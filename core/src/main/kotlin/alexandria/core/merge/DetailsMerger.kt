package alexandria.core.merge

import alexandria.core.model.ContentKind
import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.model.PublicationStatus
import alexandria.core.model.SeriesMetadata
import alexandria.core.model.SourceSeries

/** Combined series information plus, per field, which source supplied it. */
data class MergedDetails(val metadata: SeriesMetadata, val provenance: Map<String, String>)

/**
 * Builds one [SeriesMetadata] from what the linked providers report, optionally on top of an external metadata
 * record ([base]). Rules, applied per field:
 *
 *  - precedence: [base] (when present) first, then [providers] in the order given (the caller passes the entry's
 *    own provider and manual links first, then the other confirmed links by confidence and a fixed provider order);
 *  - a blank, empty or UNKNOWN value never replaces a known one, and a short description (a one-line teaser) yields
 *    to a substantial one further down the order;
 *  - genres and alternative titles are unions (case-insensitive), everything else comes from one source;
 *  - nothing is invented: a field no source reports stays empty/UNKNOWN.
 *
 * [provenance] names the source of each filled field ("description" -> "weebcentral").
 */
object DetailsMerger {
    const val USEFUL_DESCRIPTION = 80

    fun merge(base: SeriesMetadata?, providers: List<SourceSeries>): MergedDetails? {
        if (base == null && providers.isEmpty()) return null
        val prov = linkedMapOf<String, String>()
        val baseName = base?.provider

        fun <T> pick(field: String, fromBase: T?, known: (T) -> Boolean, fromProvider: (SourceSeries) -> T?): T? {
            if (fromBase != null && known(fromBase)) { prov[field] = baseName!!; return fromBase }
            for (p in providers) {
                val v = fromProvider(p) ?: continue
                if (known(v)) { prov[field] = p.sourceId; return v }
            }
            return null
        }

        val first = providers.firstOrNull { it.title.isNotBlank() } ?: providers.firstOrNull()
        val title = base?.title ?: first!!.title
        prov["title"] = baseName ?: first!!.sourceId

        // Description: the first substantial one in precedence order, else the longest non-blank one.
        val descriptions = listOfNotNull(base?.description?.let { baseName!! to it }) + providers.mapNotNull { p -> p.description?.let { p.sourceId to it } }
        val cleaned = descriptions.map { it.first to it.second.trim() }.filter { it.second.isNotEmpty() }
        val description = (cleaned.firstOrNull { it.second.length >= USEFUL_DESCRIPTION } ?: cleaned.maxByOrNull { it.second.length })
            ?.also { prov["description"] = it.first }?.second

        val authors = pick("authors", base?.authors, { it.isNotEmpty() }) { it.authors.map(String::trim).filter(String::isNotEmpty) }.orEmpty()
        val status = pick("status", base?.status, { it != PublicationStatus.UNKNOWN }) { it.status } ?: PublicationStatus.UNKNOWN
        val kind = pick("format", base?.kind, { it != ContentKind.UNKNOWN }) { it.kind } ?: ContentKind.UNKNOWN
        val year = pick("year", base?.year, { it in 1900..2100 }) { it.year }
        val language = pick("original language", base?.originalLanguage, { it.isNotBlank() }) { it.originalLanguage }

        val genres = ((base?.genres.orEmpty()) + providers.flatMap { it.genres })
            .map { it.trim() }.filter { it.isNotEmpty() && it.length <= 40 }
            .distinctBy { it.lowercase().replace('_', ' ') }
            .take(MAX_GENRES)
        if (genres.isNotEmpty()) prov["genres"] = (listOfNotNull(baseName.takeIf { base?.genres?.isNotEmpty() == true }) + providers.filter { it.genres.isNotEmpty() }.map { it.sourceId }).distinct().joinToString(", ")

        val titles = (listOfNotNull(base?.englishTitle, base?.romanizedTitle, base?.nativeTitle) + base?.altTitles.orEmpty() + providers.flatMap { it.allTitles() })
            .map { it.trim() }.filter { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(MAX_ALT_TITLES)

        var ids = base?.ids ?: alexandria.core.model.ExternalIds()
        providers.forEach { ids = ids.merge(it.ids) }

        val covers = base?.covers.orEmpty() + providers.mapNotNull { p ->
            p.coverUrl?.let { CoverCandidate(it, p.sourceId, CoverOrigin.PROVIDER_THUMBNAIL.name) }
        }

        val merged = SeriesMetadata(
            provider = baseName ?: first!!.sourceId,
            ids = ids,
            title = title,
            englishTitle = base?.englishTitle,
            romanizedTitle = base?.romanizedTitle,
            nativeTitle = base?.nativeTitle,
            altTitles = titles,
            kind = kind,
            status = status,
            originalLanguage = language,
            year = year,
            totalChapters = base?.totalChapters,
            description = description,
            authors = authors,
            artists = base?.artists.orEmpty(),
            genres = genres,
            covers = covers.distinctBy { it.url },
            thumbnailUrl = base?.thumbnailUrl,
            webUrl = base?.webUrl ?: providers.firstNotNullOfOrNull { it.webUrl },
            adult = base?.adult == true,
        )
        return MergedDetails(merged, prov)
    }

    private const val MAX_GENRES = 25
    private const val MAX_ALT_TITLES = 24
}
