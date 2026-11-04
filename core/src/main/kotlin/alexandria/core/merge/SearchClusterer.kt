package alexandria.core.merge

import alexandria.core.model.ContentKind
import alexandria.core.model.SourceSeries
import alexandria.core.identity.IdentityResolver
import alexandria.core.normalization.TitleNormalizer

/** Search hits from several providers presented as one result. [key] is the identity they already belong to, if any. */
data class SearchCluster(val members: List<SourceSeries>, val key: String?) {
    val anchor: SourceSeries get() = members.first()
}

/**
 * Groups provider search hits into results without any external lookup.
 *
 * A grouping in search is a *presentation* decision, not a confirmed link: hits join one result only when they have
 * the same title (same edition qualifiers) or share a database id, and nothing they report contradicts each other
 * (database ids, novel vs comic, years more than three apart, different authors). Each other provider in a result is
 * only a hint; it becomes a link when the series is opened and the provider's details corroborate the match
 * ([IdentityResolver]). Hits already linked to a series ([keyOf]) always show as that series, so a library entry
 * keeps its identity whatever the provider order. Pairs a verification has rejected ([rejected]) are never grouped
 * again.
 *
 * At most one hit per provider joins a result (two same-title hits on one site are different entries).
 * [canAnchor] says which providers may head a result (providers whose entries should not stand alone may still join).
 */
object SearchClusterer {

    fun cluster(
        hits: List<SourceSeries>,
        keyOf: (SourceSeries) -> String?,
        rejected: (SourceSeries, SourceSeries) -> Boolean,
        priority: (String) -> Int,
        canAnchor: (String) -> Boolean,
        /** Entries of different roles (series sites, single galleries) are never grouped. */
        roleOf: (SourceSeries) -> Any? = { null },
        /**
         * How well an entry's own title answers the search. Within one site, better answers are placed first, so a
         * site's "Tower of God" takes its place in the result before its "Tower of God (Book Version)" (whose aliases
         * include the plain title) can.
         */
        relevance: (SourceSeries) -> Double = { 0.0 },
    ): List<SearchCluster> {
        val ordered = hits.distinctBy { it.sourceId to it.id }
            .sortedWith(compareBy<SourceSeries> { priority(it.sourceId) }.thenByDescending { relevance(it) })
        val clusters = mutableListOf<MutableList<SourceSeries>>()
        val keys = mutableListOf<String?>()

        // 1. Hits already known to belong to a series.
        for (h in ordered) {
            val k = keyOf(h) ?: continue
            val i = keys.indexOf(k)
            if (i >= 0) clusters[i] += h else { clusters += mutableListOf(h); keys += k }
        }
        // 2. Everything else joins a compatible result or starts its own.
        for (h in ordered) {
            if (keyOf(h) != null) continue
            val i = clusters.indices.firstOrNull { c -> clusters[c].all { roleOf(it) == roleOf(h) } && compatible(clusters[c], h, rejected) }
            if (i != null) {
                clusters[i] += h
            } else if (clusters.any { c -> c.all { roleOf(it) == roleOf(h) } && copyOnSameSite(c, h, rejected) }) {
                // A site listing one series twice (MangaFire's "Golden Mage" ×2, Hitomi's chapters uploaded as galleries
                // of one name): the second entry is not shown. Opening the result links the site's best entry.
                continue
            } else if (canAnchor(h.sourceId)) {
                clusters += mutableListOf(h); keys += null
            }
        }
        return clusters.indices.map { i ->
            // The anchor is the first member a result may be headed by, in fixed provider order.
            val m = clusters[i].sortedBy { priority(it.sourceId) }
            val anchorFirst = m.firstOrNull { canAnchor(it.sourceId) }?.let { a -> listOf(a) + (m - a) } ?: m
            SearchCluster(anchorFirst, keys[i])
        }.filter { c -> c.key != null || canAnchor(c.anchor.sourceId) }
    }

