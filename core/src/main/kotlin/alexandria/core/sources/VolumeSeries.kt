package alexandria.core.sources

import alexandria.core.model.CoverCandidate
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.normalization.TitleNormalizer
import alexandria.core.sources.adapters.Galleries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections

/**
 * Numbered parts published as separate entries ("Imouto wa Mesu Orc", "… 2", "… 3": doujinshi series on gallery sites
 * and Manga Ball's "[H]" titles) folded into one series whose chapters are the parts.
 *
 * Grouping is by title alone: an entry's titles (every alias, bracket tags removed) that end in a number 1–99 give a
 * *base* and a part number; entries sharing a base form one series when at least two different part numbers are
 * present. An unnumbered entry whose title is a base joins as part 1. Entries with the same number (a decensored
 * re-upload) become two releases of that part.
 *
 * The series id is `vol~<base words>`, enough to find the parts again with a search of the base title (new parts are
 * picked up that way on refresh). Which base names a group can change as parts appear ("Bright Dawn 1–2" with
 * "Akatsuki" aliases, then "Akatsuki 3"), so [CompositeIdentities] remembers every base a composite id was seen with:
 * a later group sharing one of those bases keeps the id it was first given (library entries stay attached).
 */
object VolumeSeries {
    const val PREFIX = "vol~"

    fun isComposite(id: String) = id.startsWith(PREFIX)

    /** Search query that finds the parts of a composite id. */
    fun query(id: String) = id.removePrefix(PREFIX).replace('-', ' ')

    private val NUMBERED = Regex("""^(.*?\S)[\s\-–:#,]*(?:(?:vol(?:ume)?|part|ch(?:apter)?|no)\.?\s*)?(\d{1,2})$""", RegexOption.IGNORE_CASE)
    private val YEARISH = Regex("""\b(?:19|20)\d\d$""")

    /** Titles of an entry with gallery tags removed; "A | B" counts as two titles. */
    internal fun titles(s: SourceSeries): List<String> = s.allTitles()
        .flatMap { it.split(" | ") }
        .map { Galleries.cleanTitle(it) }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

    /** (base, number) of a numbered title. */
    internal fun numbered(title: String): Pair<String, Int>? {
        if (YEARISH.containsMatchIn(title)) return null
        val m = NUMBERED.matchEntire(title.trim()) ?: return null
        val base = m.groupValues[1].trim()
        val n = m.groupValues[2].toInt()
        // "Re:Zero 2" is not part 2 of "Re"; a base needs some substance. A number glued to a word or to more digits
        // ("Orc7", "Title 105") is part of the title.
        val t = title.trim()
        if (n !in 1..99 || TitleNormalizer.compact(base).length < 4 || t[t.length - m.groupValues[2].length - 1].isLetterOrDigit()) return null
        return base to n
    }

    private fun slug(base: String): String = TitleNormalizer.normalize(base).split(' ').filter { w -> w.isNotEmpty() && w.all { it in 'a'..'z' || it in '0'..'9' } }.joinToString("-")

    /** A group of parts on one provider. */
    class Group(val series: SourceSeries, val parts: List<Pair<SourceSeries, Int>>, val bases: Set<String>)

