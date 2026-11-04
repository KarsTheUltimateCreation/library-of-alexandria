package alexandria.core

import alexandria.core.cache.CacheManager
import alexandria.core.completeness.CompletenessAnalyzer
import alexandria.core.completeness.CompletenessReport
import alexandria.core.config.AggregatorSettings
import alexandria.core.covers.CoverResolver
import alexandria.core.covers.ScoredCover
import alexandria.core.fallback.FallbackInterceptor
import alexandria.core.fallback.NoReadableReleaseException
import alexandria.core.fallback.PageResolution
import alexandria.core.fallback.ReaderResolver
import alexandria.core.identity.IdentityResolver
import alexandria.core.identity.MatchDecision
import alexandria.core.metadata.MetadataResolver
import alexandria.core.model.ContentKind
import alexandria.core.model.CoverCandidate
import alexandria.core.model.PublicationStatus
import alexandria.core.model.SeriesMetadata
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.classify
import alexandria.core.normalization.ChapterKind
import alexandria.core.normalization.TitleNormalizer
import alexandria.core.quality.ImageProbe
import alexandria.core.ranking.ChapterQualityAnalyzer
import alexandria.core.ranking.SourceRanker
import alexandria.core.reconciliation.CanonicalChapter
import alexandria.core.reconciliation.ChapterReconciler
import alexandria.core.reconciliation.Release
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** A provider series linked to a unified series. */
@Serializable
data class Binding(val series: SourceSeries, val confidence: Double, val reason: String, val manual: Boolean = false)

/** Persisted per-series state: which provider entries make up this series. */
@Serializable
data class SeriesState(
    val key: String,
    val metadata: SeriesMetadata? = null,
    val bindings: Map<String, Binding> = emptyMap(),
    /** sourceId -> time of the last search that found no confident match. */
    val misses: Map<String, Long> = emptyMap(),
    val updatedAt: Long = 0,
)

data class SeriesCard(val key: SeriesKey, val title: String, val coverUrl: String?)

data class ChapterEntry(
    val url: String,
    val name: String,
    val number: Float,
    val uploadedAt: Long,
    val scanlator: String?,
    val releases: List<Release>,
)

data class SeriesDetails(
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genres: List<String>,
    val status: PublicationStatus,
    val coverUrl: String?,
    val webUrl: String?,
)

data class SeriesUpdate(val details: SeriesDetails, val chapters: List<ChapterEntry>?, val state: SeriesState, val report: CompletenessReport?)

/**
 * Entry point used by the host-facing extension class. Everything here is host-agnostic, so it can be unit
 * tested on the plain JVM.
 */
