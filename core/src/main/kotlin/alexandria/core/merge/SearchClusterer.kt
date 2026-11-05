package alexandria.core.merge

import alexandria.core.model.ContentKind
import alexandria.core.model.SourceSeries
import alexandria.core.identity.IdentityResolver
import alexandria.core.normalization.TitleNormalizer

/**
 * Search hits from several providers presented as one result. [key] is the identity they already belong to, if any;
 * [aliases] are further saved identities that strong evidence showed to be the same work (shown as [key]).
 */
data class SearchCluster(val members: List<SourceSeries>, val key: String?, val aliases: Set<String> = emptySet()) {
    val anchor: SourceSeries get() = members.first()
}

/**
 * Groups provider search hits into results without any external lookup.
 *
 * A grouping in search is a *presentation* decision, not a confirmed link: hits join one result only when they have
 * the same title (same edition qualifiers, on the primary titles too) or share a database id, and nothing they report
 * contradicts each other (database ids, novel vs comic, years more than three apart, different authors). Each other
 * provider in a result is only a hint; it becomes a link when the series is opened and the provider's details
 * corroborate the match ([IdentityResolver]). Hits already linked to a series ([keyOf]) always show as that series,
 * so a library entry keeps its identity whatever the provider order. Pairs a verification has rejected ([rejected])
 * are never grouped again.
 *
 * Two saved identities are shown as one result only on *strong* evidence (a shared database id, or the same title and
 * the same authors) with nothing contradicting it; [choose] then names the identity shown. A hit that matches two
 * results (one site's title is the other's alias, ids bridge two translations) joins them when the combined result
 * stays consistent: at most one hit per provider, and the same checks between every pair.
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
        /**
         * The identity shown for saved identities proven to be one work (null: keep them apart). Called with the keys
         * in result order; must be deterministic.
         */
        choose: (List<String>) -> String? = { null },
    ): List<SearchCluster> {
        val ordered = hits.distinctBy { it.sourceId to it.id }
            .sortedWith(compareBy<SourceSeries> { priority(it.sourceId) }.thenByDescending { relevance(it) })
        val clusters = mutableListOf<Group>()

        // 1. Hits already known to belong to a series.
        for (h in ordered) {
            val k = keyOf(h) ?: continue
            val g = clusters.firstOrNull { it.key == k }
            if (g != null) g.members += h else clusters += Group(mutableListOf(h), k)
        }
        // 1b. Saved identities that strong evidence shows to be one work ("Attack on Titan" saved from one site,
        //     "ATTACK ON TITAN" from another, both with the same AniList id): one result, shown as one of them.
        var i = 0
        while (i < clusters.size) {
            var j = i + 1
            while (j < clusters.size) {
                val a = clusters[i]
                val b = clusters[j]
                val all = (listOf(a.key!!) + a.aliases + b.key!! + b.aliases).distinct()
                val shown = if (mergeable(a.members, b.members, roleOf, rejected) && strongly(a.members, b.members)) choose(all) else null
                if (shown != null) {
                    clusters[i] = Group((a.members + b.members).toMutableList(), shown, (all - shown).toMutableSet())
                    clusters.removeAt(j)
                } else {
                    j++
                }
            }
            i++
        }
        // 2. Everything else joins a compatible result or starts its own.
        for (h in ordered) {
            if (keyOf(h) != null) continue
            val candidates = clusters.filter { c -> c.members.all { roleOf(it) == roleOf(h) } && compatible(c.members, h, rejected) }
            if (candidates.isNotEmpty()) {
                val target = candidates.first()
                target.members += h
                // [h] also matches other results: it bridges them (Sunrise / Daybreak, with both ids). Join them when
                // the combined result stays consistent; saved identities only on strong evidence through [h].
                for (other in candidates.drop(1)) {
                    if (!mergeable(target.members, other.members, roleOf, rejected)) continue
                    val tk = target.key
                    val ok = other.key
                    val identity: Pair<String?, Set<String>> = when {
                        ok == null -> tk to target.aliases
                        tk == null -> ok to other.aliases
                        // Two saved identities: only when [h] links strongly to both.
                        strongly(target.members.filter { it !== h }, listOf(h)) && strongly(other.members, listOf(h)) -> {
                            val all = (listOf(tk) + target.aliases + ok + other.aliases).distinct()
                            val shown = choose(all) ?: continue
                            shown to (all - shown).toSet()
                        }
                        else -> continue
                    }
                    target.members += other.members
                    other.members.clear()
                    target.key = identity.first
                    target.aliases = identity.second.toMutableSet()
                }
                clusters.removeAll { it.members.isEmpty() }
            } else if (clusters.any { c -> c.members.all { roleOf(it) == roleOf(h) } && copyOnSameSite(c.members, h, rejected) }) {
                // A site listing one series twice (MangaFire's "Golden Mage" ×2, Hitomi's chapters uploaded as galleries
                // of one name): the second entry is not shown. Opening the result links the site's best entry.
                continue
            } else if (canAnchor(h.sourceId)) {
                clusters += Group(mutableListOf(h), null)
            }
        }
        return clusters.map { g ->
            // The anchor is the first member a result may be headed by, in fixed provider order.
            val m = g.members.sortedBy { priority(it.sourceId) }
            val anchorFirst = m.firstOrNull { canAnchor(it.sourceId) }?.let { a -> listOf(a) + (m - a) } ?: m
            SearchCluster(anchorFirst, g.key, g.aliases.toSet())
        }.filter { c -> c.key != null || canAnchor(c.anchor.sourceId) }
    }

    private class Group(val members: MutableList<SourceSeries>, var key: String?, var aliases: MutableSet<String> = mutableSetOf())

    private fun compatible(cluster: List<SourceSeries>, h: SourceSeries, rejected: (SourceSeries, SourceSeries) -> Boolean): Boolean {
        if (cluster.any { it.sourceId == h.sourceId }) return false
        // "Example Story (Book Version)" lists the plain title as an alias: an alias never bridges editions.
        if (cluster.any { !sameEdition(it, h) }) return false
        // Authors in common with one member outweigh differently romanised names on another ("Hanjung Wolya" here,
        // "Han-Joong-Wueol-Ya" there): sites credit the same people under many spellings.
        val authorsAgree = cluster.any { sameAuthors(it, h) }
        if (cluster.any { contradicts(it, h, checkAuthors = !authorsAgree) || rejected(it, h) || rejected(h, it) }) return false
        return cluster.any { sameTitle(it, h) || it.ids.compare(h.ids) == 1 }
    }

    /** Two results may become one: distinct providers, same roles and editions, nothing contradictory or rejected. */
    private fun mergeable(a: List<SourceSeries>, b: List<SourceSeries>, roleOf: (SourceSeries) -> Any?, rejected: (SourceSeries, SourceSeries) -> Boolean): Boolean {
        val others = b.filter { y -> a.none { it === y } }
        if (others.isEmpty()) return true
        if (a.any { x -> others.any { it.sourceId == x.sourceId } }) return false
        val authorsAgree = a.any { x -> others.any { sameAuthors(x, it) } }
        return a.all { x ->
            others.all { y ->
                roleOf(x) == roleOf(y) && sameEdition(x, y) && !contradicts(x, y, checkAuthors = !authorsAgree) && !rejected(x, y) && !rejected(y, x)
            }
        }
    }

    /** Strong evidence that two groups hold one work: an agreeing database id, or the same title by the same authors. */
    private fun strongly(a: List<SourceSeries>, b: List<SourceSeries>): Boolean =
        a.any { x -> b.any { y -> x.ids.compare(y.ids) == 1 || (sameTitle(x, y) && sameAuthors(x, y)) } }

    /** The primary titles name the same edition ("Book Version", "Colored", "Season 2", "Novel" ... in neither or both). */
    fun sameEdition(a: SourceSeries, b: SourceSeries): Boolean = editionOf(a.title) == editionOf(b.title)

    private fun editionOf(title: String): Set<String> = TitleNormalizer.qualifiers(title) - NEUTRAL

    private val NEUTRAL = setOf("official", "raw")

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
