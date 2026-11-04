package alexandria.core.metadata

/**
 * Browse/search filters, expressed in MangaBaka's vocabulary (https://api.mangabaka.org/v1/series/search):
 * sort_by, type, status, genre (all must match), genre_not and content_rating.
 */
data class CatalogFilters(
    val sortBy: String? = null,
    val types: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val excludedGenres: List<String> = emptyList(),
    val contentRatings: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = sortBy == null && types.isEmpty() && statuses.isEmpty() && genres.isEmpty() && excludedGenres.isEmpty() && contentRatings.isEmpty()

    companion object {
        /** Comic formats; MangaBaka also catalogues novels. */
        val COMIC_TYPES = listOf("manga", "manhwa", "manhua", "oel")

        val NON_ADULT_RATINGS = listOf("safe", "suggestive", "erotica")
        val ALL_RATINGS = listOf("safe", "suggestive", "erotica", "pornographic")

        /** Ratings that mark a series as adult content (MangaBaka rates e.g. Berserk "erotica", so that is not one). */
        val ADULT_RATINGS = setOf("pornographic")
    }
}
