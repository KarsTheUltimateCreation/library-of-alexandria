package alexandria.core

import alexandria.core.cache.BoundedCache
import alexandria.core.cache.CacheManager
import alexandria.core.cache.SingleFlight
import alexandria.core.cache.StoredChapterList
import alexandria.core.cache.mergeReleases
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
import alexandria.core.merge.DetailsMerger
import alexandria.core.merge.MergedDetails
import alexandria.core.merge.LocalFilters
import alexandria.core.merge.SearchClusterer
import alexandria.core.net.RateLimiter
import alexandria.core.normalization.ChapterLabelParser
import alexandria.core.metadata.CatalogFilters
import alexandria.core.metadata.MetadataResolver
import alexandria.core.model.ContentKind
import alexandria.core.model.CoverCandidate
import alexandria.core.model.PublicationStatus
import alexandria.core.model.SeriesMetadata
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.normalization.ChapterKind
import alexandria.core.normalization.SearchRelevance
import alexandria.core.normalization.TitleNormalizer
import alexandria.core.quality.ImageProbe
import alexandria.core.ranking.ChapterQualityAnalyzer
import alexandria.core.ranking.SourceRanker
import alexandria.core.reconciliation.CanonicalChapter
import alexandria.core.reconciliation.ChapterReconciler
import alexandria.core.reconciliation.Release
import alexandria.core.sources.ProviderRole
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * A provider series linked to a unified series. [hint] marks a link taken from search results that has not been
 * verified yet (title only): it is checked against the provider's details before it is trusted or persisted.
 */
@Serializable
data class Binding(val series: SourceSeries, val confidence: Double, val reason: String, val manual: Boolean = false, val hint: Boolean = false) {
    /** Hints, including ones written by 1.6.x (title-only search links that were never detailed). */
    val unverified: Boolean get() = hint || (!manual && !series.detailed && series.ids.isEmpty && reason.startsWith("search"))
}

/** Persisted per-series state: which provider entries make up this series. */
@Serializable
data class SeriesState(
    val key: String,
    val metadata: SeriesMetadata? = null,
    val bindings: Map<String, Binding> = emptyMap(),
    /** sourceId -> time of the last search that found no confident match. */
    val misses: Map<String, Long> = emptyMap(),
    val updatedAt: Long = 0,
    /** sourceId -> provider series a verification rejected for this series; never linked or grouped automatically again. */
    val rejected: Map<String, String> = emptyMap(),
    /** Last background search of every remaining provider (progressive discovery), to keep library updates cheap. */
    val wideAt: Long = 0,
    /**
     * Confirmed matches beyond the per-series cap (sourceId -> provider series). Not read from, but promoted without a
     * new search when a linked source fails or chapters are missing.
     */
    val spares: Map<String, Binding> = emptyMap(),
)

/** One search/browse result. [hit] is the provider entry it was built from (for local filtering), when there is one. */
data class SeriesCard(val key: SeriesKey, val title: String, val coverUrl: String?, val hit: SourceSeries? = null)

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

data class SeriesUpdate(
    val details: SeriesDetails,
    val chapters: List<ChapterEntry>?,
    val state: SeriesState,
    val report: CompletenessReport?,
    /**
     * Linked providers whose chapter list could not be fetched and had no last known copy. Their chapters are
     * missing from [chapters]; hosts should keep the chapters they already have rather than delete them.
     */
    val unavailableSources: Set<String> = emptySet(),
)

/** Why chapter lists are being built: decides how fresh a cached provider list must be to be reused. */
enum class Refresh {
    /** Internal rebuilds (resolving pages after a restart, re-opening): lists up to `chapterCacheMinutes` old are reused. */
    ROUTINE,

