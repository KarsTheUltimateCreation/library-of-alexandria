package alexandria.core.completeness

import alexandria.core.model.PublicationStatus
import alexandria.core.normalization.ChapterKind
import alexandria.core.normalization.ChapterNumber
import alexandria.core.reconciliation.CanonicalChapter
import alexandria.core.reconciliation.ReconcileResult

enum class GapType {
    /** The chapter exists (another provider has it) but this provider lacks it. Recovered automatically. */
    CONFIRMED_MISSING,

    /** A numbering gap no provider fills. It may be skipped numbering, a merged chapter, or truly missing. */
    POSSIBLE_MISSING,

    /** Original publication has more chapters than any translation available. */
    UNTRANSLATED,

    /** Providers number the same installments differently. */
    NUMBERING_DIFFERENCE,
}

data class Gap(val type: GapType, val language: String, val season: Int?, val from: Long, val to: Long, val note: String? = null) {
    fun describe(): String {
        val range = if (from == to) "$from" else "$from–$to"
        val s = season?.let { "S$it " } ?: ""
        return when (type) {
            GapType.CONFIRMED_MISSING -> "${s}ch. $range missing from ${note ?: "a source"}; recovered from another source"
            GapType.POSSIBLE_MISSING -> "${s}ch. $range not found on any enabled source"
            GapType.UNTRANSLATED -> "${s}ch. $range published originally; no ${language.uppercase()} release found (likely untranslated)"
            GapType.NUMBERING_DIFFERENCE -> note ?: "numbering differs between sources"
        }
    }
}

data class CompletenessReport(
    val gaps: List<Gap>,
    /** Canonical chapters that the primary (most complete) source lacks, recovered from others: sourceId -> count. */
    val recoveredBySource: Map<String, Int>,
    val primarySource: String?,
    val totalCanonical: Int,
    val metadataTotal: String?,
) {
    fun summaryLines(maxGaps: Int = 6): List<String> {
        val lines = mutableListOf<String>()
        val recovered = recoveredBySource.values.sum()
        if (primarySource != null && recovered > 0) {
            lines += "$recovered chapter(s) not on $primarySource were filled in from other sources."
        }
        val possible = gaps.filter { it.type == GapType.POSSIBLE_MISSING }
        if (possible.isNotEmpty()) {
            lines += "Possible gaps: " + possible.take(maxGaps).joinToString(", ") { g ->
                val s = g.season?.let { "S$it " } ?: ""
                if (g.from == g.to) "$s${g.from}" else "$s${g.from}–${g.to}"
            } + if (possible.size > maxGaps) " …" else ""
        }
        gaps.filter { it.type == GapType.UNTRANSLATED || it.type == GapType.NUMBERING_DIFFERENCE }.forEach { lines += it.describe() }
        return lines
    }
}

/**
 * Detects gaps in the canonical chapter list. It never fabricates chapters: it only classifies gaps.
 *
 * - A whole number n counts as present if chapter n exists, or a split of it ("n part 1", "n.1"),
 *   so split chapters are not reported as gaps. A lone "n.5" does not prove chapter n exists.
 * - Gaps are only computed inside the observed range; chapters after the highest available one are compared
 *   against the original-publication total from metadata and reported as *likely untranslated*, never missing,
 *   because publication counts and translated releases legitimately differ (split counting, extras, delays).
 */
class CompletenessAnalyzer {

    fun analyze(
        result: ReconcileResult,
        metadataTotalChapters: String?,
        status: PublicationStatus,
        primaryLanguage: String?,
    ): CompletenessReport {
        val chapters = result.chapters
        val gaps = mutableListOf<Gap>()

        // Which source covers the most canonical chapters: the de-facto "primary" the user would have used alone.
        val coverage = chapters.flatMap { c -> c.sources.map { it to c.key } }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet().size }
        val primary = coverage.maxByOrNull { it.value }?.key
        val recovered = mutableMapOf<String, Int>()
        if (primary != null) {
            val missingFromPrimary = chapters.filter { primary !in it.sources }
            missingFromPrimary.forEach { c -> c.sources.firstOrNull()?.let { recovered.merge(it, 1, Int::plus) } }
            // Confirmed gaps: whole numbers inside the primary's own range that it lacks but others have.
            mains(chapters).forEach { (lang, season, list) ->
                val primaryWholes = list.filter { primary in it.sources }.mapNotNull { wholeCovered(it) }.toSortedSet()
                if (primaryWholes.isEmpty()) return@forEach
                val others = list.filter { primary !in it.sources }.mapNotNull { wholeCovered(it) }
                    .filter { it in primaryWholes.first()..primaryWholes.last() && it !in primaryWholes }
                    .toSortedSet()
                compress(others).forEach { (a, b) -> gaps += Gap(GapType.CONFIRMED_MISSING, lang, season, a, b, primary) }
            }
        }

