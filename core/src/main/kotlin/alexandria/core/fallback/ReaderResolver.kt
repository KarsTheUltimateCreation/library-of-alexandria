package alexandria.core.fallback

import alexandria.core.cache.CacheManager
import alexandria.core.config.AggregatorSettings
import alexandria.core.config.QualityMode
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.net.classify
import alexandria.core.quality.ImageInfo
import alexandria.core.quality.ImageProbe
import alexandria.core.ranking.CandidateEvidence
import alexandria.core.ranking.ChapterQualityAnalyzer
import alexandria.core.ranking.SourceRanker
import alexandria.core.reconciliation.Release
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

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
 * take the best-ranked release. Any failure moves on to the next release when fallback is enabled.
 * The decision is cached so re-opening a chapter does not repeat the comparison.
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
    private val recent = object : LinkedHashMap<String, PageResolution>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PageResolution>?) = size > 40
    }
    private val alternatePages = ConcurrentHashMap<String, List<ReaderPage>>()

    fun recentResolution(chapterRef: String): PageResolution? = synchronized(recent) { recent[chapterRef] }

    suspend fun resolve(chapterRef: String, releases: List<Release>, settings: AggregatorSettings): PageResolution {
        val usable = releases.filter { r ->
            !r.externalOnly && registry[r.sourceId] != null &&
                (settings.enabledSources.isEmpty() || r.sourceId in settings.enabledSources)
        }
        if (usable.isEmpty()) throw NoReadableReleaseException(listOf("-" to "no enabled source carries this chapter"))
        var ordered = ranker.rank(usable, settings)
        val notes = mutableListOf<String>()
        val attempts = mutableListOf<Pair<String, String>>()

        // Re-use a previous decision when it is still among the candidates.
        cache.get("choice:$chapterRef", Choice.serializer(), 7 * CacheManager.DAY)?.let { c ->
            ordered.firstOrNull { it.sourceId == c.sourceId && it.chapterId == c.chapterId }?.let { prev ->
                ordered = listOf(prev) + (ordered - prev)
                fetchPages(prev, settings)?.let { pages ->
                    return remember(PageResolution(chapterRef, prev, pages, ordered.drop(1), listOf("cached choice")))
                }
                attempts += prev.sourceId to "cached choice failed"
                ordered = ordered.drop(1)
            }
        }

        val compare = settings.qualityOptimization && settings.qualitySwitching && ordered.size > 1

        // Fast path: a comparison earlier in this series already found the best provider. Use it directly
        // and only compare again when it lacks this chapter or fails.
        val seriesKey = chapterRef.substringBefore('|')
        if (compare) {
            cache.get("seriespref:$seriesKey:${settings.qualityMode}", SeriesPreference.serializer(), 3 * CacheManager.DAY)?.let { pref ->
                val preferred = pref.order.firstNotNullOfOrNull { sid -> ordered.firstOrNull { it.sourceId == sid && it.confidence >= 0.8 } }
                if (preferred != null) {
                    fetchPages(preferred, settings)?.let { pages ->
                        cache.put("choice:$chapterRef", Choice.serializer(), Choice(preferred.sourceId, preferred.chapterId))
                        return remember(PageResolution(chapterRef, preferred, pages, ordered - preferred, listOf("series preference")))
                    }
                    attempts += preferred.sourceId to "preferred source failed"
                }
            }
        }

        if (compare) {
            val k = when (settings.qualityMode) { QualityMode.BEST -> 3; QualityMode.BALANCED -> 2; QualityMode.DATA_SAVER -> 2 }
            // Low-confidence releases (possible numbering mismatch) never win on quality; they are fallback only.
            val contenders = ordered.filter { it.confidence >= 0.8 }.take(k)
            if (contenders.size > 1) {
                val evidence = coroutineScope {
                    contenders.map { r -> async { gather(r, settings) } }.awaitAll()
                }
                evidence.filter { it.error != null }.forEach { attempts += it.release.sourceId to (it.error?.message ?: "error") }
                val verdicts = analyzer.evaluate(evidence, settings)
                if (verdicts.isNotEmpty()) {
                    val best = verdicts.first()
                    val pages = evidence.first { it.release == best.release }.pages!!
                    notes += verdicts.map { v -> "${v.release.sourceId}=%.2f%s".format(v.score, if (v.notes.isEmpty()) "" else " (${v.notes.joinToString()})") }
                    evidence.filter { it.pages != null && it.release != best.release }.forEach { alternatePages[altKey(chapterRef, it.release)] = it.pages!! }
                    val rest = verdicts.drop(1).map { it.release } + (ordered - verdicts.map { it.release }.toSet())
                    cache.put("choice:$chapterRef", Choice.serializer(), Choice(best.release.sourceId, best.release.chapterId))
                    cache.put("seriespref:$seriesKey:${settings.qualityMode}", SeriesPreference.serializer(), SeriesPreference(verdicts.map { it.release.sourceId }.distinct()))
                    return remember(PageResolution(chapterRef, best.release, pages, rest, notes))
                }
                // Every contender failed; continue sequentially with the remaining releases.
                ordered = ordered - contenders.toSet()
            }
        }

        val tryList = if (settings.sourceFallback) ordered else ordered.take(1)
        for (r in tryList) {
            val pages = try {
                fetchPagesOrThrow(r, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempts += r.sourceId to (e.message ?: e::class.simpleName.orEmpty())
                null
            }
            if (!pages.isNullOrEmpty()) {
                if (r != ordered.first()) notes += "fell back to ${r.sourceId}"
                cache.put("choice:$chapterRef", Choice.serializer(), Choice(r.sourceId, r.chapterId))
                return remember(PageResolution(chapterRef, r, pages, ordered - r, notes))
            }
            if (pages != null) attempts += r.sourceId to "empty page list"
        }
        throw NoReadableReleaseException(attempts)
    }

    /** Page list of another release for page-level fallback (cached per chapter). */
    suspend fun alternatePages(chapterRef: String, release: Release, settings: AggregatorSettings): List<ReaderPage>? {
        alternatePages[altKey(chapterRef, release)]?.let { return it }
        return fetchPages(release, settings)?.also { alternatePages[altKey(chapterRef, release)] = it }
    }

    /** The actual image of [page] ([ReaderPage.lazy] pages are viewer pages that must be resolved first). */
    suspend fun image(sourceId: String, page: ReaderPage, settings: AggregatorSettings): ReaderPage {
        if (!page.lazy) return page
        val adapter = registry[sourceId] ?: error("unknown source $sourceId")
        return withTimeout(settings.requestTimeoutSeconds * 1000L) { adapter.resolveImage(page.imageUrl) }
    }

    private fun remember(res: PageResolution): PageResolution {
        synchronized(recent) { recent[res.chapterRef] = res }
        if (res.notes.isNotEmpty()) log("chapter ${res.chapterRef}: ${res.chosen.sourceId} [${res.notes.joinToString("; ")}]")
        return res
    }

    private suspend fun gather(r: Release, settings: AggregatorSettings): CandidateEvidence {
        val pages = try {
            fetchPagesOrThrow(r, settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CandidateEvidence(r, null, emptyList(), error = e)
        }
        // Lazy pages (viewer pages, not images) cannot be probed without resolving them first.
        if (pages.isEmpty() || pages.first().lazy) return CandidateEvidence(r, pages, emptyList())
        val indices = when (settings.qualityMode) {
            QualityMode.BEST -> listOf(0, pages.size / 2).distinct()
            QualityMode.BALANCED, QualityMode.DATA_SAVER -> listOf(0)
        }
        var reachable: Boolean? = null
        val probes = indices.map { i ->
            val page = pages[i]
            try {
                withTimeout(PROBE_TIMEOUT_MS) {
                    ImageProbe.probe(probeClient, page.imageUrl, page.headers.toHeaders(), maxBytes = 48 * 1024)
                }.also { if (i == 0) reachable = true }
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) { if (i == 0) reachable = false; null } else throw e
            } catch (e: Exception) {
                if (i == 0) reachable = false
                null
            }
        }
        return CandidateEvidence(r, pages, probes, firstPageReachable = reachable)
    }

    private suspend fun fetchPages(r: Release, settings: AggregatorSettings): List<ReaderPage>? = try {
        fetchPagesOrThrow(r, settings).takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchPagesOrThrow(r: Release, settings: AggregatorSettings): List<ReaderPage> {
        val adapter = registry[r.sourceId] ?: error("unknown source ${r.sourceId}")
        val chapter = SourceChapter(sourceId = r.sourceId, seriesId = r.seriesId, id = r.chapterId, label = r.label, language = r.language, webUrl = r.webUrl)
        val started = System.currentTimeMillis()
        return try {
            withTimeout(registry.health.timeoutFor(r.sourceId, settings.requestTimeoutSeconds * 1000L)) {
                adapter.pages(chapter, dataSaver = settings.qualityOptimization && settings.qualityMode == QualityMode.DATA_SAVER)
            }.also { registry.health.recordSuccess(r.sourceId, System.currentTimeMillis() - started) }
        } catch (e: Exception) {
            if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            registry.health.recordFailure(r.sourceId, classify(e))
            throw e
        }
    }

    private fun altKey(chapterRef: String, r: Release) = "$chapterRef|${r.sourceId}|${r.chapterId}"

    private companion object {
        /** A 48 KB header probe that takes longer than this says enough about the provider already. */
        const val PROBE_TIMEOUT_MS = 8_000L
    }
}
