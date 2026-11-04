package alexandria.core.metadata

import alexandria.core.SeriesKey
import alexandria.core.cache.CacheManager
import alexandria.core.config.AggregatorSettings
import alexandria.core.config.MetadataPreference
import alexandria.core.identity.IdentityResolver
import alexandria.core.identity.MatchDecision
import alexandria.core.model.SeriesMetadata
import alexandria.core.model.SourceSeries

/**
 * Builds the authoritative [SeriesMetadata] for a series by combining MangaBaka and AniList (each optional),
 * caching results, and degrading gracefully: if a service is disabled or fails, the other one (or the
 * provider's own data) is used instead. Disabled services receive no requests.
 */
class MetadataResolver(
    private val mangaBaka: MangaBakaClient,
    private val aniList: AniListClient,
    private val cache: CacheManager,
    private val identity: IdentityResolver = IdentityResolver(),
    private val log: (String) -> Unit = {},
) {

    suspend fun forKey(key: SeriesKey, settings: AggregatorSettings, providerHint: SourceSeries? = null): SeriesMetadata? = when (key) {
        is SeriesKey.MangaBaka -> {
            val mb = if (settings.mangabaka) cached("mb:${key.id}", settings) { mangaBaka.series(key.id) } else null
            // AniList (30 req/min) only adds value when MangaBaka lacks a description/genres or is not preferred.
            val needAniList = settings.anilist && (mb == null || mb.description.isNullOrBlank() || mb.genres.isEmpty() ||
                settings.metadataPreference == MetadataPreference.ANILIST)
            val al = if (needAniList) (mb?.ids?.anilist)?.let { id -> cached("al:$id", settings) { aniList.byId(id) } } else null
            merge(mb, al, settings) ?: providerHint?.asMetadata()
        }
        is SeriesKey.AniList -> {
            val al = if (settings.anilist) cached("al:${key.id}", settings) { aniList.byId(key.id) } else null
            val mb = if (settings.mangabaka) cached("mb-al:${key.id}", settings) { mangaBaka.byAniList(key.id) } else null
            merge(mb, al, settings) ?: providerHint?.asMetadata()
        }
        is SeriesKey.Provider -> providerHint?.let { matchProvider(it, settings) } ?: providerHint?.asMetadata()
    }

    /** Finds the metadata record for a provider-only series (by shared ids first, then by title search). */
    suspend fun matchProvider(series: SourceSeries, settings: AggregatorSettings): SeriesMetadata {
        val own = series.asMetadata()
        val alId = series.ids.anilist
        if (alId != null) {
            forKey(SeriesKey.AniList(alId), settings)?.let { return mergeWithProvider(it, series) }
        }
        val candidates = mutableListOf<SeriesMetadata>()
        if (settings.mangabaka) candidates += safe("mangabaka search") { mangaBaka.search(series.title, limit = 6) }.orEmpty()
        if (candidates.isEmpty() && settings.anilist) candidates += safe("anilist search") { aniList.search(series.title, 6) }.orEmpty()
        // Reverse match: the provider entry is the reference, metadata records are the candidates.
        val best = candidates
            .map { m -> m to identity.match(own, m.asSourceSeries()) }
            .filter { it.second.decision == MatchDecision.CONFIRMED }
            .maxByOrNull { it.second.score }
        return best?.first?.let { mergeWithProvider(it, series) } ?: own
    }

    suspend fun search(query: String, settings: AggregatorSettings, limit: Int = 12): List<SeriesMetadata> {
        val adult = settings.adultContent
        val preferAniList = settings.metadataPreference == MetadataPreference.ANILIST || !settings.mangabaka
        if (!preferAniList) {
            safe("mangabaka search") { mangaBaka.search(query, limit, adult = adult) }?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (settings.anilist) {
            safe("anilist search") { aniList.search(query, limit, adult = adult) }?.let { return it }
        }
        if (preferAniList && settings.mangabaka) {
            safe("mangabaka search") { mangaBaka.search(query, limit, adult = adult) }?.let { return it }
        }
        return emptyList()
    }

    suspend fun popular(page: Int, settings: AggregatorSettings): List<SeriesMetadata>? = when {
        settings.mangabaka && settings.metadataPreference != MetadataPreference.ANILIST ->
            // MangaBaka sorts by popularity *rank*: ascending = most popular first (verified against the live API).
            safe("mangabaka popular") { mangaBaka.search("", limit = 30, page = page, sortBy = "popularity_asc", adult = settings.adultContent) }
                ?: if (settings.anilist) safe("anilist popular") { aniList.popular(page) } else null
        settings.anilist -> safe("anilist popular") { aniList.popular(page) }
        else -> null
    }

    /**
     * Filtered browse/search (MangaBaka only: AniList's unauthenticated search has no equivalent filters).
     * Null when MangaBaka is disabled or fails, so the caller can fall back.
     */
    suspend fun browse(query: String, filters: CatalogFilters, page: Int, settings: AggregatorSettings, limit: Int = 30): List<SeriesMetadata>? {
        if (!settings.mangabaka) return null
        return safe("mangabaka browse") {
            mangaBaka.search(query, limit = limit, page = page, sortBy = if (query.isBlank()) "popularity_asc" else null, adult = settings.adultContent, filters = filters)
        }
    }

    fun merge(mb: SeriesMetadata?, al: SeriesMetadata?, settings: AggregatorSettings): SeriesMetadata? {
        if (mb == null) return al
        if (al == null) return mb
        val (first, second) = if (settings.metadataPreference == MetadataPreference.ANILIST) al to mb else mb to al
        return first.copy(
            ids = first.ids.merge(second.ids),
            englishTitle = first.englishTitle ?: second.englishTitle,
            romanizedTitle = first.romanizedTitle ?: second.romanizedTitle,
            nativeTitle = first.nativeTitle ?: second.nativeTitle,
            altTitles = (first.altTitles + second.altTitles).distinct(),
            originalLanguage = first.originalLanguage ?: second.originalLanguage,
            year = first.year ?: second.year,
            // MangaBaka is the completeness reference when available.
            totalChapters = mb.totalChapters ?: al.totalChapters,
            description = first.description ?: second.description,
            authors = first.authors.ifEmpty { second.authors },
            artists = first.artists.ifEmpty { second.artists },
            genres = (first.genres + second.genres).distinctBy { it.lowercase() },
            covers = (first.covers + second.covers).distinctBy { it.url },
        )
    }

    private fun mergeWithProvider(meta: SeriesMetadata, series: SourceSeries) = meta.copy(
        ids = meta.ids.merge(series.ids),
        altTitles = (meta.altTitles + series.allTitles()).distinct(),
        covers = (meta.covers + series.asMetadata().covers).distinctBy { it.url },
    )

    private suspend fun cached(key: String, settings: AggregatorSettings, load: suspend () -> SeriesMetadata?): SeriesMetadata? {
        val ttl = settings.metadataCacheHours * CacheManager.HOUR
        cache.get("meta:$key", SeriesMetadata.serializer(), ttl)?.let { return it }
        val fresh = safe(key) { load() }
        if (fresh != null) {
            cache.put("meta:$key", SeriesMetadata.serializer(), fresh)
        } else {
            // Serve stale data rather than nothing when the service is down.
            cache.get("meta:$key", SeriesMetadata.serializer(), Long.MAX_VALUE)?.let { return it }
        }
        return fresh
    }

    private suspend fun <T> safe(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        log("metadata $what failed: ${e.message}")
        null
    }
}

/** Lets the identity resolver compare two metadata records. */
fun SeriesMetadata.asSourceSeries() = SourceSeries(
    sourceId = provider,
    id = ids.mangabaka?.toString() ?: ids.anilist?.toString() ?: title,
    title = title,
    altTitles = allTitles().drop(1),
    ids = ids,
    kind = kind,
    status = status,
    year = year,
    detailed = true,
)
