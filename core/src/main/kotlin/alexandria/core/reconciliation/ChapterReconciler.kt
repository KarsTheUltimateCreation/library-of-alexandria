package alexandria.core.reconciliation

import alexandria.core.config.AggregatorSettings
import alexandria.core.model.SourceChapter
import alexandria.core.normalization.ChapterIdentity
import alexandria.core.normalization.ChapterKind
import alexandria.core.normalization.ChapterLabelParser
import alexandria.core.normalization.LabelFlag
import alexandria.core.normalization.ParsedLabel
import alexandria.core.normalization.TitleNormalizer
import kotlinx.serialization.Serializable

/** One provider's release of a canonical chapter. All releases are retained for quality selection and fallback. */
@Serializable
data class Release(
    val sourceId: String,
    val seriesId: String,
    val chapterId: String,
    val label: String,
    val language: String,
    val group: String? = null,
    val official: Boolean? = null,
    val uploadedAt: Long? = null,
    /** Confidence that this release is the same chapter as the canonical record (0..1). */
    val confidence: Double = 1.0,
    val webUrl: String? = null,
    val externalOnly: Boolean = false,
)

@Serializable
data class CanonicalChapter(
    /** Stable key, e.g. "en:c:10.5". Used as the host-visible chapter URL suffix, so it must not change across refreshes. */
    val key: String,
    val language: String,
    val identity: ChapterIdentity,
    val title: String?,
    val releases: List<Release>,
    val uncertain: Boolean = false,
) {
    val sources: List<String> get() = releases.map { it.sourceId }.distinct()
    val earliestUpload: Long? get() = releases.mapNotNull { it.uploadedAt }.minOrNull()
}

data class ReconcileResult(
    /** Sorted ascending (oldest first). */
    val chapters: List<CanonicalChapter>,
    /** Provider ids detected as restarting chapter numbers each volume. */
    val volumeResetSources: Set<String>,
    /** Provider pairs whose numbering is offset from each other: (source, otherSource, offset). */
    val numberingOffsets: List<Triple<String, String, Int>>,
    val inputCount: Int,
)

/**
 * Groups provider releases into canonical chapters.
 *
 *  1. Parse every label into a structured [ChapterIdentity].
 *  2. Detect providers that restart numbering per volume and include the volume in *their* keys.
 *  3. Key = language + identity key (+ group when alternative translations are shown).
 *  4. Group equal keys; every release is preserved in [CanonicalChapter.releases].
 *  5. Flag a group as uncertain when its releases carry clearly different titles.
 * With deduplication disabled, each release becomes its own entry but still carries the other releases
 * of the same key as fallback alternatives.
 */
class ChapterReconciler {

    private data class Parsed(val chapter: SourceChapter, val label: ParsedLabel, val language: String)