class Aggregator(
    val registry: SourceRegistry,
    val metadata: MetadataResolver,
    val cache: CacheManager,
    private val probeClient: OkHttpClient,
    private val identity: IdentityResolver = IdentityResolver(),
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reconciler = ChapterReconciler()
    private val completeness = CompletenessAnalyzer()
    val ranker = SourceRanker(registry.health)
    private val analyzer = ChapterQualityAnalyzer(ranker, registry.health)
    val reader = ReaderResolver(registry, ranker, analyzer, cache, probeClient, log)
    private val covers = CoverResolver(probe = { c -> ImageProbe.probe(probeClient, c.url, c.headers.toHeaders()) })

    /** Canonical chapters per series kept in memory for page resolution on hosts that do not persist memos. */
    private val canonical = ConcurrentHashMap<String, Map<String, CanonicalChapter>>()

    // ---------------------------------------------------------------- search

    /** Recent search results; hosts re-run the same search when navigating back. */
    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<SeriesCard>>>()

    suspend fun search(query: String, settings: AggregatorSettings): List<SeriesCard> {
        val cacheKey = query.trim().lowercase() + "|" + settings.searchFingerprint()
        searchCache[cacheKey]?.let { (at, cards) -> if (clock() - at < 10 * CacheManager.MINUTE) return cards }
        return searchUncached(query, settings).also {
            if (searchCache.size > 50) searchCache.clear()
            searchCache[cacheKey] = clock() to it
        }
    }

    private fun AggregatorSettings.searchFingerprint() =
        listOf(mangabaka, anilist, metadataPreference, enabledSources.sorted(), languages, highQualityCovers).hashCode()

    private suspend fun searchUncached(query: String, settings: AggregatorSettings): List<SeriesCard> = coroutineScope {
        val metaD = async { if (settings.mangabaka || settings.anilist) metadata.search(query, settings) else emptyList() }
        // Provider hits only pre-link entries; a slow provider must not hold up the results page.
        val provD = async { searchProviders(listOf(query), settings, deadlineMs = SEARCH_DEADLINE_MS) }
        val metas = metaD.await().filter { it.kind == ContentKind.UNKNOWN || it.kind.isComic }
        val hits = provD.await()

        val claimed = mutableSetOf<Pair<String, String>>()
        val withHits = mutableListOf<SeriesCard>()
        val withoutHits = mutableListOf<SeriesCard>()
        for (m in metas) {
            val key = keyFor(m) ?: continue
            val matched = hits.mapNotNull { (sid, list) -> identity.pick(m, list)?.let { sid to it } }
            matched.forEach { (sid, p) -> claimed += sid to p.first.id }
            rememberHints(key, m, matched.associate { (sid, p) -> sid to Binding(p.first, p.second.score, "search") })
            val card = SeriesCard(key, m.displayTitle, quickCover(m, matched.map { it.second.first }, settings))
            if (matched.isNotEmpty()) withHits += card else withoutHits += card
        }

        // Provider entries that matched no metadata record become their own entries, grouped by exact title.
        val orphans = hits.flatMap { (sid, list) -> list.filter { (sid to it.id) !in claimed && it.kind != ContentKind.NOVEL } }
            .groupBy { TitleNormalizer.compact(it.title) + "|" + TitleNormalizer.qualifiers(it.title).sorted() }
        val orphanCards = orphans.values.map { group ->
            val anchor = group.sortedBy { settings.sourceRank(it.sourceId) }.first()
            val key = SeriesKey.Provider(anchor.sourceId, anchor.id)
            rememberHints(key, null, group.distinctBy { it.sourceId }.associate { it.sourceId to Binding(it, 0.95, "search title") })
            SeriesCard(key, anchor.title, anchor.coverUrl)
        }
        withHits + orphanCards + withoutHits
    }

    suspend fun popular(page: Int, settings: AggregatorSettings): Pair<List<SeriesCard>, Boolean> {
        metadata.popular(page, settings)?.takeIf { it.isNotEmpty() }?.let { list ->
            return list.mapNotNull { m -> keyFor(m)?.let { SeriesCard(it, m.displayTitle, quickCover(m, emptyList(), settings)) } } to true
        }
        val adapter = registry.enabled(settings, includeUnhealthy = false).firstOrNull() ?: return emptyList<SeriesCard>() to false
        val list = guarded(adapter, settings) { adapter.popular(page) }.orEmpty()
        return list.map { SeriesCard(SeriesKey.Provider(it.sourceId, it.id), it.title, it.coverUrl) } to list.isNotEmpty()
    }

    suspend fun latest(page: Int, settings: AggregatorSettings): Pair<List<SeriesCard>, Boolean> = coroutineScope {
        val lists = registry.enabled(settings, includeUnhealthy = false).take(2).map { a ->
            async { guarded(a, settings) { a.latest(page) }.orEmpty() }
        }.awaitAll()
        val merged = lists.flatten().distinctBy { TitleNormalizer.compact(it.title) }
        merged.map { SeriesCard(SeriesKey.Provider(it.sourceId, it.id), it.title, it.coverUrl) } to merged.isNotEmpty()
    }

    // ---------------------------------------------------------------- details + chapters

    suspend fun update(
        key: SeriesKey,
        knownState: SeriesState?,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        settings: AggregatorSettings,
    ): SeriesUpdate {
        var state = loadState(key, knownState)

        // 1. Identity / metadata.
        if (fetchDetails || state.metadata == null) {
            val anchor = (key as? SeriesKey.Provider)?.let { providerAnchor(it, state, settings) }
            val meta = metadata.forKey(key, settings, anchor) ?: state.metadata ?: anchor?.asMetadata()
            state = state.copy(metadata = meta)
            if (anchor != null) state = state.copy(bindings = state.bindings + (anchor.sourceId to Binding(anchor, 1.0, "entry source")))
        }
        val meta = state.metadata

        // 2. Link providers.
        if (meta != null) state = resolveBindings(key, meta, state, settings)
        saveState(state)

        // 3 + 4. Chapters and cover are independent: fetch them concurrently.
        val finalState = state
        val (built, cover) = coroutineScope {
            val chaptersD = async { if (fetchChapters) buildChapters(key, finalState, settings) else null }
            val coverD = async { if (fetchDetails) resolveCover(key, finalState, settings) else null }
            chaptersD.await() to coverD.await()
        }
        built?.let { canonical[key.value] = it.canonical.associateBy { c -> c.key } }
        val details = buildDetails(state, cover, built?.report, settings)
        return SeriesUpdate(details, built?.entries, state, built?.report)
    }

    private data class Built(val entries: List<ChapterEntry>, val report: CompletenessReport?, val canonical: List<CanonicalChapter>)

    private suspend fun buildChapters(key: SeriesKey, state: SeriesState, settings: AggregatorSettings): Built = coroutineScope {
        val enabled = registry.enabled(settings).map { it.id }.toSet()
        val bound = state.bindings.filterKeys { it in enabled }
        val gate = Semaphore(settings.maxConcurrentSearches.coerceIn(1, 6))
        val lists = bound.map { (sid, b) ->
            async {
                val adapter = registry[sid] ?: return@async sid to emptyList<SourceChapter>()
                val fresh = gate.withPermit { guarded(adapter, settings) { adapter.chapters(b.series, settings.languages) } }
                val cacheKey = "ch:$sid:${b.series.id}"
                if (fresh != null) {
                    cache.put(cacheKey, ListSerializer(SourceChapter.serializer()), fresh, persist = false)
                    sid to fresh
                } else {
                    // Provider failed: keep its last known chapters so the list does not shrink.
                    sid to (cache.get(cacheKey, ListSerializer(SourceChapter.serializer()), Long.MAX_VALUE) ?: emptyList())
                }
            }
        }.awaitAll().toMap().filterValues { it.isNotEmpty() }

        val result = reconciler.reconcile(lists, settings)
        var chapters = result.chapters

        // Missing-chapter recovery off: show only what the primary provider has (others still back it up).
        val primary = primarySource(chapters, settings)
        if (!settings.missingChapterRecovery && primary != null) {
            chapters = chapters.filter { c -> c.releases.any { it.sourceId == primary } }
        }

        val report = if (settings.completenessChecking) {
            completeness.analyze(result.copy(chapters = chapters), state.metadata?.totalChapters, state.metadata?.status ?: PublicationStatus.UNKNOWN, settings.languages.firstOrNull())
        } else {
            null
        }

        val seasoned = chapters.any { it.identity.season != null }
        val entries = chapters.mapIndexed { i, c ->
            val ranked = ranker.rank(c.releases, settings).ifEmpty { c.releases }
            ChapterEntry(
                url = ChapterUrl.build(key, c.key),
                name = displayName(c, settings),
                number = when {
                    c.identity.kind != ChapterKind.MAIN -> c.identity.number?.toFloat() ?: -1f
                    seasoned -> (i + 1).toFloat()
                    else -> c.identity.number?.toFloat() ?: -1f
                },
                uploadedAt = c.earliestUpload ?: 0L,
                scanlator = c.sources.mapNotNull { registry[it]?.name }.joinToString(" · ").ifEmpty { null },
                releases = ranked,
            )
        }.asReversed() // hosts expect newest first
        Built(entries, report, chapters)
    }

    private fun primarySource(chapters: List<CanonicalChapter>, settings: AggregatorSettings): String? {
        settings.preferredSource?.let { p -> if (chapters.any { c -> c.sources.contains(p) }) return p }
        val coverage = chapters.flatMap { it.sources }.groupingBy { it }.eachCount()
        return coverage.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { settings.sourceRank(it.key) }).firstOrNull()?.key
    }

    // ---------------------------------------------------------------- pages

    suspend fun pages(chapterUrl: String, memoReleases: List<Release>?, settings: AggregatorSettings): PageResolution {
        val (key, chapterKey) = ChapterUrl.parse(chapterUrl) ?: throw IllegalArgumentException("Not a Library of Alexandria chapter: $chapterUrl")
        val releases = memoReleases?.takeIf { it.isNotEmpty() }
            ?: canonical[key.value]?.get(chapterKey)?.releases
            ?: run {
                // Host did not keep our memo (or app restarted): rebuild from persisted provider links.
                update(key, null, fetchDetails = false, fetchChapters = true, settings)
                canonical[key.value]?.get(chapterKey)?.releases
            }
            ?: throw IllegalStateException("Chapter is no longer listed by any enabled source. Refresh the series.")
        val ref = ChapterUrl.build(key, chapterKey)
        val resolution = try {
            reader.resolve(ref, releases, settings)
        } catch (e: NoReadableReleaseException) {
            // Releases stored in the chapter memo may be stale (chapter re-uploaded, provider re-linked): refresh once.
            if (memoReleases.isNullOrEmpty()) throw e
            update(key, null, fetchDetails = false, fetchChapters = true, settings)
            val fresh = canonical[key.value]?.get(chapterKey)?.releases ?: throw e
            reader.resolve(ref, fresh, settings)
        }
        val pages = resolution.pages.map { p -> p.copy(imageUrl = FallbackInterceptor.tagPage(p.imageUrl, ref, p.index, resolution.chosen.sourceId)) }
        return resolution.copy(pages = pages)
    }

    // ---------------------------------------------------------------- binding

    private suspend fun resolveBindings(key: SeriesKey, meta: SeriesMetadata, state0: SeriesState, settings: AggregatorSettings): SeriesState {
        var state = state0
        // Manual links from settings win over automatic matching.
        settings.manualLinks[key.value]?.forEach { target ->
            val sid = target.substringBefore(':')
            val id = target.substringAfter(':')
            val adapter = registry[sid] ?: return@forEach
            state = if (id == "-" || id.isBlank()) {
                state.copy(bindings = state.bindings - sid, misses = state.misses + (sid to Long.MAX_VALUE))
            } else {
                val existing = state.bindings[sid]
                if (existing?.series?.id == id) state else {
                    val series = guarded(adapter, settings) { adapter.details(SourceSeries(sid, id, meta.title)) } ?: SourceSeries(sid, id, meta.title)
                    state.copy(bindings = state.bindings + (sid to Binding(series, 1.0, "manual link", manual = true)))
                }
            }
        }

        // Links pre-made from search results are title-only. Verify them with provider details (year,
        // tracker ids, alt titles) before trusting them, exactly like a normal match.
        val unverified = state.bindings.filter { (sid, b) -> !b.manual && !b.series.detailed && b.series.ids.isEmpty && registry[sid] != null }
        val verified = coroutineScope {
            unverified.map { (sid, b) -> async { Triple(sid, b, guarded(registry[sid]!!, settings) { registry[sid]!!.details(b.series) }) } }.awaitAll()
        }
        for ((sid, b, detailed) in verified) {
            if (detailed == null) continue
            val verdict = identity.match(meta, detailed)
            state = if (verdict.decision == MatchDecision.CONFIRMED) {
                state.copy(bindings = state.bindings + (sid to b.copy(series = detailed, confidence = verdict.score)))
            } else {
                log("dropping unverified link $sid:${b.series.id} for ${key.value}: ${verdict.reasons.joinToString()}")
                state.copy(bindings = state.bindings - sid)
            }
        }

        val now = clock()
        // Re-search providers that had no match weekly, not on every library refresh.
        val missTtl = 7 * CacheManager.DAY
        val toFind = registry.enabled(settings, includeUnhealthy = false).filter { a ->
            a.id !in state.bindings && (state.misses[a.id]?.let { now - it > missTtl } ?: true)
        }
        if (toFind.isEmpty()) return state

        val queries = meta.searchQueries()
        // Stop trying further title variants on a provider as soon as one query yields a confident match.
        val results = searchProviders(queries, settings, toFind, stopWhen = { identity.pick(meta, it) != null })
        val found = ConcurrentHashMap<String, Binding>()
        val missed = ConcurrentHashMap<String, Long>()
        coroutineScope { toFind.map { adapter -> async {
            val candidates = results[adapter.id].orEmpty()
            var pick = identity.pick(meta, candidates)
            if (pick == null || pick.first.ids.isEmpty) {
                // Progressive evidence: fetch details (alt titles, tracker ids) for the most plausible hits.
                val detailed = identity.needsDetails(meta, candidates).mapNotNull { c -> guarded(adapter, settings) { adapter.details(c) } }
                if (detailed.isNotEmpty()) {
                    val merged = candidates.map { c -> detailed.firstOrNull { it.id == c.id } ?: c }
                    // Details are authoritative: if they disprove the title-only match, there is no match.
                    pick = identity.pick(meta, merged)
                }
            }
            if (pick != null) {
                found[adapter.id] = Binding(pick.first, pick.second.score, pick.second.reasons.joinToString("; "))
            } else if (results.containsKey(adapter.id)) {
                missed[adapter.id] = now
            }
        } }.awaitAll() }
        return state.copy(bindings = state.bindings + found, misses = state.misses + missed)
    }

    private suspend fun searchProviders(
        queries: List<String>,
        settings: AggregatorSettings,
        adapters: List<SourceAdapter> = registry.enabled(settings, includeUnhealthy = false),
        deadlineMs: Long? = null,
        stopWhen: (List<SourceSeries>) -> Boolean = { false },
    ): Map<String, List<SourceSeries>> = coroutineScope {
        val gate = Semaphore(settings.maxConcurrentSearches.coerceIn(1, 6))
        adapters.map { a ->
            async {
                val collected = mutableListOf<SourceSeries>()
                var anySuccess = false
                val work: suspend () -> Unit = {
                    for (q in queries.filter { it.isNotBlank() }) {
                        val r = gate.withPermit { guarded(a, settings) { a.search(q) } } ?: continue
                        anySuccess = true
                        collected += r
                        if (stopWhen(collected) || r.any { TitleNormalizer.compact(it.title) == TitleNormalizer.compact(q) }) break
                    }
                }
                if (deadlineMs != null) withTimeoutOrNull(deadlineMs) { work() } else work()
                if (anySuccess) a.id to collected.distinctBy { it.id } else null
            }
        }.awaitAll().filterNotNull().toMap()
    }

    private suspend fun providerAnchor(key: SeriesKey.Provider, state: SeriesState, settings: AggregatorSettings): SourceSeries? {
        val adapter = registry[key.sourceId] ?: return state.bindings[key.sourceId]?.series
        val base = state.bindings[key.sourceId]?.series ?: SourceSeries(key.sourceId, key.seriesId, state.metadata?.title ?: key.seriesId)
        return guarded(adapter, settings) { adapter.details(base.copy(detailed = false)) } ?: base
    }

    // ---------------------------------------------------------------- covers

    private suspend fun resolveCover(key: SeriesKey, state: SeriesState, settings: AggregatorSettings): List<ScoredCover> {
        val cacheKey = "cover:${key.value}:${settings.highQualityCovers}:${settings.coverProvider}:${settings.preferOriginalArtwork}"
        if (settings.cacheCovers) {
            cache.get(cacheKey, ListSerializer(CoverCandidate.serializer()), 7 * CacheManager.DAY)?.let { cached ->
                return cached.map { ScoredCover(it, 0.0, true, emptyList()) }
            }
        }
        val meta = state.metadata
        val enabled = registry.enabled(settings).map { it.id }.toSet()
        val bound = state.bindings.filterKeys { it in enabled }
        val candidates = mutableListOf<CoverCandidate>()
        if (settings.highQualityCovers) meta?.covers?.let { candidates += it }
        coroutineScope {
            bound.map { (sid, b) ->
                async {
                    val adapter = registry[sid] ?: return@async emptyList()
                    // Only MangaDex exposes extra (original) covers; others just give their thumbnail.
                    if (settings.highQualityCovers || sid == primaryBinding(state, settings)) {
                        guarded(adapter, settings) { adapter.covers(b.series) }.orEmpty()
                    } else {
                        emptyList()
                    }
                }
            }.awaitAll().forEach { candidates += it }
        }
        if (candidates.isEmpty()) meta?.covers?.let { candidates += it }
        val ranked = covers.rank(candidates, settings, meta?.originalLanguage, primaryBinding(state, settings))
        if (settings.cacheCovers && ranked.isNotEmpty()) {
            cache.put(cacheKey, ListSerializer(CoverCandidate.serializer()), ranked.take(4).map { it.candidate })
        }
        return ranked
    }

    private fun primaryBinding(state: SeriesState, settings: AggregatorSettings): String? =
        state.bindings.keys.sortedBy { settings.sourceRank(it) }.firstOrNull()

    /** Browse-grid thumbnail: small pre-resized art; the full-quality cover is chosen when the series is opened. */
    private fun quickCover(m: SeriesMetadata, hits: List<SourceSeries>, settings: AggregatorSettings): String? {
        val meta = m.thumbnailUrl ?: m.covers.sortedByDescending { it.coverOrigin.trust }.firstOrNull()?.url
        return if (settings.highQualityCovers) meta ?: hits.firstNotNullOfOrNull { it.coverUrl } else hits.firstNotNullOfOrNull { it.coverUrl } ?: meta
    }

    // ---------------------------------------------------------------- presentation

    private fun buildDetails(state: SeriesState, covers: List<ScoredCover>?, report: CompletenessReport?, settings: AggregatorSettings): SeriesDetails {
        val m = state.metadata
        val coverUrl = covers?.firstOrNull()?.let { best ->
            if (settings.coverFallback) {
                FallbackInterceptor.tagCover(best.candidate.url, covers.drop(1).take(3).map { FallbackInterceptor.CoverAlt(it.candidate.url, it.candidate.headers) })
            } else {
                best.candidate.url
            }
        }
        val description = buildString {
            m?.description?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
            val alt = m?.allTitles()?.drop(1)?.take(6)
            if (!alt.isNullOrEmpty()) append("Also known as: ").append(alt.joinToString(" · ")).append("\n\n")
            append("— Library of Alexandria —\n")
            val enabled = registry.enabled(settings).map { it.id }.toSet()
            val linked = state.bindings.filterKeys { it in enabled }
            if (linked.isEmpty()) {
                append("No enabled source carries this series yet.\n")
            } else {
                append("Sources: ").append(linked.keys.mapNotNull { registry[it]?.name }.joinToString(", ")).append('\n')
            }
            report?.summaryLines()?.forEach { append(it).append('\n') }
            m?.totalChapters?.let { append("Original run: ").append(it).append(" chapters (per ").append(m.provider).append(")\n") }
            val credit = listOfNotNull(
                "MangaBaka (CC BY-NC-SA 4.0)".takeIf { m?.ids?.mangabaka != null && settings.mangabaka },
                "AniList".takeIf { m?.ids?.anilist != null && settings.anilist },
            )
            if (credit.isNotEmpty()) append("Metadata: ").append(credit.joinToString(", ")).append('\n')
        }.trim()
        return SeriesDetails(
            title = m?.displayTitle ?: state.bindings.values.firstOrNull()?.series?.title ?: state.key,
            author = m?.authors?.joinToString()?.ifEmpty { null },
            artist = m?.artists?.joinToString()?.ifEmpty { null },
            description = description,
            genres = m?.genres.orEmpty(),
            status = m?.status ?: PublicationStatus.UNKNOWN,
            coverUrl = coverUrl,
            webUrl = m?.webUrl ?: state.bindings.values.firstOrNull()?.series?.webUrl,
        )
    }

    private fun displayName(c: CanonicalChapter, settings: AggregatorSettings): String {
        val id = c.identity
        val n = id.number?.toString()
        val base = when (id.kind) {
            ChapterKind.MAIN -> "Chapter $n"
            ChapterKind.EXTRA -> if (n != null) "Extra (ch. $n)" else "Extra"
            ChapterKind.SIDE_STORY -> if (n != null) "Side Story $n" else "Side Story"
            ChapterKind.PROLOGUE -> if (n != null) "Prologue $n" else "Prologue"
            ChapterKind.EPILOGUE -> if (n != null) "Epilogue $n" else "Epilogue"
            ChapterKind.ONESHOT -> "Oneshot"
            ChapterKind.SPECIAL -> if (n != null) "Special $n" else "Special"
            ChapterKind.NOTICE -> "Notice"
            ChapterKind.UNKNOWN -> c.releases.first().label
        }
        return buildString {
            id.season?.let { append("S").append(it).append(" · ") }
            append(base)
            id.part?.let { append(" (Part ").append(it).append(')') }
            c.title?.takeIf { id.kind != ChapterKind.UNKNOWN }?.let { append(": ").append(it) }
            if (settings.showAlternativeTranslations) c.releases.first().group?.let { append(" [").append(it).append(']') }
            if (c.language != settings.languages.firstOrNull()) append(" (").append(c.language).append(')')
        }
    }

    // ---------------------------------------------------------------- state

    private fun loadState(key: SeriesKey, known: SeriesState?): SeriesState {
        val stored = cache.get("state:${key.value}", SeriesState.serializer(), Long.MAX_VALUE)
        return when {
            known != null && stored != null -> if (known.updatedAt >= stored.updatedAt) known else stored
            else -> known ?: stored ?: SeriesState(key.value)
        }
    }

    private fun saveState(state: SeriesState) {
        cache.put("state:${state.key}", SeriesState.serializer(), state.copy(updatedAt = clock()))
    }

    /** Search results pre-link providers so opening a series is fast; never overrides an existing state. */
    private fun rememberHints(key: SeriesKey, meta: SeriesMetadata?, bindings: Map<String, Binding>) {
        if (bindings.isEmpty() && meta == null) return
        val existing = cache.get("state:${key.value}", SeriesState.serializer(), Long.MAX_VALUE)
        if (existing != null) return
        cache.put("state:${key.value}", SeriesState.serializer(), SeriesState(key.value, meta, bindings, updatedAt = 0), persist = false)
    }

    fun forgetSeries(key: SeriesKey) {
        cache.remove("state:${key.value}")
        canonical.remove(key.value)
    }

    private companion object {
        const val SEARCH_DEADLINE_MS = 6_000L
    }

    private fun keyFor(m: SeriesMetadata): SeriesKey? =
        m.ids.mangabaka?.let { SeriesKey.MangaBaka(it) } ?: m.ids.anilist?.let { SeriesKey.AniList(it) }

    private suspend fun <T> guarded(adapter: SourceAdapter, settings: AggregatorSettings, block: suspend () -> T): T? {
        val started = clock()
        return try {
            withTimeout(settings.requestTimeoutSeconds * 1000L) { block() }.also {
                registry.health.recordSuccess(adapter.id, clock() - started)
            }
        } catch (e: Exception) {
            if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            registry.health.recordFailure(adapter.id, classify(e))
            log("${adapter.id} failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }
}
