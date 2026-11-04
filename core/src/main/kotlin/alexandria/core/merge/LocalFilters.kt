package alexandria.core.merge

import alexandria.core.SeriesCard
import alexandria.core.metadata.CatalogFilters
import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus

/**
 * Catalogue filters applied to provider results when no catalogue service is used. Providers report formats,
 * statuses and genres unevenly, so the rules degrade instead of guessing:
 *
 *  - an *include* filter (type, status, genre) keeps only results whose provider reports a matching value; results
 *    that do not report the field at all are dropped, since they cannot be shown to match;
 *  - an *exclude* filter drops only results known to carry the genre;
 *  - sorting by title is applied; other sort orders (popularity, score, dates) need data providers do not expose in
 *    search results and keep the providers' own order;
 *  - content-rating filters are not applied (providers do not rate content consistently).
 */
object LocalFilters {
    fun apply(cards: List<SeriesCard>, f: CatalogFilters): List<SeriesCard> {
        var out = cards.filter { c ->
            val s = c.hit
            val kindOk = f.types.isEmpty() || (s != null && s.kind != ContentKind.UNKNOWN && f.types.any { kindMatches(it, s.kind) })
            val statusOk = f.statuses.isEmpty() || (s != null && s.status != PublicationStatus.UNKNOWN && f.statuses.any { statusMatches(it, s.status) })
            val genres = s?.genres.orEmpty().map(::norm).toSet()
            val includeOk = f.genres.isEmpty() || f.genres.all { norm(it) in genres }
            val excludeOk = f.excludedGenres.none { norm(it) in genres }
            kindOk && statusOk && includeOk && excludeOk
        }
        when (f.sortBy) {
            "name_asc" -> out = out.sortedBy { it.title.lowercase() }
            "name_desc" -> out = out.sortedByDescending { it.title.lowercase() }
        }
        return out
    }

    private fun norm(g: String) = g.trim().lowercase().replace('_', ' ').replace('-', ' ').replace("'", "")

    private fun kindMatches(type: String, kind: ContentKind) = when (type.lowercase()) {
        "manga" -> kind == ContentKind.MANGA
        "manhwa" -> kind == ContentKind.MANHWA
        "manhua" -> kind == ContentKind.MANHUA
        "oel" -> kind == ContentKind.COMIC
        else -> false
    }

    private fun statusMatches(status: String, s: PublicationStatus) = when (status.lowercase()) {
        "releasing" -> s == PublicationStatus.ONGOING
        "completed" -> s == PublicationStatus.COMPLETED
        "hiatus" -> s == PublicationStatus.HIATUS
        "cancelled" -> s == PublicationStatus.CANCELLED
        else -> false
    }
}