    fun reconcile(bySource: Map<String, List<SourceChapter>>, settings: AggregatorSettings): ReconcileResult {
        val parsed = bySource.mapValues { (_, list) ->
            val raw = list.map { c ->
                val p = ChapterLabelParser.parse(c.label, c.number, c.volume)
                val lang = (p.languageHint ?: c.language).lowercase()
                Parsed(c.copy(title = c.title ?: p.title), p, lang)
            }
            if (seasonIsDecorative(raw)) raw.map { it.copy(label = it.label.copy(identity = it.label.identity.copy(season = null))) } else raw
        }

        val volumeReset = parsed.filter { (_, list) -> restartsPerVolume(list) }.keys
        val offsets = detectNumberingOffsets(parsed)
        val offsetSources = offsets.map { it.first }.toSet()

        val accepted = parsed.values.flatten()
            .filter { settings.acceptsLanguage(it.language) }
            .filter { settings.includeSpecials || it.label.identity.kind !in SPECIAL_KINDS }
            .filter { LabelFlag.PREVIEW !in it.label.flags }

        fun baseKey(p: Parsed): String =
            p.language + ":" + p.label.identity.key(includeVolume = p.chapter.sourceId in volumeReset)

        val groups = LinkedHashMap<String, MutableList<Parsed>>()
        for (p in accepted) {
            var key = baseKey(p)
            if (settings.showAlternativeTranslations && p.chapter.group != null) {
                key += "@" + TitleNormalizer.compact(p.chapter.group)
            }
            groups.getOrPut(key) { mutableListOf() }.add(p)
        }

        val chapters = mutableListOf<CanonicalChapter>()
        for ((key, members) in groups) {
            val titles = members.mapNotNull { it.chapter.title?.takeIf { t -> t.isNotBlank() } }.distinct()
            val titleConflict = titlesConflict(titles)
            val releases = members.map { m ->
                var confidence = 1.0
                if (titleConflict) confidence -= 0.3
                if (m.chapter.sourceId in offsetSources) confidence -= 0.3
                if (m.label.identity.kind == ChapterKind.UNKNOWN) confidence -= 0.1
                Release(
                    sourceId = m.chapter.sourceId,
                    seriesId = m.chapter.seriesId,
                    chapterId = m.chapter.id,
                    label = m.chapter.label,
                    language = m.language,
                    group = m.chapter.group,
                    official = m.chapter.official ?: when {
                        LabelFlag.OFFICIAL in m.label.flags -> true
                        LabelFlag.FAN in m.label.flags -> false
                        else -> null
                    },
                    uploadedAt = m.chapter.uploadedAt,
                    confidence = confidence.coerceIn(0.1, 1.0),
                    webUrl = m.chapter.webUrl,
                    externalOnly = m.chapter.externalOnly,
                )
            }
            val identity = members.first().label.identity
            val title = titles.firstOrNull()

            if (settings.deduplicate) {
                chapters += CanonicalChapter(key, members.first().language, identity, title, releases, uncertain = titleConflict)
            } else {
                // One entry per release; the others stay attached as fallback alternatives.
                releases.forEachIndexed { i, r ->
                    val ordered = listOf(r) + releases.filterIndexed { j, _ -> j != i }
                    chapters += CanonicalChapter(
                        key = key + "#" + r.sourceId + ":" + r.chapterId.hashCode().toUInt().toString(36),
                        language = r.language,
                        identity = identity,
                        title = members[i].chapter.title,
                        releases = ordered,
                        uncertain = titleConflict,
                    )
                }
            }
        }

        // Drop canonical chapters that only exist as external links (nothing readable) when a readable copy is absent.
        val readable = chapters.filter { c -> c.releases.any { !it.externalOnly } }

        return ReconcileResult(
            chapters = readable.sortedWith(CHAPTER_ORDER),
            volumeResetSources = volumeReset,
            numberingOffsets = offsets,
            inputCount = bySource.values.sumOf { it.size },
        )
    }

    /**
     * True when a provider's seasons do not restart the numbering, so the season adds nothing to the chapter's
     * identity: "S1 Chapter 25" followed by "S2 Chapter 26", or every chapter labelled season 1. Such chapters must
     * merge with other providers' plain "Chapter 25" instead of being listed twice.
     */
    private fun seasonIsDecorative(list: List<Parsed>): Boolean {
        val mains = list.filter { it.label.identity.kind == ChapterKind.MAIN && it.label.identity.number != null }
        val seasoned = mains.filter { it.label.identity.season != null }
        if (seasoned.isEmpty()) return false
        // The same number under two seasons (or under a season and none) means numbering restarts: keep seasons.
        val seasonsPerNumber = mains.groupBy { it.label.identity.copy(season = null, volume = null, text = null).key() }
            .values.map { group -> group.map { it.label.identity.season }.distinct().size }
        if (seasonsPerNumber.any { it > 1 }) return false
        val bySeason = seasoned.groupBy { it.label.identity.season!! }.toSortedMap()
        if (bySeason.size == 1) return bySeason.firstKey() == 1
        // Seasons must follow each other in number order: every season starts after the previous one ends.
        val ranges = bySeason.values.map { g -> g.minOf { it.label.identity.number!! } to g.maxOf { it.label.identity.number!! } }
        return ranges.zipWithNext().all { (a, b) -> b.first > a.second }
    }

    /** A provider restarts numbering per volume when the same main number repeats under different volumes. */
    private fun restartsPerVolume(list: List<Parsed>): Boolean {
        val mains = list.filter { it.label.identity.kind == ChapterKind.MAIN && it.label.identity.number != null }
        val byNumber = mains.groupBy { it.label.identity.copy(volume = null, text = null).key() + "|" + it.language + "|" + (it.chapter.group ?: "") }
        val repeats = byNumber.values.count { group -> group.mapNotNull { it.label.identity.volume }.distinct().size >= 2 }
        return repeats >= 3 && repeats * 4 >= byNumber.size.coerceAtLeast(1)
    }