    private fun compatible(cluster: List<SourceSeries>, h: SourceSeries, rejected: (SourceSeries, SourceSeries) -> Boolean): Boolean {
        if (cluster.any { it.sourceId == h.sourceId }) return false
        // Authors in common with one member outweigh differently romanised names on another ("Hanjung Wolya" here,
        // "Han-Joong-Wueol-Ya" there): sites credit the same people under many spellings.
        val authorsAgree = cluster.any { sameAuthors(it, h) }
        if (cluster.any { contradicts(it, h, checkAuthors = !authorsAgree) || rejected(it, h) || rejected(h, it) }) return false
        return cluster.any { sameTitle(it, h) || it.ids.compare(h.ids) == 1 }
    }

    /** [h] has exactly the main title of a member from its own site, and nothing in the result contradicts it. */
    private fun copyOnSameSite(cluster: List<SourceSeries>, h: SourceSeries, rejected: (SourceSeries, SourceSeries) -> Boolean): Boolean {
        val twin = cluster.firstOrNull {
            it.sourceId == h.sourceId && TitleNormalizer.compact(it.title) == TitleNormalizer.compact(h.title) &&
                TitleNormalizer.qualifiers(it.title) == TitleNormalizer.qualifiers(h.title)
        } ?: return false
        return cluster.none { contradicts(it, h) || rejected(it, h) || rejected(h, it) } && twin.kind.let { k -> k == h.kind || k == ContentKind.UNKNOWN || h.kind == ContentKind.UNKNOWN }
    }

    /**
     * One entry's main title is the other's main title or one of its alternative titles ("30 Years Since the Prologue"
     * listed by one site, "30 Years Have Passed Since the Prologue" with the first as an alt title by another). Same
     * edition qualifiers required; very short titles only match exactly.
     */
    internal fun sameTitle(a: SourceSeries, b: SourceSeries): Boolean {
        fun mainIn(x: SourceSeries, y: SourceSeries): Boolean {
            val main = TitleNormalizer.compact(x.title)
            if (main.isEmpty()) return false
            val quals = TitleNormalizer.qualifiers(x.title)
            return y.allTitles().any { t ->
                val c = TitleNormalizer.compact(t)
                c == main && TitleNormalizer.qualifiers(t) == quals && (t == y.title || main.length >= MIN_ALT_MATCH)
            }
        }
        return mainIn(a, b) || mainIn(b, a)
    }

    /** Shortest compact title that may match through an alternative title (short ones are too often shared). */
    private const val MIN_ALT_MATCH = 8

    private fun sameAuthors(a: SourceSeries, b: SourceSeries): Boolean {
        val aa = IdentityResolver.realAuthors(a.authors)
        val ba = IdentityResolver.realAuthors(b.authors)
        return aa.isNotEmpty() && ba.isNotEmpty() && (IdentityResolver.authorsOverlap(aa, ba) || IdentityResolver.authorsAlike(aa, ba))
    }

    /** Evidence that two entries are different works. */
    fun contradicts(a: SourceSeries, b: SourceSeries, checkAuthors: Boolean = true): Boolean {
        if (a.ids.compare(b.ids) == -1) return true
        if (a.kind != ContentKind.UNKNOWN && b.kind != ContentKind.UNKNOWN && (a.kind == ContentKind.NOVEL) != (b.kind == ContentKind.NOVEL)) return true
        val ya = a.year
        val yb = b.year
        if (ya != null && yb != null && kotlin.math.abs(ya - yb) > 3) return true
        val aa = IdentityResolver.realAuthors(a.authors)
        val ba = IdentityResolver.realAuthors(b.authors)
        // Korean and Chinese names have no single romanisation ("Supp" / "Seop-I", "Coul" / "Krukru"): differing Latin
        // credits prove nothing for manhwa and manhua.
        val romanised = a.kind == ContentKind.MANHWA || a.kind == ContentKind.MANHUA || b.kind == ContentKind.MANHWA || b.kind == ContentKind.MANHUA
        if (checkAuthors && !romanised && aa.isNotEmpty() && ba.isNotEmpty() && (aa + ba).all { n -> n.all { !it.isLetter() || it.code < 0x250 } } &&
            !IdentityResolver.authorsOverlap(aa, ba) && !IdentityResolver.authorsAlike(aa, ba)
        ) {
            val wa = aa.flatMap { TitleNormalizer.normalize(it).split(' ') }.filter { it.length >= 3 }.toSet()
            if (ba.flatMap { TitleNormalizer.normalize(it).split(' ') }.none { it.length >= 3 && it in wa }) return true
        }
        return false
    }
}