    /**
     * Folds the numbered parts in [hits] into composite entries (in the position of their first part); other hits are
     * returned unchanged. Only hits accepted by [eligible] are considered.
     */
    fun group(
        hits: List<SourceSeries>,
        /** The id a group with these bases was given before, if any. */
        existing: (Set<String>) -> String? = { null },
        eligible: (SourceSeries) -> Boolean,
    ): Pair<List<SourceSeries>, List<Group>> {
        class Info(val s: SourceSeries, val number: Int?, val bases: Map<String, String>, val bare: Set<String>)
        val infos = hits.filter(eligible).map { s ->
            val ts = titles(s)
            val nums = ts.mapNotNull { numbered(it) }
            // The number most titles agree on ("Orc 4" and "Mesu Orc 4"); conflicting aliases are ignored.
            val n = nums.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key
            val bases = nums.filter { it.second == n }.associate { TitleNormalizer.compact(it.first) to it.first }
            Info(s, n, bases, if (n == null) ts.map { TitleNormalizer.compact(it) }.filter { it.length >= 4 }.toSet() else emptySet())
        }
        val numberedInfos = infos.filter { it.number != null }
        // One numbered part can still pair with an unnumbered first part ("Title" + "Title 7").
        if (numberedInfos.isEmpty()) return hits to emptyList()

        // Union numbered entries that share a base.
        val parent = IntArray(numberedInfos.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        val byBase = HashMap<String, Int>()
        numberedInfos.forEachIndexed { i, inf -> inf.bases.keys.forEach { b -> byBase[b]?.let { j -> parent[find(i)] = find(j) } ?: run { byBase[b] = i } } }
        val clusters = numberedInfos.indices.groupBy { find(it) }.values.map { idx -> idx.map { numberedInfos[it] } }
        val groups = mutableListOf<Group>()
        val used = Collections.newSetFromMap(java.util.IdentityHashMap<SourceSeries, Boolean>())
        for (members in clusters) {
            val baseKeys = members.flatMap { it.bases.keys }.toSet()
            val bareParts = infos.filter { it.number == null && it.s !in used && it.bare.any { b -> b in baseKeys } }
            val parts = members.map { it.s to it.number!! } + bareParts.map { it.s to 1 }
            if (parts.map { it.second }.distinct().size < 2) continue
            // The base most parts share (an unnumbered first part counts for the bases its titles equal) names the
            // series and its id; a base without Latin letters cannot be an id.
            val display0 = members.flatMap { it.bases.entries }.associate { it.key to it.value }
            val support = display0.keys.associateWith { k -> members.count { k in it.bases } + bareParts.count { k in it.bare } }
            val idBase = support.entries.filter { slug(display0.getValue(it.key)).replace("-", "").length >= 4 }
                .maxByOrNull { it.value }?.let { display0.getValue(it.key) } ?: continue
            val id = existing(baseKeys)?.takeIf { isComposite(it) && groups.none { g -> g.series.id == it } } ?: (PREFIX + slug(idBase))
            if (id.length > 200 || groups.any { it.series.id == id }) continue
            val sorted = parts.sortedBy { it.second }
            val first = sorted.first().first
            // Display title: the base of the first part's own title ("[H] Imouto Wa Mesu Orc 2" -> "Imouto Wa Mesu Orc").
            val display = titles(first).firstNotNullOfOrNull { t -> numbered(t)?.first?.takeIf { TitleNormalizer.compact(it) in baseKeys } }
                ?: Galleries.cleanTitle(first.title).takeIf { TitleNormalizer.compact(it) in baseKeys }
                ?: idBase
            val alts = (members.flatMap { it.bases.values } + bareParts.flatMap { titles(it.s) }).distinctBy { TitleNormalizer.compact(it) }
                .filter { TitleNormalizer.compact(it) != TitleNormalizer.compact(display) }
            val composite = first.copy(
                id = id,
                title = display,
                altTitles = alts,
                coverUrl = sorted.firstNotNullOfOrNull { it.first.coverUrl },
                authors = parts.flatMap { it.first.authors }.distinct(),
                genres = parts.flatMap { it.first.genres }.distinct(),
                related = parts.flatMap { it.first.related }.distinctBy { it.lowercase() }.take(12),
                year = parts.mapNotNull { it.first.year }.minOrNull(),
                description = null,
                webUrl = first.webUrl,
                detailed = true,
            )
            groups += Group(composite, sorted, baseKeys)
            used += parts.map { it.first }
        }
        if (groups.isEmpty()) return hits to emptyList()
        val out = mutableListOf<SourceSeries>()
        val placed = mutableSetOf<String>()
        for (h in hits) {
            val g = groups.firstOrNull { g -> g.parts.any { it.first === h } }
            if (g == null) out += h else if (placed.add(g.series.id)) out += g.series
        }
        return out to groups
    }

    /** Chapters of one part, numbered by part: a single chapter becomes chapter n, several become n.1, n.2, … */
    fun partChapters(part: SourceSeries, n: Int, chapters: List<SourceChapter>): List<SourceChapter> {
        val name = Galleries.cleanTitle(part.title)
        if (chapters.size == 1) {
            return listOf(chapters[0].copy(number = n.toString(), volume = null, label = "Chapter $n", title = name))
        }
        val ordered = chapters.sortedBy { it.number?.toDoubleOrNull() ?: Double.MAX_VALUE }
        return ordered.mapIndexed { k, c -> c.copy(number = "$n.${k + 1}", volume = null, label = "Chapter $n.${k + 1}", title = listOfNotNull(name, c.title).joinToString(" - ")) }
    }
}

/**
 * Wraps a provider so numbered parts appear as one series ([VolumeSeries]). Everything else is passed through; chapters
 * keep their part's own ids, so reading goes straight to the wrapped provider.
 */
class VolumeGroupingAdapter(
    private val inner: SourceAdapter,
    /** Which hits may be grouped (Manga Ball: only its "[H]" titles). */
    private val eligible: (SourceSeries) -> Boolean = { true },
) : SourceAdapter by inner {

    /** Composite ids given so far, by base; the engine replaces it with a persisted one. */
    @Volatile var identities: CompositeIdentities = CompositeIdentities.InMemory()

    private fun groupHits(hits: List<SourceSeries>): Pair<List<SourceSeries>, List<VolumeSeries.Group>> {
        val result = VolumeSeries.group(hits, existing = { bases -> identities.lookup(id, bases) }, eligible = eligible)
        result.second.forEach { g -> identities.record(id, g.series.id, g.bases) }
        return result
    }

    /** Parts of composites seen recently (search results), so opening one needs no second search. */
    private val known: MutableMap<String, List<Pair<SourceSeries, Int>>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<Pair<SourceSeries, Int>>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<SourceSeries, Int>>>?) = size > 200
        },
    )

    private fun fold(hits: List<SourceSeries>): List<SourceSeries> {
        val (out, groups) = groupHits(hits)
        groups.forEach { g -> known[g.series.id] = merge(known[g.series.id], g.parts) }
        return out
    }

    private fun merge(old: List<Pair<SourceSeries, Int>>?, new: List<Pair<SourceSeries, Int>>) =
        ((old ?: emptyList()) + new).distinctBy { it.first.id }.sortedBy { it.second }

    override suspend fun search(query: String): List<SourceSeries> = fold(inner.search(query))
    override suspend fun popular(page: Int): List<SourceSeries> = fold(inner.popular(page))
    override suspend fun latest(page: Int): List<SourceSeries> = fold(inner.latest(page))

    /** The composite and its parts, searching the base title again when [refresh] or nothing is known. */
    private suspend fun parts(id: String, refresh: Boolean): Pair<SourceSeries?, List<Pair<SourceSeries, Int>>> {
        val cached = known[id]
        if (cached != null && !refresh) return null to cached
        val found = try {
            // By base title, not by id: the parts found now may name the group differently than when it was first seen.
            val base = TitleNormalizer.compact(VolumeSeries.query(id))
            groupHits(inner.search(VolumeSeries.query(id))).second
                .sortedByDescending { it.series.id == id }.firstOrNull { it.series.id == id || base in it.bases }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cached == null) throw e else null
        }
        val all = merge(cached, found?.parts.orEmpty())
        if (all.isNotEmpty()) known[id] = all
        return found?.series to all
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (!VolumeSeries.isComposite(series.id)) return inner.details(series)
        if (series.detailed && known.containsKey(series.id)) return series
        val (fresh, _) = parts(series.id, refresh = false)
        return (fresh?.copy(id = series.id) ?: series).copy(detailed = true)
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        if (!VolumeSeries.isComposite(series.id)) return inner.chapters(series, languages)
        // Refreshing the chapter list is when new parts are looked for.
        val (_, parts) = parts(series.id, refresh = true)
        check(parts.isNotEmpty()) { "no parts found for ${series.id}" }
        val gate = Semaphore(3)
        return coroutineScope {
            parts.map { (part, n) ->
                async {
                    val list = try {
                        gate.withPermit { inner.chapters(part, languages) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One failing part must not hide the others; the chapter list keeps its last known copy of it.
                        if (parts.size == 1) throw e else emptyList()
                    }
                    VolumeSeries.partChapters(part, n, list)
                }
            }.awaitAll().flatten()
        }
    }

    override suspend fun covers(series: SourceSeries): List<CoverCandidate> {
        if (!VolumeSeries.isComposite(series.id)) return inner.covers(series)
        return inner.covers(known[series.id]?.firstOrNull()?.first ?: series)
    }

    override fun seriesWebUrl(seriesId: String): String {
        if (!VolumeSeries.isComposite(seriesId)) return inner.seriesWebUrl(seriesId)
        return known[seriesId]?.firstOrNull()?.first?.let { it.webUrl ?: inner.seriesWebUrl(it.id) } ?: inner.baseUrl
    }
}

/**
 * Composite ids by provider and title base ([VolumeSeries.Group.bases]). A base keeps the first id recorded for it;
 * lookups pick the id most of a group's bases point to (ties: the earliest-sorting id), so the choice is stable.
 */
interface CompositeIdentities {
    fun lookup(sourceId: String, bases: Set<String>): String?
    fun record(sourceId: String, id: String, bases: Set<String>)

    /** Bounded in-memory map (tests, and until the engine attaches a persisted one). */
    class InMemory(private val capacity: Int = 2000) : CompositeIdentities {
        private val map = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
            },
        )

        override fun lookup(sourceId: String, bases: Set<String>): String? = choose(bases.mapNotNull { map["$sourceId:$it"] })

        override fun record(sourceId: String, id: String, bases: Set<String>) {
            bases.forEach { b -> map.putIfAbsent("$sourceId:$b", id) }
        }
    }

    companion object {
        fun choose(ids: List<String>): String? =
            ids.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key
    }
}
