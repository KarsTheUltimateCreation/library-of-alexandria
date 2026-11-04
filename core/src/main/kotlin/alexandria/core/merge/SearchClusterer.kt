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
        /** Providers of different roles (series sites, gallery sites) are never grouped. */
        roleOf: (String) -> Any? = { null },
    ): List<SearchCluster> {
        val ordered = hits.distinctBy { it.sourceId to it.id }.sortedBy { priority(it.sourceId) }
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
            val i = clusters.indices.firstOrNull { c -> clusters[c].all { roleOf(it.sourceId) == roleOf(h.sourceId) } && compatible(clusters[c], h, rejected) }
            if (i != null) {
                clusters[i] += h
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
        if (cluster.any { contradicts(it, h) || rejected(it, h) || rejected(h, it) }) return false
        return cluster.any { sameTitle(it, h) || it.ids.compare(h.ids) == 1 }
    }

    private fun sameTitle(a: SourceSeries, b: SourceSeries): Boolean =
        TitleNormalizer.compact(a.title) == TitleNormalizer.compact(b.title) &&
            TitleNormalizer.qualifiers(a.title) == TitleNormalizer.qualifiers(b.title)

    /** Evidence that two entries are different works. */
    fun contradicts(a: SourceSeries, b: SourceSeries): Boolean {
        if (a.ids.compare(b.ids) == -1) return true
        if (a.kind != ContentKind.UNKNOWN && b.kind != ContentKind.UNKNOWN && (a.kind == ContentKind.NOVEL) != (b.kind == ContentKind.NOVEL)) return true
        val ya = a.year
        val yb = b.year
        if (ya != null && yb != null && kotlin.math.abs(ya - yb) > 3) return true
        if (a.authors.isNotEmpty() && b.authors.isNotEmpty() && (a.authors + b.authors).all { n -> n.all { !it.isLetter() || it.code < 0x250 } } &&
            !IdentityResolver.authorsOverlap(a.authors, b.authors)
        ) {
            val wa = a.authors.flatMap { TitleNormalizer.normalize(it).split(' ') }.filter { it.length >= 3 }.toSet()
            if (b.authors.flatMap { TitleNormalizer.normalize(it).split(' ') }.none { it.length >= 3 && it in wa }) return true
        }
        return false
    }
}