    /**
     * Compares chapter titles between providers. If titles line up consistently at a non-zero offset
     * (provider B's chapter n+1 has the title of provider A's chapter n), the providers number differently.
     * Such releases are kept but marked low-confidence so they serve only as failure fallback.
     */
    private fun detectNumberingOffsets(parsed: Map<String, List<Parsed>>): List<Triple<String, String, Int>> {
        val titled = parsed.mapValues { (_, list) ->
            list.filter { it.label.identity.kind == ChapterKind.MAIN && it.label.identity.part == null }
                .mapNotNull { p ->
                    val n = p.label.identity.number ?: return@mapNotNull null
                    if (n.fraction != null) return@mapNotNull null
                    val t = p.chapter.title?.let { TitleNormalizer.compact(it) }?.takeIf { it.length >= 4 } ?: return@mapNotNull null
                    Triple(p.label.identity.season, n.whole, t)
                }
        }
        val result = mutableListOf<Triple<String, String, Int>>()
        val ids = titled.keys.sorted()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = titled.getValue(ids[i])
            val b = titled.getValue(ids[j])
            if (a.isEmpty() || b.isEmpty()) continue
            val aIndex = a.groupBy { it.third }
            val votes = mutableMapOf<Int, Int>()
            for ((season, whole, t) in b) {
                aIndex[t]?.filter { it.first == season }?.forEach { other -> votes.merge((whole - other.second).toInt(), 1, Int::plus) }
            }
            val aligned = votes[0] ?: 0
            val best = votes.filterKeys { it != 0 }.maxByOrNull { it.value } ?: continue
            if (best.value >= 3 && best.value > aligned * 2) {
                result += Triple(ids[j], ids[i], best.key)
            }
        }
        // Which side of a pair is "offset" must not depend on id order: the outlier is the provider that disagrees
        // with more of the others. (Before 1.6.6 the alphabetically later id was demoted, so one new provider with
        // unusual numbering could demote several well-aligned ones.) Ties keep the previous choice.
        val involvement = result.flatMap { listOf(it.first, it.second) }.groupingBy { it }.eachCount()
        return result.map { (a, b, offset) ->
            if ((involvement[b] ?: 0) > (involvement[a] ?: 0)) Triple(b, a, -offset) else Triple(a, b, offset)
        }
    }

    private fun titlesConflict(titles: List<String>): Boolean {
        if (titles.size < 2) return false
        val generic = Regex("""^(chapter|ch|episode|ep)?\s*[\d.]+$""", RegexOption.IGNORE_CASE)
        val meaningful = titles.filterNot { generic.matches(it.trim()) }
        if (meaningful.size < 2) return false
        for (i in meaningful.indices) for (j in i + 1 until meaningful.size) {
            if (TitleNormalizer.similarity(meaningful[i], meaningful[j]) < 0.35) return true
        }
        return false
    }

    companion object {
        val SPECIAL_KINDS = setOf(ChapterKind.EXTRA, ChapterKind.SIDE_STORY, ChapterKind.SPECIAL, ChapterKind.NOTICE, ChapterKind.ONESHOT)

        /** Reading order: season, then number (prologue before 1, extras after their anchor), then part. */
        val CHAPTER_ORDER: Comparator<CanonicalChapter> = Comparator { a, b ->
            val ia = a.identity
            val ib = b.identity
            compareValuesBy(ia, ib, { it.season ?: 0 }).takeIf { it != 0 }
                ?: compareAnchors(a, b).takeIf { it != 0 }
                ?: compareValuesBy(ia, ib, { kindOrder(it.kind) }, { it.part ?: 0 }).takeIf { it != 0 }
                ?: compareValues(a.earliestUpload ?: Long.MAX_VALUE, b.earliestUpload ?: Long.MAX_VALUE).takeIf { it != 0 }
                ?: a.key.compareTo(b.key)
        }

        private fun compareAnchors(a: CanonicalChapter, b: CanonicalChapter): Int {
            val na = a.identity.number
            val nb = b.identity.number
            return when {
                na != null && nb != null -> na.compareTo(nb)
                na == null && nb == null -> anchorlessRank(a.identity.kind).compareTo(anchorlessRank(b.identity.kind))
                na == null -> if (a.identity.kind == ChapterKind.PROLOGUE) -1 else if (anchorlessRank(a.identity.kind) > 0) 1 else -1
                else -> if (b.identity.kind == ChapterKind.PROLOGUE) 1 else if (anchorlessRank(b.identity.kind) > 0) -1 else 1
            }
        }

        private fun anchorlessRank(kind: ChapterKind) = when (kind) {
            ChapterKind.PROLOGUE -> -1
            ChapterKind.ONESHOT -> 0
            else -> 1
        }

        private fun kindOrder(kind: ChapterKind) = when (kind) {
            ChapterKind.PROLOGUE -> 0
            ChapterKind.MAIN -> 1
            ChapterKind.EXTRA, ChapterKind.SPECIAL, ChapterKind.SIDE_STORY -> 2
            ChapterKind.EPILOGUE -> 3
            else -> 4
        }
    }
}