        mains(chapters).forEach { (lang, season, list) ->
            val wholes = list.mapNotNull { wholeCovered(it) }.toSortedSet()
            if (wholes.size < 2) return@forEach
            val start = maxOf(wholes.first(), if (wholes.first() == 0L) 0L else 1L)
            val absent = (start..wholes.last()).filter { it !in wholes }
            // A huge hole usually means a numbering scheme change, not hundreds of missing chapters.
            if (absent.size > maxOf(25, wholes.size)) {
                gaps += Gap(GapType.NUMBERING_DIFFERENCE, lang, season, wholes.first(), wholes.last(), "Large numbering jumps; chapter numbers may not be continuous")
            } else {
                compress(absent.toSortedSet()).forEach { (a, b) -> gaps += Gap(GapType.POSSIBLE_MISSING, lang, season, a, b) }
            }
        }

        // Compare against the original publication count (only for un-seasoned main numbering in the primary language).
        val total = metadataTotalChapters?.let { ChapterNumber.parse(it) }?.whole
        if (total != null && total > 0) {
            val lang = primaryLanguage ?: chapters.firstOrNull()?.language
            val wholes = chapters.filter { it.language == lang && it.identity.kind == ChapterKind.MAIN && it.identity.season == null }
                .mapNotNull { it.identity.number?.whole }
            val maxAvailable = wholes.maxOrNull()
            if (maxAvailable != null && lang != null) {
                // Series that start at chapter 0 have max+1 installments; compare counts, not labels.
                val installments = if (wholes.min() == 0L) maxAvailable + 1 else maxAvailable
                when {
                    total > installments -> gaps += Gap(GapType.UNTRANSLATED, lang, null, maxAvailable + 1, maxAvailable + (total - installments))
                    maxAvailable > total + maxOf(5, total / 10) && status == PublicationStatus.COMPLETED ->
                        gaps += Gap(GapType.NUMBERING_DIFFERENCE, lang, null, total, maxAvailable, "Sources list up to ch. $maxAvailable but the original run has $total; numbering likely differs (split chapters or extras)")
                }
            }
        }

        result.numberingOffsets.forEach { (source, other, offset) ->
            gaps += Gap(GapType.NUMBERING_DIFFERENCE, "", null, 0, 0, "$source numbers chapters ${if (offset > 0) "+" else ""}$offset relative to $other; its releases are only used as fallback")
        }
        if (result.volumeResetSources.isNotEmpty()) {
            gaps += Gap(GapType.NUMBERING_DIFFERENCE, "", null, 0, 0, "${result.volumeResetSources.joinToString()} restart(s) numbering each volume; volume is kept in those chapter identities")
        }

        return CompletenessReport(gaps, recovered, primary, chapters.size, metadataTotalChapters)
    }

    /** The whole chapter number this record proves exists, if any. */
    private fun wholeCovered(c: CanonicalChapter): Long? {
        val n = c.identity.number ?: return null
        return when {
            n.fraction == null -> n.whole
            c.identity.part != null -> n.whole
            n.fraction == "1" -> n.whole // "10.1" is the first piece of a split chapter 10
            else -> null
        }
    }

    private fun mains(chapters: List<CanonicalChapter>): List<Triple<String, Int?, List<CanonicalChapter>>> =
        chapters.filter { it.identity.kind == ChapterKind.MAIN && it.identity.number != null }
            .groupBy { it.language to it.identity.season }
            .map { (k, v) -> Triple(k.first, k.second, v) }

    private fun compress(values: Set<Long>): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var start: Long? = null
        var prev: Long? = null
        for (v in values.sorted()) {
            if (start == null) { start = v; prev = v; continue }
            if (v == prev!! + 1) { prev = v } else { out += start to prev; start = v; prev = v }
        }
        if (start != null) out += start to prev!!
        return out
    }
}
