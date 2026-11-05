package alexandria.core.fallback

import alexandria.core.cache.BoundedCache
import alexandria.core.cache.CacheManager
import alexandria.core.cache.SingleFlight
import alexandria.core.config.AggregatorSettings
import alexandria.core.config.QualityMode
import alexandria.core.config.SourcePolicy
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.net.ProviderTimeoutException
import alexandria.core.net.withProviderTimeout
import alexandria.core.quality.ImageProbe
import alexandria.core.ranking.CandidateEvidence
import alexandria.core.ranking.ChapterQualityAnalyzer
import alexandria.core.ranking.SourceRanker
import alexandria.core.reconciliation.Release
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient

class NoReadableReleaseException(val attempts: List<Pair<String, String>>) :
    Exception("No source could provide this chapter: " + attempts.joinToString { "${it.first}: ${it.second}" })

data class PageResolution(
    val chapterRef: String,
    val chosen: Release,
    val pages: List<ReaderPage>,
    /** Remaining releases, best first, usable for page-level fallback. */
    val fallbacks: List<Release>,
    val notes: List<String>,
)

@Serializable
private data class Choice(val sourceId: String, val chapterId: String)

/** Provider ranking from the last full comparison in a series (best first). */
@Serializable
private data class SeriesPreference(val order: List<String>)

/**
 * Chooses which provider serves a chapter and returns its page list.
 *
 * Steps: rank releases cheaply ([SourceRanker]); if quality optimisation is on, fetch page lists of the top
 * candidates in parallel and probe a few image headers, then score them ([ChapterQualityAnalyzer]); otherwise
 * take the best-ranked release. Any failure — including a provider exceeding its own deadline — moves on to the
 * next release when fallback is enabled; only cancellation of the caller stops the search.
 *
 * Decisions are cached under [AggregatorSettings.selectionFingerprint], so re-opening a chapter does not repeat the
 * comparison but a change of preferred source or reading quality does.
 *
 * Limits: a series preference (the ranking from the last comparison in the series, reused for 3 days) skips the
 * comparison for later chapters. It is a speed trade-off: it does not re-measure quality for each chapter, and is
 * dropped as soon as the preferred provider lacks or fails a chapter.
 */
