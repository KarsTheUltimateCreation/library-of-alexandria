package eu.kanade.tachiyomi.extension.all.libraryofalexandria

import alexandria.core.metadata.CatalogFilters
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Browse/search filters. With MangaBaka enabled they are applied by its catalogue search. In the default
 * provider-only mode they are applied to the providers' results as far as each provider reports the field
 * (see alexandria.core.merge.LocalFilters): unreported values do not match include filters, and only title sorting
 * is available.
 */
object Filters {

    private class Option(val name: String, val value: String)

    private val SORTS = listOf(
        Option("Default (popularity / relevance)", ""),
        Option("Most popular", "popularity_asc"),
        Option("Trending this week", "trending_7d"),
        Option("Trending this month", "trending_30d"),
        Option("Recently updated", "latest"),
        Option("Highest rated", "score_desc"),
        Option("Newest", "published_start_date_desc"),
        Option("Oldest", "published_start_date_asc"),
        Option("Most chapters", "chapters_desc"),
        Option("Title A–Z", "name_asc"),
        Option("Title Z–A", "name_desc"),
    )

    private val TYPES = listOf(Option("Manga", "manga"), Option("Manhwa", "manhwa"), Option("Manhua", "manhua"), Option("OEL", "oel"))

    private val STATUSES = listOf(
        Option("Releasing", "releasing"),
        Option("Completed", "completed"),
        Option("Hiatus", "hiatus"),
        Option("Cancelled", "cancelled"),
        Option("Upcoming", "upcoming"),
    )

    private val RATINGS = listOf(
        Option("Safe", "safe"),
        Option("Suggestive", "suggestive"),
        Option("Erotica", "erotica"),
        Option("Pornographic", "pornographic"),
    )

    /** MangaBaka genre values (GET /v1/genres), minus categories the extension never offers. */
    private val GENRES = listOf(
        "Action" to "action", "Adult" to "adult", "Adventure" to "adventure", "Avant Garde" to "avant_garde",
        "Award Winning" to "award_winning", "Boys' Love" to "boys_love", "Comedy" to "comedy", "Doujinshi" to "doujinshi",
        "Drama" to "drama", "Ecchi" to "ecchi", "Erotica" to "erotica", "Fantasy" to "fantasy", "Gender Bender" to "gender_bender",
        "Girls' Love" to "girls_love", "Gourmet" to "gourmet", "Harem" to "harem", "Hentai" to "hentai", "Historical" to "historical",
        "Horror" to "horror", "Josei" to "josei", "Mahou Shoujo" to "mahou_shoujo", "Martial Arts" to "martial_arts", "Mature" to "mature",
        "Mecha" to "mecha", "Music" to "music", "Mystery" to "mystery", "Psychological" to "psychological", "Romance" to "romance",
        "School Life" to "school_life", "Sci-Fi" to "sci-fi", "Seinen" to "seinen", "Shoujo" to "shoujo", "Shoujo Ai" to "shoujo_ai",
        "Shounen" to "shounen", "Shounen Ai" to "shounen_ai", "Slice of Life" to "slice_of_life", "Smut" to "smut", "Sports" to "sports",
        "Supernatural" to "supernatural", "Suspense" to "suspense", "Thriller" to "thriller", "Tragedy" to "tragedy", "Yaoi" to "yaoi",
        "Yuri" to "yuri",
    ).map { (n, v) -> Option(n, v) }

    private class SortFilter : Filter.Select<String>("Sort by", SORTS.map { it.name }.toTypedArray())

    private class OptionBox(val option: Option) : Filter.CheckBox(option.name)

    private class OptionGroup(name: String, options: List<Option>) : Filter.Group<OptionBox>(name, options.map { OptionBox(it) }) {
        val selected: List<String> get() = state.filter { it.state }.map { it.option.value }
    }

    private class GenreState(val option: Option) : Filter.TriState(option.name)

    private class GenreGroup(options: List<Option>) : Filter.Group<GenreState>("Genres (tap twice to exclude)", options.map { GenreState(it) })

    fun list(): FilterList = FilterList(
        Filter.Header("Without MangaBaka, filters apply to what sources report"),
        SortFilter(),
        OptionGroup("Type", TYPES),
        OptionGroup("Status", STATUSES),
        OptionGroup("Content rating", RATINGS),
        GenreGroup(GENRES),
    )

    fun parse(filters: FilterList): CatalogFilters {
        var sort: String? = null
        var types = emptyList<String>()
        var statuses = emptyList<String>()
        var ratings = emptyList<String>()
        val include = mutableListOf<String>()
        val exclude = mutableListOf<String>()
        for (f in filters) {
            when (f) {
                is SortFilter -> sort = SORTS.getOrNull(f.state)?.value?.ifEmpty { null }
                is OptionGroup -> when (f.name) {
                    "Type" -> types = f.selected
                    "Status" -> statuses = f.selected
                    "Content rating" -> ratings = f.selected
                }
                is GenreGroup -> f.state.forEach { g ->
                    when {
                        g.isIncluded() -> include += g.option.value
                        g.isExcluded() -> exclude += g.option.value
                    }
                }
                else -> {}
            }
        }
        return CatalogFilters(sort, types, statuses, include, exclude, ratings)
    }
}
