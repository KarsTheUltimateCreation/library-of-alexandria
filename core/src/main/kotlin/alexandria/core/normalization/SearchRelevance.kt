package alexandria.core.normalization

/**
 * How well a title answers a search query, in [0, 1]. Used to order the merged search results (metadata
 * records and provider-only entries come from different services with incomparable rankings) and to drop
 * provider hits that only fuzzily resemble the query.
 */
object SearchRelevance {

    /** Typographic quotes from mobile keyboards ("don’t") confuse some providers' search. */
    fun cleanQuery(query: String): String = query
        .replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'').replace('`', '\'')
        .replace('“', '"').replace('”', '"')
        .replace(Regex("\\s+"), " ")
        .trim()

    fun score(query: String, titles: Collection<String>): Double = titles.maxOfOrNull { score(query, it) } ?: 0.0

    fun score(query: String, title: String): Double {
        val q = TitleNormalizer.normalize(query)
        val t = TitleNormalizer.normalize(title)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (q.replace(" ", "") == t.replace(" ", "")) return 1.0
        val queryWords = q.split(' ').filter { it.isNotEmpty() }
        val titleWords = t.split(' ').filter { it.isNotEmpty() }
        val titleSet = titleWords.toSet()
        // A word counts when present; the last query word may be a prefix (the user is still typing it).
        val covered = queryWords.withIndex().count { (i, w) ->
            w in titleSet || (i == queryWords.lastIndex && w.length >= 3 && titleWords.any { it.startsWith(w) })
        }
        val coverage = covered.toDouble() / queryWords.size
        var s = maxOf(TitleNormalizer.similarity(query, title), coverage * 0.75)
        if (coverage == 1.0) {
            // Every query word is in the title: tighter titles rank higher, a title that starts with the query higher still.
            s = maxOf(s, 0.8 + 0.12 * queryWords.size / titleWords.size.coerceAtLeast(1))
            if (t.startsWith(q)) s = maxOf(s, 0.9 + 0.08 * q.length / t.length)
        }
        return s.coerceIn(0.0, 0.99)
    }

    /** Provider-only hits below this are fuzzy matches ("Don Dracula" for "don't tell mom") and are not shown. */
    const val PROVIDER_HIT_MIN = 0.6
}