class ReaderResolver(
    private val registry: SourceRegistry,
    private val ranker: SourceRanker,
    private val analyzer: ChapterQualityAnalyzer,
    private val cache: CacheManager,
    private val probeClient: OkHttpClient,
    private val log: (String) -> Unit = {},
) {
    /** Recent resolutions, used by [FallbackInterceptor] to substitute failing images. */
    private val recent = BoundedCache<String, PageResolution>(40)

    /** Page lists of non-chosen releases. Image URLs often carry expiring tokens, hence the short lifetime. */
    private val alternatePages = BoundedCache<String, List<ReaderPage>>(96, ttlMillis = 20 * CacheManager.MINUTE)
    private val pageFlights = SingleFlight<String, List<ReaderPage>>()

    fun recentResolution(chapterRef: String): PageResolution? = recent[chapterRef]

    suspend fun resolve(chapterRef: String, releases: List<Release>, settings: AggregatorSettings): PageResolution {
        val usable = releases.filter { allowed(it, settings) }.distinctBy { it.sourceId to it.chapterId }
        if (usable.isEmpty()) throw NoReadableReleaseException(listOf("-" to "no enabled source carries this chapter"))
        var ordered = ranker.rank(usable, settings)
        val notes = mutableListOf<String>()
        val attempts = mutableListOf<Pair<String, String>>()
        val fingerprint = settings.selectionFingerprint()
        val choiceKey = "choice:v2:$fingerprint:$chapterRef"

        // Re-use a previous decision (made under the same settings) when it is still among the candidates.
        cache.get(choiceKey, Choice.serializer(), 7 * CacheManager.DAY)?.let { c ->
            ordered.firstOrNull { it.sourceId == c.sourceId && it.chapterId == c.chapterId }?.let { prev ->
                ordered = listOf(prev) + (ordered - prev)
                when (val result = tryPages(prev, settings)) {
                    is Fetched.Ok -> return remember(PageResolution(chapterRef, prev, result.pages, ordered.drop(1), listOf("cached choice")))
                    is Fetched.Failed -> attempts += prev.sourceId to "cached choice failed: ${result.reason}"
                }
                ordered = ordered.drop(1)
                if (!settings.sourceFallback) throw NoReadableReleaseException(attempts)
            }
        }

        val compare = settings.qualityOptimization && settings.qualitySwitching && ordered.size > 1

        // Fast path: a comparison earlier in this series already found the best provider. Use it directly
        // and only compare again when it lacks this chapter or fails.
        val seriesKey = chapterRef.substringBefore('|')
        val prefKey = "seriespref:v2:$fingerprint:$seriesKey"
        if (compare) {
            cache.get(prefKey, SeriesPreference.serializer(), 3 * CacheManager.DAY)?.let { pref ->
                val preferred = pref.order.firstNotNullOfOrNull { sid -> ordered.firstOrNull { it.sourceId == sid && it.confidence >= 0.8 } }
                if (preferred != null) {
                    when (val result = tryPages(preferred, settings)) {
                        is Fetched.Ok -> {
                            cache.put(choiceKey, Choice.serializer(), Choice(preferred.sourceId, preferred.chapterId))
                            return remember(PageResolution(chapterRef, preferred, result.pages, ordered - preferred, listOf("series preference")))
                        }
                        is Fetched.Failed -> {
                            attempts += preferred.sourceId to "preferred source failed: ${result.reason}"
                            ordered = ordered - preferred
                        }
                    }
                }
            }
        }

        if (compare) {
            val k = when (settings.qualityMode) { QualityMode.BEST -> 3; QualityMode.BALANCED -> 2; QualityMode.DATA_SAVER -> 2 }
            // Low-confidence releases (possible numbering mismatch) never win on quality; they are fallback only.
            val contenders = ordered.filter { it.confidence >= 0.8 }.take(k)
            if (contenders.size > 1) {
                val evidence = compareCandidates(contenders, settings)
                evidence.filter { it.error != null }.forEach { attempts += it.release.sourceId to (it.error?.message ?: "error") }
                val verdicts = analyzer.evaluate(evidence, settings)
                if (verdicts.isNotEmpty()) {
                    val best = verdicts.first()
                    val pages = evidence.first { it.release == best.release }.pages!!
                    notes += verdicts.map { v -> "${v.release.sourceId}=%.2f%s".format(v.score, if (v.notes.isEmpty()) "" else " (${v.notes.joinToString()})") }
                    evidence.filter { it.pages != null && it.release != best.release }.forEach { alternatePages[altKey(chapterRef, it.release)] = it.pages!! }
                    val rest = verdicts.drop(1).map { it.release } + (ordered - verdicts.map { it.release }.toSet())
                    cache.put(choiceKey, Choice.serializer(), Choice(best.release.sourceId, best.release.chapterId))
                    cache.put(prefKey, SeriesPreference.serializer(), SeriesPreference(verdicts.map { it.release.sourceId }.distinct()))
                    return remember(PageResolution(chapterRef, best.release, pages, rest, notes))
                }
                // Every contender failed; continue sequentially with the remaining releases.
                ordered = ordered - contenders.toSet()
            }
        }

        val tryList = if (settings.sourceFallback) ordered else ordered.take(1)
        for (r in tryList) {
            when (val result = tryPages(r, settings)) {
                is Fetched.Ok -> {
                    if (attempts.isNotEmpty()) notes += "fell back to ${r.sourceId}"
                    cache.put(choiceKey, Choice.serializer(), Choice(r.sourceId, r.chapterId))
                    return remember(PageResolution(chapterRef, r, result.pages, ordered - r, notes))
                }
                is Fetched.Failed -> attempts += r.sourceId to result.reason
            }
        }
        throw NoReadableReleaseException(attempts)
    }

    /**
     * Gathers evidence for [contenders] in parallel. Once one candidate is usable the others get a short grace
     * period; a provider still stalling after that is left out of the comparison (it stays a fallback) instead of
     * holding the chapter until its own timeout.
     */
    private suspend fun compareCandidates(contenders: List<Release>, settings: AggregatorSettings): List<CandidateEvidence> = coroutineScope {
        val jobs = contenders.map { r -> async { gather(r, settings) } }
        val firstUsable = CompletableDeferred<Unit>()
        jobs.forEach { d -> launch { if (!d.await().pages.isNullOrEmpty()) firstUsable.complete(Unit) } }
        launch { jobs.joinAll(); firstUsable.complete(Unit) }
        firstUsable.await()
        withTimeoutOrNull(COMPARISON_GRACE_MS) { jobs.joinAll() }
        val evidence = jobs.mapIndexed { i, d ->
            if (d.isCompleted && !d.isCancelled) d.getCompleted()
            else CandidateEvidence(contenders[i], null, emptyList(), error = ProviderTimeoutException(contenders[i].sourceId, COMPARISON_GRACE_MS, ProviderTimeoutException.Phase.NETWORK))
        }
        coroutineContext.cancelChildren() // stragglers: our own cancellation, not recorded as provider failures
        evidence
    }

    /** Whether [r] may be read under [settings] now (its provider exists and is enabled; 18+ switch included). */
    fun allowed(r: Release, settings: AggregatorSettings): Boolean =
        !r.externalOnly && registry[r.sourceId] != null && SourcePolicy.allows(settings, r.sourceId)

    /**
     * Page list of another release for page-level fallback (cached per chapter). The current source policy is checked
     * first, before the cached list too: a resolution prepared before a provider was disabled must not reach it.
     */
    suspend fun alternatePages(chapterRef: String, release: Release, settings: AggregatorSettings): List<ReaderPage>? {
        if (!allowed(release, settings)) return null
        alternatePages[altKey(chapterRef, release)]?.let { return it }
        return (tryPages(release, settings) as? Fetched.Ok)?.pages?.also { alternatePages[altKey(chapterRef, release)] = it }
    }

    /** The actual image of [page] ([ReaderPage.lazy] pages are viewer pages that must be resolved first). */
    suspend fun image(sourceId: String, page: ReaderPage, settings: AggregatorSettings): ReaderPage {
        if (!page.lazy) return page
        val adapter = registry[sourceId] ?: error("unknown source $sourceId")
        check(SourcePolicy.allows(settings, sourceId)) { "$sourceId is disabled" }
        return registry.health.call(sourceId, settings.requestTimeoutSeconds * 1000L) { adapter.resolveImage(page.imageUrl) }
    }

    private fun remember(res: PageResolution): PageResolution {
        recent[res.chapterRef] = res
        if (res.notes.isNotEmpty()) log("chapter ${res.chapterRef}: ${res.chosen.sourceId} [${res.notes.joinToString("; ")}]")
        return res
    }

    private suspend fun gather(r: Release, settings: AggregatorSettings): CandidateEvidence {
        val pages = when (val result = tryPages(r, settings)) {
            is Fetched.Ok -> result.pages
            is Fetched.Failed -> return CandidateEvidence(r, null, emptyList(), error = result.error)
        }
        // Lazy pages (viewer pages, not images) cannot be probed without resolving them first.
        if (pages.first().lazy) return CandidateEvidence(r, pages, emptyList())
        val indices = when (settings.qualityMode) {
            QualityMode.BEST -> listOf(0, pages.size / 2).distinct()
            QualityMode.BALANCED, QualityMode.DATA_SAVER -> listOf(0)
        }
        var reachable: Boolean? = null
        val probes = indices.map { i ->
            val page = pages[i]
            try {
                withProviderTimeout("${r.sourceId} image", PROBE_TIMEOUT_MS) {
                    ImageProbe.probe(probeClient, page.imageUrl, page.headers.toHeaders(), maxBytes = 48 * 1024)
                }.also { if (i == 0) reachable = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (i == 0) reachable = false
                null
            }
        }
        return CandidateEvidence(r, pages, probes, firstPageReachable = reachable)
    }

    private sealed interface Fetched {
        class Ok(val pages: List<ReaderPage>) : Fetched
        class Failed(val error: Throwable) : Fetched {
            val reason: String get() = error.message ?: error::class.simpleName.orEmpty()
        }
    }

    /** Fetches [r]'s page list. Every failure of the provider, its own timeout included, is a [Fetched.Failed]. */
    private suspend fun tryPages(r: Release, settings: AggregatorSettings): Fetched = try {
        val pages = fetchPagesOrThrow(r, settings)
        if (pages.isEmpty()) Fetched.Failed(IllegalStateException("empty page list")) else Fetched.Ok(pages)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Fetched.Failed(e)
    }

    private suspend fun fetchPagesOrThrow(r: Release, settings: AggregatorSettings): List<ReaderPage> {
        val adapter = registry[r.sourceId] ?: error("unknown source ${r.sourceId}")
        val dataSaver = settings.qualityOptimization && settings.qualityMode == QualityMode.DATA_SAVER
        // Concurrent requests for the same page list (comparison, image fallback, a second reader) share one fetch.
        return pageFlights.run("${r.sourceId}|${r.seriesId}|${r.chapterId}|$dataSaver") {
            val chapter = SourceChapter(sourceId = r.sourceId, seriesId = r.seriesId, id = r.chapterId, label = r.label, language = r.language, webUrl = r.webUrl)
            registry.health.call(r.sourceId, settings.requestTimeoutSeconds * 1000L) { adapter.pages(chapter, dataSaver) }
        }
    }

    private fun altKey(chapterRef: String, r: Release) = "$chapterRef|${r.sourceId}|${r.chapterId}"

    private companion object {
        /** A 48 KB header probe that takes longer than this says enough about the provider already. */
        const val PROBE_TIMEOUT_MS = 8_000L

        /** How long other candidates may still take once one candidate is usable. */
        const val COMPARISON_GRACE_MS = 4_000L
    }
}