    /** The host asked (library update, pull to refresh): only lists fetched moments ago are reused (duplicate calls). */
    EXPLICIT,
}

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
    /** Persisted last known chapter list per provider series (bounded). Defaults to [cache]. */
    private val listCache: CacheManager = cache,
    /** Runs optional provider work that must never delay its caller (late search results). */
    detachedDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Current rate-limit backlog of a provider in ms (its shared limiter's queue); replaceable in tests. */
    private val backlogProbe: (String) -> Long = { RateLimiter.backlogMs(it) },
) {
    private val reconciler = ChapterReconciler()
    private val completeness = CompletenessAnalyzer()
    val ranker = SourceRanker(registry.health)
    private val analyzer = ChapterQualityAnalyzer(ranker, registry.health)
    val reader = ReaderResolver(registry, ranker, analyzer, cache, probeClient, log)
    private val covers = CoverResolver(
        probe = { c -> ImageProbe.probe(probeClient, c.url, c.headers.toHeaders()) },
        known = ::knownImage,
        remember = ::rememberImage,
    )

    /** Measured cover sizes (width × height × bytes), kept 30 days so a cover is measured once, not on every opening. */
    private fun imageKey(url: String) = "cimg:" + Integer.toHexString(url.hashCode()) + ":" + url.length

    private fun knownImage(url: String): alexandria.core.quality.ImageInfo? =
        cache.get(imageKey(url), String.serializer(), 30 * CacheManager.DAY)?.split('x')?.takeIf { it.size >= 2 }?.let { p ->
            val w = p[0].toIntOrNull() ?: return null
            val h = p[1].toIntOrNull() ?: return null
            alexandria.core.quality.ImageInfo(w, h, "", p.getOrNull(2)?.toLongOrNull())
        }

    private fun rememberImage(url: String, info: alexandria.core.quality.ImageInfo) {
        if (info.width > 0 && info.height > 0) cache.put(imageKey(url), String.serializer(), "${info.width}x${info.height}x${info.bytes ?: ""}")
    }

    /** Pixel count of a cover measured earlier, or 0 when unknown (never makes a request). */
    private fun knownPixels(url: String?): Long = url?.let { knownImage(it)?.pixels } ?: 0L

    /** Canonical chapters per series kept in memory for page resolution (bounded: long sessions browse many series). */
    private val canonical = BoundedCache<String, Map<String, CanonicalChapter>>(CANONICAL_SERIES)
    private val chapterFlights = SingleFlight<String, List<SourceChapter>?>()

    // ---------------------------------------------------------------- search

    /** A computed first search page: its cards, every provider hit, and which providers were asked. */
    private class SearchPage(val at: Long, val cards: List<SeriesCard>, val hits: Map<String, List<SourceSeries>>, val searched: Set<String>, val more: Boolean)

    /** Recent search results; hosts re-run the same search when navigating back. Kept (stale) for outages. */
    private val searchCache = ConcurrentHashMap<String, SearchPage>()

    suspend fun search(query: String, settings: AggregatorSettings): List<SeriesCard> = searchPage(query, 1, settings).first

    /**
     * Search results page by page. Page 1 asks the most relevant healthy providers (and widens on its own when they
     * find little); a further page — the host asks for it when the user scrolls past the end — asks the remaining
     * providers. With *Search every source* on, page 1 asks all of them.
     */
    suspend fun searchPage(query: String, page: Int, settings: AggregatorSettings): Pair<List<SeriesCard>, Boolean> {
        val q = SearchRelevance.cleanQuery(query)
        val cacheKey = q.lowercase() + "|" + settings.searchFingerprint()
        val fresh = searchCache[cacheKey]?.takeIf { clock() - it.at < SEARCH_CACHE_MS }
        val first = fresh ?: searchFirstPage(q, settings, searchCache[cacheKey]).also {
            if (searchCache.size > 50) searchCache.clear()
            searchCache[cacheKey] = it
        }
        if (page <= 1) return first.cards to first.more
        if (!first.more) return emptyList<SeriesCard>() to false
        val rest = registry.enabled(settings, includeUnhealthy = false).filter { it.id !in first.searched }
        val hits = searchProviders(listOf(q), settings, rest, deadlineMs = SEARCH_DEADLINE_MS)
        hits.forEach { (sid, list) -> list.forEach { registry.noteImage(it.coverUrl, sid) } }
        val shown = first.cards.map { it.key.value }.toSet()
        val cards = providerCards(q, first.hits + hits, settings, emptySet()).map { it.card }.filter { it.key.value !in shown }
        searchCache[cacheKey] = SearchPage(first.at, first.cards, first.hits + hits, first.searched + rest.map { it.id }, more = false)
        return cards to false
    }

    private fun AggregatorSettings.searchFingerprint() =
        listOf(mangabaka, anilist, metadataPreference, enabledSources.sorted(), languages, highQualityCovers, deepDiscovery).hashCode()

    private class Ranked(val card: SeriesCard, val score: Double, val order: Int)

    /** Providers for the first search page, and the rest (searched on demand or when the first ones find little). */
    private fun searchTiers(settings: AggregatorSettings): Pair<List<SourceAdapter>, List<SourceAdapter>> {
        val all = registry.enabled(settings, includeUnhealthy = false)
        if (settings.deepDiscovery) return all to emptyList()
        // A provider whose request queue is already long (a library update is using it) would only time out here.
        val general = all.filter { it.role == ProviderRole.SERIES && !it.adult && it.standaloneResults && backlog(it.id) < BUSY_MS }
        val first = general.sortedWith(
            compareByDescending<SourceAdapter> { a ->
                val (n, hits) = discoveryStats[a.id] ?: listOf(0, 0)
                (hits + 1.0) / (n + 2.0) + 0.5 * registry.health.get(a.id).successRate + if (settings.preferredSource == a.id) 1.0 else 0.0
            }.thenBy { registry.all.indexOf(it) },
        ).take(INITIAL_SEARCH)
        return first to all.filter { it !in first }
    }

    private suspend fun searchFirstPage(query: String, settings: AggregatorSettings, stale: SearchPage?): SearchPage = coroutineScope {
        val metaD = async {
            if (settings.externalMetadata) metadata.search(query, settings).filter { it.kind == ContentKind.UNKNOWN || it.kind.isComic || it.kind == ContentKind.OTHER } else emptyList()
        }
        val (tier1, tier2) = searchTiers(settings)
        // Provider hits only pre-link entries; a slow provider must not hold up the results page.
        var hits = searchProviders(listOf(query), settings, tier1, deadlineMs = SEARCH_DEADLINE_MS)
        var searched = tier1.map { it.id }.toSet()
        val metas = metaD.await()
        // Little found among the main providers (an adult title, a doujinshi, a rare series): ask the others right away.
        // Busy providers (long rate-limit queue) are left for page 2, which the user asks for by scrolling.
        val widen = tier2.filter { backlog(it.id) < BUSY_MS }
        if (widen.isNotEmpty() && metas.size < 3 && relevantTitles(query, hits) < FEW_RESULTS) {
            hits = hits + searchProviders(listOf(query), settings, widen, deadlineMs = SEARCH_DEADLINE_MS)
            searched = searched + widen.map { it.id }
        }
        hits.forEach { (sid, list) -> list.forEach { registry.noteImage(it.coverUrl, sid) } }
        // Every provider failed (offline, outage): show the last results instead of nothing.
        if (hits.isEmpty() && metas.isEmpty() && stale != null) return@coroutineScope stale

        val claimed = mutableSetOf<Pair<String, String>>()
        val ranked = mutableListOf<Ranked>()
        for (m in metas) {
            val key = keyFor(m) ?: continue
            // Gallery sites hold one-shots, never the catalogue series itself: their hits stay standalone entries.
            val matched = hits.filterKeys { registry[it]?.role == ProviderRole.SERIES }
                .mapNotNull { (sid, list) -> identity.pick(m, list)?.let { sid to it } }
            matched.forEach { (sid, p) -> claimed += sid to p.first.id }
            rememberHints(key, m, matched.associate { (sid, p) -> sid to Binding(p.first, p.second.score, "search", hint = !p.first.detailed) })
            val card = SeriesCard(key, m.displayTitle, quickCover(m, matched.map { it.second.first }, settings))
            // Entries some provider already carries are readable right away: a small boost among equals.
            val score = SearchRelevance.score(query, m.allTitles()) + if (matched.isNotEmpty()) 0.03 else 0.0
            ranked += Ranked(card, score, ranked.size)
        }
        ranked += providerCards(query, hits, settings, claimed).map { Ranked(it.card, it.score, ranked.size + it.order) }
        val cards = ranked.sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.order }).map { it.card }.distinctBy { it.key.value }
        SearchPage(clock(), cards, hits, searched, more = searched.size < tier1.size + tier2.size)
    }

    private fun relevantTitles(query: String, hits: Map<String, List<SourceSeries>>): Int =
        hits.values.flatten().filter { SearchRelevance.score(query, it.allTitles()) >= RELEVANT }.map { TitleNormalizer.compact(it.title) }.toSet().size

    /**
     * Provider hits as results: grouped by [SearchClusterer] (same title or shared ids, nothing contradictory), shown
     * under the series they are already linked to when there is one. Only close matches are kept: provider search is
     * fuzzy ("don't tell mom" also returns "Don Dracula").
     */
    private fun providerCards(query: String, hits: Map<String, List<SourceSeries>>, settings: AggregatorSettings, claimed: Set<Pair<String, String>>): List<Ranked> {
        val pool = hits.flatMap { (sid, list) -> list.filter { (sid to it.id) !in claimed && it.kind != ContentKind.NOVEL } }
            .filter { SearchRelevance.score(query, it.allTitles()) >= SearchRelevance.PROVIDER_HIT_MIN }
        val clusters = SearchClusterer.cluster(
            pool,
            keyOf = ::indexedKey,
            rejected = { a, b -> (indexedKey(a) ?: SeriesKey.Provider(a.sourceId, a.id).value) in rejectedBy(b) },
            priority = { sid -> registry.all.indexOfFirst { it.id == sid }.let { if (it < 0) Int.MAX_VALUE else it } },
            canAnchor = { sid -> registry[sid]?.standaloneResults != false },
            roleOf = { sid -> registry[sid]?.role },
        )
        return clusters.mapIndexed { i, c ->
            val anchor = c.anchor
            val key = c.key?.let { SeriesKey.parse(it) } ?: SeriesKey.Provider(anchor.sourceId, anchor.id)
            if (c.key == null) {
                rememberHints(key, null, c.members.associate { it.sourceId to Binding(it, if (it == anchor) 1.0 else 0.8, "search title", hint = it != anchor) })
            }
            // The sharpest cover measured earlier (no request), else the preferred source's.
            val byPreference = c.members.sortedWith(compareByDescending<SourceSeries> { knownPixels(it.coverUrl) }.thenBy { settings.sourceRank(it.sourceId) })
            val cover = c.key?.let { bestKnownCover(it) }?.takeIf { settings.externalMetadata || METADATA_PROVIDERS.none { m -> m in it } }
                ?: byPreference.filter { registry[it.sourceId]?.standaloneResults != false }.firstNotNullOfOrNull { it.coverUrl }
            val title = c.key?.let { storedTitle(it) } ?: anchor.title
            Ranked(SeriesCard(key, title, cover, anchor), SearchRelevance.score(query, c.members.flatMap { it.allTitles() }), i)
        }
    }

    /**
     * Filtered browsing (genres, status, type, sort) and filtered search. With MangaBaka enabled it is served from
     * its catalogue; otherwise the filters are applied to provider results, as far as the providers report each field.
     * Returns the page's cards and whether another page exists.
     */
    suspend fun browse(query: String, filters: CatalogFilters, page: Int, settings: AggregatorSettings): Pair<List<SeriesCard>, Boolean> {
        val q = SearchRelevance.cleanQuery(query)
        val list = if (settings.mangabaka) metadata.browse(q, filters, page, settings, limit = BROWSE_PAGE_SIZE) else null
        if (list == null) {
            val (cards, more) = if (q.isNotBlank()) searchPage(q, page, settings) else popular(page, settings)
            return LocalFilters.apply(cards, filters) to more
        }
        val cards = list.mapNotNull { m -> keyFor(m)?.let { SeriesCard(it, m.displayTitle, quickCover(m, emptyList(), settings)) } }
        // MangaBaka drops a few unresolved placeholder records from full pages, so a page is "full" slightly below the limit.
        return cards to (list.size >= BROWSE_PAGE_SIZE - 5)
    }

    suspend fun popular(page: Int, settings: AggregatorSettings): Pair<List<SeriesCard>, Boolean> {
        if (settings.externalMetadata) {
            metadata.popular(page, settings)?.takeIf { it.isNotEmpty() }?.let { list ->
                return list.mapNotNull { m -> keyFor(m)?.let { SeriesCard(it, m.displayTitle, quickCover(m, emptyList(), settings)) } } to true
            }
        }
        // The first healthy browsing provider; the next one when it fails or lists nothing.
        for (adapter in registry.forBrowsing(settings).take(2)) {
            val list = guarded(adapter, settings) { adapter.popular(page) }.orEmpty()
            if (list.isEmpty()) continue
            list.forEach { registry.noteImage(it.coverUrl, it.sourceId) }
            return list.map { providerCard(it) } to true
        }
        return emptyList<SeriesCard>() to false
    }

    suspend fun latest(page: Int, settings: AggregatorSettings): Pair<List<SeriesCard>, Boolean> = coroutineScope {
        val lists = registry.forBrowsing(settings).take(2).map { a ->
            async { guarded(a, settings) { a.latest(page) }.orEmpty() }
        }.awaitAll()
        val merged = lists.flatten().distinctBy { TitleNormalizer.compact(it.title) }
        merged.forEach { registry.noteImage(it.coverUrl, it.sourceId) }
        merged.map { providerCard(it) }.distinctBy { it.key.value } to merged.isNotEmpty()
    }

    /** A provider listing entry, shown as the series it is already linked to when there is one. */
    private fun providerCard(s: SourceSeries): SeriesCard {
        val known = indexedKey(s)
        val key = known?.let { SeriesKey.parse(it) } ?: SeriesKey.Provider(s.sourceId, s.id)
        return SeriesCard(key, known?.let { storedTitle(it) } ?: s.title, s.coverUrl, s)
    }

    // ---------------------------------------------------------------- details + chapters

    /** Updates in progress: hosts often ask for the same series twice at once (details, then chapters). */
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<SeriesUpdate>>()

    /**
     * Refreshes a series. [titleHint] is the title the host shows for it (used only when nothing else is known about
     * the series, e.g. a library entry whose stored links were lost). [recovered] are provider links read back from
     * the host's own chapter records (sourceId -> provider series id): links this extension confirmed earlier.
     */
    suspend fun update(
        key: SeriesKey,
        knownState: SeriesState?,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        settings: AggregatorSettings,
        intent: Refresh = Refresh.EXPLICIT,
        titleHint: String? = null,
        recovered: Map<String, String> = emptyMap(),
    ): SeriesUpdate {
        val flightKey = "${key.value}|$fetchDetails|$fetchChapters"
        // Join an identical request, or one doing strictly more (details + chapters covers chapters only).
        val covering = listOf(flightKey, "${key.value}|true|$fetchChapters", "${key.value}|$fetchDetails|true", "${key.value}|true|true")
        covering.firstNotNullOfOrNull { inflight[it] }?.let { running ->
            try {
                return running.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // we were cancelled ourselves
            } catch (e: Exception) {
                // The other caller failed; try once on our own below.
            }
        }
        val run: suspend () -> SeriesUpdate = { updateNow(key, knownState, fetchDetails, fetchChapters, settings, intent, titleHint, recovered) }
        val mine = CompletableDeferred<SeriesUpdate>()
        inflight.putIfAbsent(flightKey, mine)?.let { running ->
            return try { running.await() } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                run()
            }
        }
        return try {
            run().also { mine.complete(it) }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inflight.remove(flightKey, mine)
        }
    }

    private suspend fun updateNow(
        key: SeriesKey,
        knownState: SeriesState?,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
        settings: AggregatorSettings,
        intent: Refresh,
        titleHint: String?,
        recovered: Map<String, String>,
    ): SeriesUpdate {
        var state = recoverBindings(loadState(key, knownState), recovered, titleHint)
        // A library update (chapters only) runs for every series at once: it shares a discovery budget and widens rarely.
        val background = !fetchDetails
        val gallery = isGallery(key)

        // 1. Details: the entry's own provider, plus the external record only when the user enabled one.
        if (fetchDetails || merged(state) == null) {
            val anchor = (key as? SeriesKey.Provider)?.let { providerAnchor(it, state, settings, refresh = fetchDetails) }
            if (anchor != null) state = state.copy(bindings = state.bindings + (anchor.sourceId to Binding(anchor, 1.0, "entry source")))
            if (settings.externalMetadata && !gallery) {
                metadata.forKey(key, settings, anchor)?.takeIf { it.isExternal }?.let { state = state.copy(metadata = it) }
            } else if (fetchDetails && key !is SeriesKey.Provider) {
                // A catalogue-keyed entry (mb:/al:) in provider-only mode: its primary link supplies fresh details.
                state = refreshPrimaryDetails(state, settings)
            }
        }
        var reference = merged(state)?.metadata ?: titleHint?.takeIf { it.isNotBlank() }?.let { SeriesMetadata(TITLE_ONLY, title = it) }

        // 2. Link providers: the most promising ones first (see resolveBindings), unless asked to be thorough.
        if (reference != null) state = resolveBindings(key, reference, state, settings, deep = settings.deepDiscovery, background = background)
        saveState(state)
        reference = merged(state)?.metadata ?: reference

        // 3 + 4. Chapters and cover are independent: fetch them concurrently.
        val linkedState = state
        var (built, cover) = coroutineScope {
            val chaptersD = async { if (fetchChapters) buildChapters(key, linkedState, settings, intent, background) else null }
            val coverD = async { if (fetchDetails) resolveCover(key, linkedState, settings) else null }
            chaptersD.await() to coverD.await()
        }

        // 5. Progressive discovery: search the remaining providers only when the linked ones leave chapters missing,
        //    fail, or are too few to fall back on. Only for host-requested updates: an internal rebuild (resolving the
        //    pages of a chapter being opened) must not wait for searches. Library updates do it at most twice a day.
        // 5a. Over the cap (links made before it existed, or manual ones): let go of sources that add no chapter of
        //     their own. Sources that are the only ones with some chapter always stay: the cap never costs chapters.
        if (built != null && !gallery) {
            pruneRedundant(key, state, built)?.let { pruned ->
                state = pruned
                saveState(state)
            }
        }

        // 5b. A linked source failed or chapters are missing: promote a spare match before searching anything.
        val enabledIds = registry.enabled(settings).map { it.id }.toSet()
        val spares = state.spares.filterKeys { it in enabledIds && !registry.health.isCircuitOpen(it) && it !in manualUnlinks(key.value, settings) }
        val linkedNow = state.bindings.count { (sid, b) -> !b.unverified && sid in enabledIds }
        if (intent == Refresh.EXPLICIT && built != null && reference != null && !gallery && spares.isNotEmpty() &&
            (built.unavailable.isNotEmpty() || needsMoreCoverage(state, built, reference, settings))
        ) {
            // At the cap, a failing source makes room: it swaps places with the spare (never the entry source or a manual link).
            val anchorSid = (key as? SeriesKey.Provider)?.sourceId
            val demotable = built.unavailable.filter { sid -> sid != anchorSid && state.bindings[sid]?.manual != true }
            val slots = (MAX_LINKED - linkedNow).coerceAtLeast(0) + demotable.size
            val promote = spares.entries.sortedByDescending { it.value.confidence }.take(slots)
            val demote = demotable.take((promote.size - (MAX_LINKED - linkedNow).coerceAtLeast(0)).coerceAtLeast(0)).toSet()
            if (promote.isNotEmpty()) {
                state = state.copy(
                    bindings = state.bindings - demote + promote.associate { it.key to it.value },
                    spares = state.spares - promote.map { it.key }.toSet() + demote.associateWith { state.bindings.getValue(it) },
                )
                saveState(state)
                log("coverage for ${key.value}: promoted spare ${promote.joinToString { it.key }}")
                built = buildChapters(key, state, settings, Refresh.ROUTINE, background)
            }
        }

        val canWiden = !background || clock() - state.wideAt > BACKGROUND_WIDEN_MS
        if (intent == Refresh.EXPLICIT && built != null && reference != null && !gallery && !settings.deepDiscovery && canWiden &&
            needsMoreCoverage(state, built, reference, settings)
        ) {
            val wider = resolveBindings(key, reference, state, settings, deep = true, background = background)
                .let { if (background) it.copy(wideAt = clock()) else it }
            if (wider != state) {
                val added = wider.bindings.keys - state.bindings.keys
                state = wider
                saveState(state)
                if (added.isNotEmpty()) {
                    log("coverage for ${key.value}: linked ${added.joinToString()} after a wider search")
                    // Lists fetched a moment ago are reused; only the newly linked providers are asked.
                    built = buildChapters(key, state, settings, Refresh.ROUTINE, background)
                }
            }
        }
        built?.let { canonical[key.value] = it.canonical.associateBy { c -> c.key } }
        val details = buildDetails(key, state, cover, built?.report, settings, titleHint)
        return SeriesUpdate(details, built?.entries, state, built?.report, built?.unavailable.orEmpty())
    }

    /**
     * Bindings to drop so the series has at most [MAX_LINKED] linked sources: never the entry source or manual links,
     * never a source that is the only one listing some chapter; among the rest, the ones covering fewest chapters go
     * first. Dropped matches become spares. Null when nothing changes.
     */
    private fun pruneRedundant(key: SeriesKey, state: SeriesState, built: Built): SeriesState? {
        val anchor = (key as? SeriesKey.Provider)?.sourceId
        val active = state.bindings.filterValues { !it.unverified }
        if (active.size <= MAX_LINKED) return null
        val coverage = built.canonical.flatMap { it.sources.distinct() }.groupingBy { it }.eachCount()
        val unique = built.canonical.filter { it.sources.distinct().size == 1 }.map { it.sources.first() }.toSet()
        val droppable = active.filter { (sid, b) -> sid != anchor && !b.manual && sid !in unique && sid !in built.unavailable }
            .keys.sortedBy { coverage[it] ?: 0 }
        val drop = droppable.take(active.size - MAX_LINKED).toSet()
        if (drop.isEmpty()) return null
        log("${key.value}: ${drop.joinToString()} add no chapters of their own; kept as spares (cap $MAX_LINKED)")
        return state.copy(
            bindings = state.bindings - drop,
            spares = (state.spares + drop.associateWith { state.bindings.getValue(it) }).entries.take(MAX_SPARES).associate { it.key to it.value },
        )
    }

    /** Links read back from the host's chapter records: confirmed earlier, so they are trusted without a search. */
    private fun recoverBindings(state: SeriesState, recovered: Map<String, String>, titleHint: String?): SeriesState {
        val add = recovered.filter { (sid, id) ->
            sid !in state.bindings && registry[sid] != null && state.rejected[sid] != id && SeriesKey.parse("p:$sid:$id") != null
        }
        if (add.isEmpty()) return state
        val title = merged(state)?.metadata?.title ?: titleHint ?: ""
        return state.copy(bindings = state.bindings + add.mapValues { (sid, id) -> Binding(SourceSeries(sid, id, title), 0.95, "previous chapter list") })
    }

    /**
     * Combined details of a series: the external record (when one was fetched, now or by an earlier version) on top
     * of every linked provider's own information, in precedence order (see [orderedBindings]).
     */
    private fun merged(state: SeriesState): MergedDetails? {
        val base = state.metadata?.takeIf { it.isExternal }
        // A provider record stored as the series' metadata by 1.6.x: lowest precedence, so nothing it knew is lost.
        val legacy = state.metadata?.takeIf { !it.isExternal }?.let { m ->
            SourceSeries(m.provider, "", m.title, m.altTitles, null, m.ids, m.kind, m.status, m.year, m.originalLanguage, m.description, m.authors, m.genres, m.webUrl, detailed = true)
        }
        val providers = orderedBindings(state).filter { !it.unverified }.map { it.series } + listOfNotNull(legacy)
        return DetailsMerger.merge(base, providers)
    }

    /** Entry source and manual links first, then confidence, then the fixed provider order (never the user's order). */
    private fun orderedBindings(state: SeriesState): List<Binding> {
        val anchorSource = (SeriesKey.parse(state.key) as? SeriesKey.Provider)?.sourceId
        return state.bindings.values.sortedWith(
            compareByDescending<Binding> { it.series.sourceId == anchorSource }
                .thenByDescending { it.manual }
                .thenByDescending { it.confidence }
                .thenBy { b -> registry.all.indexOfFirst { it.id == b.series.sourceId }.let { if (it < 0) Int.MAX_VALUE else it } },
        )
    }

    private suspend fun refreshPrimaryDetails(state: SeriesState, settings: AggregatorSettings): SeriesState {
        val enabled = registry.enabled(settings).map { it.id }.toSet()
        val primary = orderedBindings(state).firstOrNull { it.series.sourceId in enabled && !it.unverified } ?: return state
        val adapter = registry[primary.series.sourceId] ?: return state
        val fresh = cachedDetails(adapter, primary.series, settings) ?: return state
        return state.copy(bindings = state.bindings + (adapter.id to primary.copy(series = fresh)))
    }

    private data class Built(
        val entries: List<ChapterEntry>,
        val report: CompletenessReport?,
        val canonical: List<CanonicalChapter>,
        /** Linked providers with no chapter list at all (failed, nothing cached). */
        val unavailable: Set<String>,
        /** Linked providers that contributed chapters. */
        val contributing: Set<String>,
    )

    /** Linked series providers that could still be searched for this series (not bound, not recently missed). */
    private fun discoverable(state: SeriesState, meta: SeriesMetadata, settings: AggregatorSettings): List<SourceAdapter> {
        val now = clock()
        val adultSeries = meta.looksAdult || state.bindings.keys.any { registry[it]?.adult == true }
        val unlinked = manualUnlinks(state.key, settings)
        return registry.forLinking(settings, adultSeries).filter { a ->
            a.id !in state.bindings && a.id !in state.spares && a.id !in unlinked && (state.misses[a.id]?.let { now - it > MISS_TTL } ?: true)
        }
    }

    /** Providers the user unlinked from this series ("<series> = <provider>:-"); applied while the setting says so. */
    private fun manualUnlinks(seriesKey: String, settings: AggregatorSettings): Set<String> =
        settings.manualLinks[seriesKey].orEmpty().filter { it.substringAfter(':').let { id -> id == "-" || id.isBlank() } }.map { it.substringBefore(':') }.toSet()

    private fun needsMoreCoverage(state: SeriesState, built: Built, meta: SeriesMetadata, settings: AggregatorSettings): Boolean {
        if (discoverable(state, meta, settings).isEmpty()) return false
        if (built.contributing.size < TARGET_LINKED) return true // nothing, or nothing to fall back on
        if (built.unavailable.isNotEmpty()) return true // a linked source is failing
        val main = built.canonical.filter { it.identity.kind == ChapterKind.MAIN }.mapNotNull { it.identity.number?.whole?.toDouble() }
        val total = meta.totalChapters?.trim()?.toDoubleOrNull()
        if (total != null && total > 0 && (main.maxOrNull() ?: 0.0) < total) return true // the run continues past what we have
        return built.report?.gaps?.any { it.type == alexandria.core.completeness.GapType.POSSIBLE_MISSING } == true
    }

    private suspend fun buildChapters(key: SeriesKey, state: SeriesState, settings: AggregatorSettings, intent: Refresh, background: Boolean = false): Built = coroutineScope {
        val enabled = registry.enabled(settings).map { it.id }.toSet()
        val bound = state.bindings.filterKeys { it in enabled }.filterValues { !it.unverified }
        val gate = Semaphore(settings.maxConcurrentSearches.coerceIn(1, 6))
        val leaders = if (background && intent == Refresh.EXPLICIT) leadingSources(bound) else bound.keys
        fun freshFor(sid: String) = when {
            intent == Refresh.ROUTINE -> settings.chapterCacheMinutes * CacheManager.MINUTE
            // Library updates ask the providers furthest ahead every time and the others a few times a day: a new
            // chapter appears on the leaders first, and the rest only back them up (gaps, fallback).
            sid !in leaders -> SECONDARY_REFRESH_MS
            else -> EXPLICIT_REUSE_MS
        }
        val unavailable = ConcurrentHashMap.newKeySet<String>()
        val lists = bound.map { (sid, b) ->
            async {
                val adapter = registry[sid] ?: return@async sid to emptyList<SourceChapter>()
                val cacheKey = listKey(sid, b.series.id)
                // Fresh enough for this kind of request: no network call at all.
                storedList(cacheKey, freshFor(sid))?.let { return@async sid to it }
                // A library update does not queue behind a provider that is already saturated: its last list serves
                // now, and a later update refreshes it.
                if (background && backlog(sid) >= BUSY_MS) storedList(cacheKey, Long.MAX_VALUE)?.let { return@async sid to it }
                // A provider in cool-down would only time out again: use its last known list straight away.
                val fresh = if (registry.health.isCircuitOpen(sid)) null else chapterFlights.run(cacheKey) {
                    gate.withPermit { guarded(adapter, settings) { adapter.chapters(b.series, settings.languages) } }
                }
                if (!fresh.isNullOrEmpty()) {
                    listCache.put(cacheKey, StoredChapterList.serializer(), StoredChapterList.of(sid, b.series.id, fresh))
                    sid to fresh
                } else {
                    // Failed (or suddenly empty): keep the last known chapters so the list does not shrink.
                    val last = storedList(cacheKey, Long.MAX_VALUE)
                    if (last == null) unavailable += sid
                    sid to last.orEmpty()
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
        Built(entries, report, chapters, unavailable.toSet(), lists.keys)
    }

    private fun listKey(sourceId: String, seriesId: String) = "chl:$sourceId:$seriesId"

    /**
     * Linked providers refreshed on every library update: the [LEADERS] whose last known lists reach the highest
     * chapter, plus any provider without a stored list.
     */
    private fun leadingSources(bound: Map<String, Binding>): Set<String> {
        val latest = bound.mapValues { (sid, b) ->
            storedList(listKey(sid, b.series.id), Long.MAX_VALUE)?.mapNotNull { c ->
                ChapterLabelParser.parse(c.label, c.number, c.volume).identity.takeIf { it.kind == ChapterKind.MAIN }?.number?.toFloat()
            }?.maxOrNull()
        }
        val missing = latest.filterValues { it == null }.keys
        val top = latest.filterValues { it != null }.entries.sortedByDescending { it.value!! }.take(LEADERS).map { it.key }
        return missing + top
    }

    private fun storedList(cacheKey: String, maxAgeMs: Long): List<SourceChapter>? =
        listCache.get(cacheKey, StoredChapterList.serializer(), maxAgeMs)?.toChapters()

    /**
     * Canonical chapters rebuilt without network from the persisted provider links and last known lists, for page
     * resolution after an app restart. Null when nothing usable is stored.
     */
    private fun offlineCanonical(key: SeriesKey, settings: AggregatorSettings): Map<String, CanonicalChapter>? {
        canonical[key.value]?.let { return it }
        val state = cache.get("state:${key.value}", SeriesState.serializer(), Long.MAX_VALUE) ?: return null
        val enabled = registry.enabled(settings).map { it.id }.toSet()
        val lists = state.bindings.filterKeys { it in enabled }
            .mapNotNull { (sid, b) -> storedList(listKey(sid, b.series.id), Long.MAX_VALUE)?.let { sid to it } }
            .toMap()
        if (lists.isEmpty()) return null
        return reconciler.reconcile(lists, settings).chapters.associateBy { it.key }.also { canonical[key.value] = it }
    }

    private fun primarySource(chapters: List<CanonicalChapter>, settings: AggregatorSettings): String? {
        settings.preferredSource?.let { p -> if (chapters.any { c -> c.sources.contains(p) }) return p }
        val coverage = chapters.flatMap { it.sources }.groupingBy { it }.eachCount()
        return coverage.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { settings.sourceRank(it.key) }).firstOrNull()?.key
    }

    // ---------------------------------------------------------------- pages

    /**
     * Resolves a chapter's pages. Releases come from the full canonical record (in memory, or rebuilt offline from
     * the persisted lists) merged with the host's compact memo, which is only a bootstrap: it may be truncated and
     * lacks titles. Only when neither is available are the provider lists fetched again.
     */
    suspend fun pages(chapterUrl: String, memoReleases: List<Release>?, settings: AggregatorSettings): PageResolution {
        val (key, chapterKey) = ChapterUrl.parse(chapterUrl) ?: throw IllegalArgumentException("Not a Library of Alexandria chapter: $chapterUrl")
        val memo = memoReleases?.takeIf { it.isNotEmpty() }
        var refreshed = false
        suspend fun rebuild(intent: Refresh): List<Release>? {
            refreshed = true
            update(key, null, fetchDetails = false, fetchChapters = true, settings, intent)
            return canonical[key.value]?.get(chapterKey)?.releases
        }
        val known = offlineCanonical(key, settings)?.get(chapterKey)?.releases
        val releases = mergeReleases(known, memo).ifEmpty { null }
            ?: rebuild(Refresh.ROUTINE) // nothing stored (memo-less host after a restart): rebuild from the provider links
            ?: throw IllegalStateException("Chapter is no longer listed by any enabled source. Refresh the series.")
        val ref = ChapterUrl.build(key, chapterKey)
        val resolution = try {
            reader.resolve(ref, releases, settings)
        } catch (e: NoReadableReleaseException) {
            // Stored releases may be stale (chapter re-uploaded, provider re-linked): refresh the lists once.
            if (refreshed) throw e
            val fresh = rebuild(Refresh.EXPLICIT) ?: throw e
            reader.resolve(ref, mergeReleases(fresh, memo), settings)
        }
        val pages = resolution.pages.map { p -> p.copy(imageUrl = FallbackInterceptor.tagPage(p.imageUrl, ref, p.index, resolution.chosen.sourceId)) }
        return resolution.copy(pages = pages)
    }

    /**
     * Resolves a lazy page ([alexandria.core.model.ReaderPage.lazy]) handed out by [pages] into its image URL,
     * tagged for page fallback like any other page.
     */
    suspend fun resolveImage(pageRef: String, settings: AggregatorSettings): String {
        val token = FallbackInterceptor.pageToken(pageRef) ?: throw IllegalArgumentException("Not a Library of Alexandria page: $pageRef")
        val adapter = token.sourceId?.let { registry[it] } ?: throw IllegalStateException("Unknown provider for page $pageRef")
        val page = registry.health.call(adapter.id, settings.requestTimeoutSeconds * 1000L) { adapter.resolveImage(FallbackInterceptor.stripTag(pageRef)) }
        return FallbackInterceptor.tagPage(page.imageUrl, token.chapterRef, token.index, adapter.id)
    }

    // ---------------------------------------------------------------- binding

    /**
     * Links provider series to [key]. Manual links win; title-only links from search are verified with details and
     * must be corroborated (author, year, ids or a second title) before they are trusted. Unlinked providers are
     * searched progressively: with [deep] false only when fewer than [TARGET_LINKED] providers are linked, and then
     * only the [INITIAL_DISCOVERY] most promising ones (by past hit rate, health, content-type specialty and user
     * order). [deep] searches every relevant provider not missed in the last week. [background] searches (library
     * updates) draw on a shared per-provider budget and are skipped, not queued, when it is spent.
     */
    private suspend fun resolveBindings(
        key: SeriesKey,
        meta: SeriesMetadata,
        state0: SeriesState,
        settings: AggregatorSettings,
        deep: Boolean,
        background: Boolean = false,
    ): SeriesState {
        var state = state0
        val configured = settings.manualLinks[key.value].orEmpty()
        // Manual links from settings win over automatic matching; a manual link removed from settings is dropped.
        state = state.copy(bindings = state.bindings.filterValues { b -> !b.manual || configured.any { it == "${b.series.sourceId}:${b.series.id}" } })
        configured.forEach { target ->
            val sid = target.substringBefore(':')
            val id = target.substringAfter(':')
            val adapter = registry[sid] ?: return@forEach
            state = if (id == "-" || id.isBlank()) {
                state.copy(bindings = state.bindings - sid)
            } else {
                val existing = state.bindings[sid]
                if (existing?.series?.id == id && existing.manual) state else {
                    val series = cachedDetails(adapter, SourceSeries(sid, id, meta.title), settings) ?: SourceSeries(sid, id, meta.title)
                    state.copy(
                        bindings = state.bindings + (sid to Binding(series, 1.0, "manual link", manual = true)),
                        rejected = state.rejected - sid,
                    )
                }
            }
        }

        // Links pre-made from search results are title-only. Verify them with provider details (year, authors,
        // tracker ids, alt titles) before trusting them, exactly like a normal match.
        // Gallery copies were grouped by exact title in search; galleries carry no ids or years to verify against.
        val unverified = state.bindings.filter { (sid, b) -> b.unverified && registry[sid] != null && registry[sid]?.role != ProviderRole.GALLERY }
        val verified = coroutineScope {
            unverified.map { (sid, b) -> async { Triple(sid, b, cachedDetails(registry[sid]!!, b.series, settings)) } }.awaitAll()
        }
        val reference = merged(state)?.metadata ?: meta
        for ((sid, b, detailed) in verified) {
            if (detailed == null) continue // stays a hint; checked again next time
            val verdict = identity.match(reference, detailed)
            state = if (verdict.decision == MatchDecision.CONFIRMED) {
                state.copy(bindings = state.bindings + (sid to b.copy(series = detailed, confidence = verdict.score, reason = verdict.reasons.joinToString("; "), hint = false)))
            } else {
                log("not linking $sid:${b.series.id} to ${key.value}: ${verdict.reasons.joinToString()}")
                rememberRejection(key, detailed)
                state.copy(bindings = state.bindings - sid, rejected = state.rejected + (sid to b.series.id))
            }
        }
        val now = clock()
        if (isGallery(key)) return state // other sites' copies were linked from search; series providers never carry it
        // Re-search providers that had no match weekly, not on every library refresh.
        val candidates = discoverable(state, meta, settings)
        val linked = state.bindings.count { (sid, b) -> !b.unverified && registry[sid] != null && (settings.enabledSources.isEmpty() || sid in settings.enabledSources) }
        val free = MAX_LINKED - linked
        var toFind = when {
            free <= 0 -> emptyList() // the cap: enough sources to read from and fall back on
            // "Search every source" asks everyone; a wider search for missing chapters asks the most promising few.
            deep && settings.deepDiscovery -> candidates
            deep -> discoveryOrder(candidates, meta, settings).take((free * 2).coerceAtLeast(INITIAL_DISCOVERY))
            linked >= TARGET_LINKED -> emptyList()
            else -> discoveryOrder(candidates, meta, settings).take(INITIAL_DISCOVERY)
        }
        if (background) toFind = toFind.filter { backlog(it.id) < BUSY_MS && discoveryToken(it.id) }
        if (toFind.isEmpty()) return state

        // Corroborating evidence lives in the merged details of what is already linked (authors, year, alt titles).
        val ref = merged(state)?.metadata?.let { m -> m.copy(altTitles = (m.altTitles + meta.allTitles()).distinct()) } ?: meta
        val queries = ref.searchQueries().ifEmpty { meta.searchQueries() }
        // Stop trying further title variants on a provider as soon as one query yields a confident match.
        val results = searchProviders(queries, settings, toFind, stopWhen = { identity.pick(ref, it) != null })
        val found = ConcurrentHashMap<String, Binding>()
        val missed = ConcurrentHashMap<String, Long>()
        coroutineScope { toFind.map { adapter -> async {
            val candidates = results[adapter.id].orEmpty().filter { c -> state.rejected[adapter.id] != c.id && key.value !in rejectedBy(c) }
            var pick = identity.pick(ref, candidates)
            if (pick == null || pick.first.ids.isEmpty) {
                // Progressive evidence: fetch details (alt titles, authors, year, tracker ids) for the most plausible hits.
                val detailed = identity.needsDetails(ref, candidates).mapNotNull { c -> cachedDetails(adapter, c, settings) }
                if (detailed.isNotEmpty()) {
                    val merged = candidates.map { c -> detailed.firstOrNull { it.id == c.id } ?: c }
                    // Details are authoritative: if they disprove the title-only match, there is no match.
                    pick = identity.pick(ref, merged)
                }
            }
            if (pick != null) {
                found[adapter.id] = Binding(pick.first, pick.second.score, pick.second.reasons.joinToString("; "))
            } else if (results.containsKey(adapter.id)) {
                missed[adapter.id] = now
            }
        } }.awaitAll() }
        // Only a title is known (a library entry whose links were lost): a title cannot confirm a match by itself, but
        // two providers whose own details corroborate each other (same exact title plus author, year or ids) can.
        if (found.isEmpty() && ref.provider == TITLE_ONLY && state.bindings.values.none { !it.unverified }) {
            crossConfirmed(ref, toFind, results, settings).forEach { (sid, b) -> found[sid] = b; missed.remove(sid) }
        }
        recordDiscovery(toFind.filter { results.containsKey(it.id) }.map { it.id }, found.keys)
        // More matches than free slots: the best (confidence, then discovery order) are linked, the rest kept as spares.
        val order = discoveryOrder(toFind, meta, settings).map { it.id }
        val ranked = found.entries.sortedWith(compareByDescending<Map.Entry<String, Binding>> { it.value.confidence }.thenBy { order.indexOf(it.key) })
        val link = ranked.take(free.coerceAtLeast(0)).associate { it.key to it.value }
        val spare = ranked.drop(free.coerceAtLeast(0)).associate { it.key to it.value }
        return state.copy(
            bindings = state.bindings + link,
            misses = state.misses + missed,
            spares = (state.spares - link.keys + spare).entries.take(MAX_SPARES).associate { it.key to it.value },
        )
    }

    private suspend fun crossConfirmed(
        ref: SeriesMetadata,
        searched: List<SourceAdapter>,
        results: Map<String, List<SourceSeries>>,
        settings: AggregatorSettings,
    ): Map<String, Binding> {
        val title = TitleNormalizer.compact(ref.title)
        val quals = TitleNormalizer.qualifiers(ref.title)
        val exact = searched.mapNotNull { a ->
            val same = results[a.id].orEmpty().filter { TitleNormalizer.compact(it.title) == title && TitleNormalizer.qualifiers(it.title) == quals }
            same.singleOrNull()?.let { c -> cachedDetails(a, c, settings) ?: c }
        }
        for (i in exact.indices) for (j in i + 1 until exact.size) {
            val verdict = identity.match(exact[i].asMetadata(), exact[j])
            if (verdict.decision == MatchDecision.CONFIRMED) {
                val reason = "same title on two sources that agree (" + verdict.reasons.drop(1).joinToString() + ")"
                return mapOf(exact[i].sourceId to Binding(exact[i], verdict.score, reason), exact[j].sourceId to Binding(exact[j], verdict.score, reason))
            }
        }
        return emptyMap()
    }

    private fun backlog(sourceId: String): Long = backlogProbe(sourceId)

    /** Shared per-provider budget for searches made by library updates; never waits. */
    private val discoveryBudget = ConcurrentHashMap<String, RateLimiter>()

    private suspend fun discoveryToken(sourceId: String): Boolean =
        discoveryBudget.getOrPut(sourceId) { RateLimiter(BACKGROUND_SEARCHES_PER_MINUTE, CacheManager.MINUTE, key = "discovery-$sourceId") }.acquire(0)

    // ---------------------------------------------------------------- discovery statistics

    /** Per provider: [0] = link searches, [1] = series found. Persisted, so the order improves across sessions. */
    private val discoveryStats: MutableMap<String, List<Int>> by lazy {
        ConcurrentHashMap(cache.get(DISCOVERY_KEY, DISCOVERY_SERIALIZER, Long.MAX_VALUE).orEmpty())
    }

    private fun recordDiscovery(searched: List<String>, found: Set<String>) {
        if (searched.isEmpty()) return
        searched.forEach { sid ->
            val (n, hits) = discoveryStats[sid] ?: listOf(0, 0)
            // Halve old counts now and then so a provider whose catalogue changed is re-evaluated.
            val (n2, h2) = if (n >= 200) n / 2 to hits / 2 else n to hits
            discoveryStats[sid] = listOf(n2 + 1, h2 + if (sid in found) 1 else 0)
        }
        cache.put(DISCOVERY_KEY, DISCOVERY_SERIALIZER, discoveryStats.toMap())
    }

    /** Most promising providers first: past hit rate, health, content-type specialty, user order. */
    private fun discoveryOrder(candidates: List<SourceAdapter>, meta: SeriesMetadata, settings: AggregatorSettings): List<SourceAdapter> =
        candidates.sortedByDescending { a ->
            val (n, hits) = discoveryStats[a.id] ?: listOf(0, 0)
            var score = (hits + 1.0) / (n + 2.0) // Laplace: new providers start at 0.5
            score += 0.3 * registry.health.get(a.id).successRate
            a.specialties?.let { sp -> if (meta.kind != ContentKind.UNKNOWN) score += if (meta.kind in sp) 0.2 else -0.25 }
            if (settings.preferredSource == a.id) score += 1.0
            score -= 0.02 * settings.sourceRank(a.id).coerceAtMost(10)
            score
        }

    private suspend fun searchProviders(
        queries: List<String>,
        settings: AggregatorSettings,
        adapters: List<SourceAdapter> = registry.enabled(settings, includeUnhealthy = false),
        deadlineMs: Long? = null,
        stopWhen: (List<SourceSeries>) -> Boolean = { false },
    ): Map<String, List<SourceSeries>> = coroutineScope {
        // A user search asks every provider once, each a different host with its own rate limiter: no shared gate.
        // Linking searches send several title variants per provider and stay bounded.
        val gate = Semaphore(if (deadlineMs != null) adapters.size.coerceAtLeast(1) else settings.maxConcurrentSearches.coerceIn(1, 8))
        adapters.map { a ->
            async {
                val collected = java.util.Collections.synchronizedList(mutableListOf<SourceSeries>())
                var anySuccess = false
                val work: suspend () -> Unit = {
                    for (q in queries.filter { it.isNotBlank() }) {
                        val r = gate.withPermit { cachedSearch(a, q, settings) } ?: continue
                        anySuccess = true
                        collected += r
                        if (stopWhen(collected.toList()) || r.any { TitleNormalizer.compact(it.title) == TitleNormalizer.compact(q) }) break
                    }
                }
                if (deadlineMs != null) {
                    // Detached, so a provider stuck in a blocking read cannot hold the results page past the deadline.
                    // A late provider is left to finish (its own timeout still applies): that warms its caches and
                    // health stats, so the next search includes it.
                    val job = detached.async { work() }
                    withTimeoutOrNull(deadlineMs) { job.await() }
                } else {
                    work()
                }
                if (anySuccess) a.id to collected.toList().distinctBy { it.id } else null
            }
        }.awaitAll().filterNotNull().toMap()
    }

    /** Scope for provider calls whose results are optional and must never delay the caller. */
    private val detached = CoroutineScope(SupervisorJob() + detachedDispatcher)

    /** Recent provider search results and details, shared by user searches, linking and library updates. */
    private val searchHits = BoundedCache<String, List<SourceSeries>>(400, ttlMillis = PROVIDER_RESULT_TTL, clock = clock)
    private val searchFlights = SingleFlight<String, List<SourceSeries>?>()
    private val detailsHits = BoundedCache<String, SourceSeries>(400, ttlMillis = PROVIDER_RESULT_TTL, clock = clock)
    private val detailsFlights = SingleFlight<String, SourceSeries?>()

    /** One provider search, answered from recent results or merged with an identical one in flight. */
    private suspend fun cachedSearch(adapter: SourceAdapter, query: String, settings: AggregatorSettings): List<SourceSeries>? {
        val k = adapter.id + "|" + query.trim().lowercase()
        searchHits[k]?.let { return it }
        return searchFlights.run(k) { guarded(adapter, settings) { adapter.search(query) } }?.also { searchHits[k] = it }
    }

    /** Provider details for one series, answered from recent results or merged with an identical request in flight. */
    private suspend fun cachedDetails(adapter: SourceAdapter, series: SourceSeries, settings: AggregatorSettings): SourceSeries? {
        val k = adapter.id + "|" + series.id
        detailsHits[k]?.let { return it }
        return detailsFlights.run(k) { guarded(adapter, settings) { adapter.details(series.copy(detailed = false)) } }?.also { detailsHits[k] = it }
    }

    /**
     * The entry's own provider series. [refresh] asks the provider again (the user opened or refreshed the series);
     * otherwise the stored copy is used. A disabled or removed provider keeps its stored copy (the entry stays usable
     * through its other links).
     */
    private suspend fun providerAnchor(key: SeriesKey.Provider, state: SeriesState, settings: AggregatorSettings, refresh: Boolean): SourceSeries? {
        val stored = state.bindings[key.sourceId]?.series
        val adapter = registry[key.sourceId]?.takeIf { a -> registry.enabled(settings).any { it.id == a.id } } ?: return stored
        if (!refresh && stored != null && stored.detailed) return stored
        val base = stored ?: SourceSeries(key.sourceId, key.seriesId, state.metadata?.title ?: key.seriesId)
        return cachedDetails(adapter, base, settings) ?: base
    }

    // ---------------------------------------------------------------- covers

    private suspend fun resolveCover(key: SeriesKey, state: SeriesState, settings: AggregatorSettings): List<ScoredCover> {
        // v3: entries cached by 1.6.x may hold MangaBaka/AniList artwork, which provider-only mode must not load.
        val cacheKey = "cover:v3:${key.value}:${settings.highQualityCovers}:${settings.coverProvider}:${settings.preferOriginalArtwork}:${settings.externalMetadata}"
        if (settings.cacheCovers) {
            cache.get(cacheKey, ListSerializer(CoverCandidate.serializer()), 7 * CacheManager.DAY)?.let { cached ->
                return cached.map { ScoredCover(it, 0.0, true, emptyList()) }
            }
        }
        // Artwork from a metadata service is only used (and probed) when the user enabled that service.
        val meta = state.metadata?.takeIf { settings.externalMetadata }
        val enabled = registry.enabled(settings).map { it.id }.toSet()
        val bound = state.bindings.filterKeys { it in enabled }.filterValues { !it.unverified }
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
        if (!settings.externalMetadata) candidates.removeAll { it.provider in METADATA_PROVIDERS }
        candidates.forEach { c -> if (registry[c.provider] != null) registry.noteImage(c.url, c.provider) }
        if (candidates.isEmpty()) meta?.covers?.let { candidates += it }
        val ranked = covers.rank(candidates, settings, meta?.originalLanguage ?: merged(state)?.metadata?.originalLanguage, primaryBinding(state, settings))
        if (settings.cacheCovers && ranked.isNotEmpty()) {
            cache.put(cacheKey, ListSerializer(CoverCandidate.serializer()), ranked.take(4).map { it.candidate })
            cache.put("cover:best:${key.value}", String.serializer(), ranked.first().candidate.url)
        }
        return ranked
    }

    private fun primaryBinding(state: SeriesState, settings: AggregatorSettings): String? =
        state.bindings.filterValues { !it.unverified }.keys.sortedBy { settings.sourceRank(it) }.firstOrNull()

    /** The cover chosen when the series was last opened (cached ranking), without any request. */
    private fun bestKnownCover(key: String): String? =
        cache.get("cover:best:$key", String.serializer(), 30 * CacheManager.DAY)

    /** Browse-grid thumbnail: small pre-resized art; the full-quality cover is chosen when the series is opened. */
    private fun quickCover(m: SeriesMetadata, hits0: List<SourceSeries>, settings: AggregatorSettings): String? {
        val meta = (m.thumbnailUrl ?: m.covers.sortedByDescending { it.coverOrigin.trust }.firstOrNull()?.url).takeIf { settings.externalMetadata }
        val hits = hits0.filter { registry[it.sourceId]?.standaloneResults != false }
        return if (settings.highQualityCovers) meta ?: hits.firstNotNullOfOrNull { it.coverUrl } else hits.firstNotNullOfOrNull { it.coverUrl } ?: meta
    }

    /**
     * A linked provider's cover from stored state, without any request: replaces a library thumbnail that points at
     * a metadata service's CDN when that service is no longer used. Null when nothing is stored.
     */
    fun providerThumbnail(key: SeriesKey, settings: AggregatorSettings): String? {
        val state = cache.get("state:${key.value}", SeriesState.serializer(), Long.MAX_VALUE) ?: return null
        val enabled = registry.enabled(settings).map { it.id }.toSet()
        return orderedBindings(state).filter { it.series.sourceId in enabled && !it.unverified && registry[it.series.sourceId]?.standaloneResults != false }
            .firstNotNullOfOrNull { it.series.coverUrl }
    }

    /** Web page for a series: the external record's page when that service is enabled, else the primary provider's. */
    fun webUrl(key: SeriesKey, settings: AggregatorSettings): String? {
        val state = cache.get("state:${key.value}", SeriesState.serializer(), Long.MAX_VALUE)
        if (settings.externalMetadata) state?.metadata?.takeIf { it.isExternal }?.webUrl?.let { return it }
        val b = state?.let { orderedBindings(it).firstOrNull { b -> !b.unverified && registry[b.series.sourceId] != null } } ?: return null
        return b.series.webUrl ?: registry[b.series.sourceId]?.seriesWebUrl(b.series.id)
    }

    // ---------------------------------------------------------------- presentation

    private fun buildDetails(key: SeriesKey, state: SeriesState, covers: List<ScoredCover>?, report: CompletenessReport?, settings: AggregatorSettings, titleHint: String?): SeriesDetails {
        val mergedDetails = merged(state)
        val m = mergedDetails?.metadata
        val prov = mergedDetails?.provenance.orEmpty()
        val external = state.metadata?.takeIf { it.isExternal }
        val coverUrl = covers?.firstOrNull()?.let { best ->
            if (settings.coverFallback) {
                FallbackInterceptor.tagCover(best.candidate.url, covers.drop(1).take(3).map { FallbackInterceptor.CoverAlt(it.candidate.url, it.candidate.headers) })
            } else {
                best.candidate.url
            }
        }
        // The title shown so far stays when no external record names the series (catalogue-keyed library entries).
        val title = external?.displayTitle ?: titleHint?.takeIf { it.isNotBlank() && key !is SeriesKey.Provider } ?: m?.title?.takeIf { it.isNotBlank() } ?: titleHint ?: state.key
        fun name(source: String) = when (source) {
            "mangabaka" -> "MangaBaka"
            "anilist" -> "AniList"
            else -> registry[source]?.name ?: source
        }
        val description = buildString {
            m?.description?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
            val alt = m?.allTitles()?.filter { !it.equals(title, true) }?.take(6)
            if (!alt.isNullOrEmpty()) append("Also known as: ").append(alt.joinToString(" · ")).append("\n\n")
            run {
                append("— Library of Alexandria —\n")
                val enabled = registry.enabled(settings).map { it.id }.toSet()
                val linked = state.bindings.filterKeys { it in enabled }.filterValues { !it.unverified }
                if (linked.isEmpty()) {
                    append("No enabled source carries this series yet.\n")
                } else {
                    append("Sources: ").append(orderedBindings(state).filter { it.series.sourceId in linked }.mapNotNull { registry[it.series.sourceId]?.name }.joinToString(", ")).append('\n')
                }
                report?.summaryLines()?.forEach { append(it).append('\n') }
                m?.totalChapters?.let { append("Original run: ").append(it).append(" chapters (per ").append(name(m.provider)).append(")\n") }
                val fields = listOf("description", "authors", "status", "genres").mapNotNull { f -> prov[f]?.let { f to it } }
                if (fields.isNotEmpty()) {
                    append("Details: ").append(fields.joinToString("; ") { (f, src) -> "$f from " + src.split(", ").joinToString(", ") { name(it) } }).append('\n')
                }
            }
            // Attribution whenever MangaBaka data is shown (its data is CC BY-NC-SA 4.0), even from an earlier version's cache.
            val shown = prov.values.flatMap { it.split(", ") }.toSet()
            val credit = listOfNotNull("MangaBaka (CC BY-NC-SA 4.0)".takeIf { "mangabaka" in shown }, "AniList".takeIf { "anilist" in shown })
            if (credit.isNotEmpty()) append("Metadata: ").append(credit.joinToString(", ")).append('\n')
        }.trim()
        return SeriesDetails(
            title = title,
            author = m?.authors?.joinToString()?.ifEmpty { null },
            artist = m?.artists?.joinToString()?.ifEmpty { null },
            description = description,
            genres = m?.genres.orEmpty(),
            status = m?.status ?: PublicationStatus.UNKNOWN,
            coverUrl = coverUrl,
            webUrl = webUrl(key, settings) ?: m?.webUrl,
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
        // Index confirmed links so search and browse show these provider entries as this series (stable identity).
        state.bindings.values.filter { !it.unverified }.forEach { b ->
            val k = "idx:${b.series.sourceId}:${b.series.id}"
            if (cache.get(k, String.serializer(), Long.MAX_VALUE) == null) cache.put(k, String.serializer(), state.key)
        }
    }

    /** The series a provider entry is linked to (the first one that linked it), if any. */
    private fun indexedKey(s: SourceSeries): String? = cache.get("idx:${s.sourceId}:${s.id}", String.serializer(), Long.MAX_VALUE)

    /** Series keys whose verification rejected this provider entry. */
    private fun rejectedBy(s: SourceSeries): List<String> = cache.get("rej:${s.sourceId}:${s.id}", ListSerializer(String.serializer()), Long.MAX_VALUE).orEmpty()

    private fun rememberRejection(key: SeriesKey, s: SourceSeries) {
        val k = "rej:${s.sourceId}:${s.id}"
        val list = rejectedBy(s)
        if (key.value !in list) cache.put(k, ListSerializer(String.serializer()), (list + key.value).takeLast(8))
        // A search result made of this entry may have shown the pair together: let the next search regroup.
        searchCache.clear()
    }

    private fun storedTitle(key: String): String? {
        val state = cache.get("state:$key", SeriesState.serializer(), Long.MAX_VALUE) ?: return null
        return state.metadata?.takeIf { it.isExternal }?.displayTitle ?: merged(state)?.metadata?.title?.takeIf { it.isNotBlank() }
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

    /** Drops every in-memory chapter list and canonical record (after "clear cache"). */
    fun clearChapterLists() {
        listCache.clear("chl:")
        cache.clear("ch:")
        canonical.clear()
        searchHits.clear()
        detailsHits.clear()
        searchCache.clear()
    }

    private companion object {
        const val SEARCH_DEADLINE_MS = 6_000L
        const val SEARCH_CACHE_MS = 10 * CacheManager.MINUTE
        const val BROWSE_PAGE_SIZE = 30

        /** Providers asked for the first page of a search, and the result count below which the others are asked too. */
        const val INITIAL_SEARCH = 5
        const val FEW_RESULTS = 3
        const val RELEVANT = 0.8

        /** Host-requested refreshes reuse a provider list fetched within this window (duplicate calls). */
        const val EXPLICIT_REUSE_MS = 60_000L

        /** Library updates refresh a provider that is not among the [LEADERS] at most this often. */
        const val SECONDARY_REFRESH_MS = 6 * CacheManager.HOUR
        const val LEADERS = 2

        /** Provider search results and details are shared between operations for this long. */
        const val PROVIDER_RESULT_TTL = 20 * CacheManager.MINUTE

        /** Searches library updates may make per provider and minute (shared by every series being updated). */
        const val BACKGROUND_SEARCHES_PER_MINUTE = 6

        /** A provider whose rate-limit queue is at least this long is avoided by optional work. */
        const val BUSY_MS = 3_000L

        /** A library update searches every remaining provider for a series at most this often. */
        const val BACKGROUND_WIDEN_MS = 12 * CacheManager.HOUR

        /** Most sources linked (read from and refreshed) per series; further confirmed matches are kept as spares. */
        const val MAX_LINKED = 5
        const val MAX_SPARES = 6

        /** Linked providers wanted per series: one to read from, one to fall back on. */
        const val TARGET_LINKED = 2

        /** Providers searched in the first discovery round. */
        const val INITIAL_DISCOVERY = 3

        /** A provider without a match is searched again after this long. */
        const val MISS_TTL = 7 * CacheManager.DAY

        const val CANONICAL_SERIES = 64
        const val DISCOVERY_KEY = "disc:stats"
        val DISCOVERY_SERIALIZER = MapSerializer(String.serializer(), ListSerializer(Int.serializer()))
        val METADATA_PROVIDERS = setOf("mangabaka", "anilist")

        /** Provider name of a reference made from nothing but the host's title. */
        const val TITLE_ONLY = "host-title"
    }

    private fun isGallery(key: SeriesKey) = key is SeriesKey.Provider && registry[key.sourceId]?.role == ProviderRole.GALLERY

    private fun keyFor(m: SeriesMetadata): SeriesKey? =
        m.ids.mangabaka?.let { SeriesKey.MangaBaka(it) } ?: m.ids.anilist?.let { SeriesKey.AniList(it) }

    /**
     * One provider call with health tracking, an overall deadline and per-request network timeouts. Any failure of the
     * provider (its own timeout included) yields null; cancellation of the caller propagates.
     */
    private suspend fun <T> guarded(adapter: SourceAdapter, settings: AggregatorSettings, block: suspend () -> T): T? = try {
        registry.health.call(adapter.id, settings.requestTimeoutSeconds * 1000L) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("${adapter.id} failed: ${e::class.simpleName}: ${e.message}")
        null
    }
}

/** A record from an external metadata service (fetched now, or stored by an earlier version). */
val SeriesMetadata.isExternal: Boolean get() = provider == "mangabaka" || provider == "anilist"
