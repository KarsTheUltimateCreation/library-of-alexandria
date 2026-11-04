package alexandria.core.identity

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.SeriesMetadata
import alexandria.core.model.SourceSeries
import alexandria.core.normalization.TitleNormalizer

enum class MatchDecision {
    /** Safe to merge automatically. */
    CONFIRMED,

    /** Plausible but not certain: never merged automatically. */
    AMBIGUOUS,
    REJECTED,
}

data class MatchResult(
    val score: Double,
    val decision: MatchDecision,
    val reasons: List<String>,
)

/**
 * Decides whether a provider's series is the same work as a reference series.
 *
 * Signals, strongest first: shared database identifiers, normalized title equality across every known
 * title variant (English / romanized / native / alternatives), edition qualifiers ("Novel",
 * "Volume Version"), format, publication year and status. Title similarity alone is never enough to merge
 * when the candidate carries a qualifier the reference lacks, or when identifiers disagree.
 */
class IdentityResolver(
    private val confirmThreshold: Double = 0.90,
    private val ambiguousThreshold: Double = 0.72,
) {

    fun match(reference: SeriesMetadata, candidate: SourceSeries): MatchResult {
        val reasons = mutableListOf<String>()
        val refTitles = reference.allTitles()
        val candTitles = candidate.allTitles()

        when (reference.ids.compare(candidate.ids)) {
            1 -> {
                // An identifier match is decisive unless the formats are clearly incompatible.
                if (kindsConflict(reference.kind, candidate.kind)) {
                    return MatchResult(0.0, MatchDecision.REJECTED, listOf("ids agree but format conflicts (${reference.kind} vs ${candidate.kind})"))
                }
                return MatchResult(1.0, MatchDecision.CONFIRMED, listOf(ID_REASON))
            }
            -1 -> return MatchResult(0.0, MatchDecision.REJECTED, listOf("database ids disagree"))
        }

        if (kindsConflict(reference.kind, candidate.kind)) {
            return MatchResult(0.0, MatchDecision.REJECTED, listOf("format conflict (${reference.kind} vs ${candidate.kind})"))
        }

        val (titleScore, refT, candT) = TitleNormalizer.bestMatch(refTitles, candTitles)
        var score = titleScore
        reasons += "title %.2f ('%s' ~ '%s')".format(titleScore, refT, candT)

        // A qualifier on the candidate's *primary* title that no reference title carries means another edition.
        val extra = TitleNormalizer.extraQualifiers(candidate.title, refTitles)
        if (extra.isNotEmpty()) {
            score -= 0.5
            reasons += "edition qualifier ${extra.joinToString()}"
        }

        // Short titles ("Blue", "Ren") collide easily: require exact equality for them.
        val compactLen = refT?.let { TitleNormalizer.compact(it).length } ?: 0
        if (compactLen in 1..4 && titleScore < 1.0) {
            score -= 0.3
            reasons += "short title needs exact match"
        }

        val ry = reference.year
        val cy = candidate.year
        if (ry != null && cy != null) {
            val diff = kotlin.math.abs(ry - cy)
            when {
                diff <= 1 -> score += 0.03
                diff > 3 -> { score -= 0.15; reasons += "year differs by $diff" }
            }
        }

        if (statusConflict(reference.status, candidate.status)) {
            score -= 0.05
            reasons += "status ${reference.status} vs ${candidate.status}"
        }

        // Equal titles are not enough on their own: different works share titles ("Blue", "Monster", remakes, same-name
        // webtoons). A confirmation needs at least one independent signal that the two entries describe the same work.
        val (support, supportReasons, contradiction) = corroboration(reference, candidate, refTitles, candTitles)
        reasons += supportReasons
        score -= contradiction

        score = score.coerceIn(0.0, 1.0)
        val decision = when {
            score >= confirmThreshold && support >= 1.0 -> MatchDecision.CONFIRMED
            score >= confirmThreshold -> {
                reasons += UNCORROBORATED
                MatchDecision.AMBIGUOUS
            }
            score >= ambiguousThreshold -> MatchDecision.AMBIGUOUS
            else -> MatchDecision.REJECTED
        }
        return MatchResult(score, decision, reasons)
    }

    /**
     * Evidence beyond the best title pair: (support, reasons, penalty). Support >= 1 is needed to confirm.
     *  - an author in common (names compared word-order- and case-insensitively): 1
     *  - publication years within one year of each other: 1
     *  - a second, different title variant that also agrees (e.g. English *and* native title): 1
     *  - same specific format, same known status: 0.25 each (never enough alone)
     * Author lists in Latin script that share no name are a contradiction (another work of the same title).
     */
    private fun corroboration(reference: SeriesMetadata, candidate: SourceSeries, refTitles: List<String>, candTitles: List<String>): Triple<Double, List<String>, Double> {
        var support = 0.0
        var penalty = 0.0
        val reasons = mutableListOf<String>()
        val ra = realAuthors(reference.authors + reference.artists)
        val ca = realAuthors(candidate.authors)
        if (ra.isNotEmpty() && ca.isNotEmpty()) {
            if (authorsOverlap(ra, ca)) {
                support += 1.0
                reasons += "author in common"
            } else if ((ra + ca).all(::latinOnly) && !sharesNameWord(ra, ca) && !authorsAlike(ra, ca) &&
                // Korean and Chinese names have no single romanisation: differing credits prove nothing there.
                listOf(reference.kind, candidate.kind).none { it == ContentKind.MANHWA || it == ContentKind.MANHUA }
            ) {
                penalty += 0.3
                reasons += "different authors (${ra.take(2).joinToString()} vs ${ca.take(2).joinToString()})"
            }
        }
        val ry = reference.year
        val cy = candidate.year
        if (ry != null && cy != null && kotlin.math.abs(ry - cy) <= 1) {
            support += 1.0
            reasons += "year agrees"
        }
        val candCompact = candTitles.map { TitleNormalizer.compact(it) }.filter { it.isNotEmpty() }.toSet()
        val agreeing = refTitles.map { TitleNormalizer.compact(it) }.filter { it.length >= 3 && it in candCompact }.toSet()
        if (agreeing.size >= 2) {
            support += 1.0
            reasons += "${agreeing.size} title variants agree"
        }
        if (reference.kind != ContentKind.UNKNOWN && reference.kind == candidate.kind) support += 0.25
        if (reference.status != PublicationStatus.UNKNOWN && reference.status == candidate.status) support += 0.25
        return Triple(support, reasons, penalty)
    }

    /**
     * Picks the provider series that is the same work as [reference]. Returns null when no candidate is
     * confirmed, or when two different candidates are confirmed with near-identical scores (ambiguous).
     */
    fun pick(reference: SeriesMetadata, candidates: List<SourceSeries>): Pair<SourceSeries, MatchResult>? {
        val scored = candidates.map { it to match(reference, it) }
            .filter { it.second.decision == MatchDecision.CONFIRMED }
            // Identifier-backed matches outrank title-only matches of equal score.
            .sortedWith(compareByDescending<Pair<SourceSeries, MatchResult>> { it.second.score }.thenByDescending { it.second.reasons.firstOrNull() == ID_REASON })
        if (scored.isEmpty()) return null
        val top = scored[0]
        val runnerUp = scored.getOrNull(1)
        val idBacked = top.second.reasons.firstOrNull() == ID_REASON
        val runnerIdBacked = runnerUp?.second?.reasons?.firstOrNull() == ID_REASON
        if (runnerUp != null && (!idBacked || runnerIdBacked) && top.second.score - runnerUp.second.score < 0.03) {
            // Two distinct entries fit equally well (e.g. duplicate uploads). Refuse to guess.
            return null
        }
        return top
    }

    /** Candidates worth fetching details for, to resolve an ambiguity with alt titles / ids. */
    fun needsDetails(reference: SeriesMetadata, candidates: List<SourceSeries>): List<SourceSeries> =
        candidates.filter { !it.detailed }
            .map { it to match(reference, it) }
            .filter { it.second.decision == MatchDecision.AMBIGUOUS || it.second.decision == MatchDecision.CONFIRMED }
            .sortedByDescending { it.second.score }
            .take(2)
            .map { it.first }

    private fun kindsConflict(a: ContentKind, b: ContentKind): Boolean {
        if (a == ContentKind.UNKNOWN || b == ContentKind.UNKNOWN) return false
        // Novel vs any comic format is a hard conflict; manga/manhwa/manhua mislabels are common and tolerated.
        return (a == ContentKind.NOVEL) != (b == ContentKind.NOVEL)
    }

    private fun statusConflict(a: PublicationStatus, b: PublicationStatus): Boolean {
        if (a == PublicationStatus.UNKNOWN || b == PublicationStatus.UNKNOWN) return false
        return (a == PublicationStatus.COMPLETED && b == PublicationStatus.ONGOING) ||
            (a == PublicationStatus.ONGOING && b == PublicationStatus.COMPLETED)
    }

    companion object {
        const val ID_REASON = "shared database id"
        const val UNCORROBORATED = "title only: no author, year or second title in common"

        private fun nameKey(name: String) = TitleNormalizer.normalize(name).split(' ').filter { it.isNotEmpty() }.sorted().joinToString(" ")

        /** "Oda Eiichiro" = "Eiichiro ODA" = "oda eiichiro"; "Chugong" = "Chu-Gong". */
        fun authorsOverlap(a: Collection<String>, b: Collection<String>): Boolean {
            val ka = a.flatMap { splitNames(it) }.flatMap { listOf(nameKey(it), TitleNormalizer.compact(it)) }.filter { it.length >= 2 }.toSet()
            return b.flatMap { splitNames(it) }.any { n -> nameKey(n) in ka || TitleNormalizer.compact(n) in ka }
        }

        /** Placeholder credits some sites show instead of a name (MangaRead's uploader "Bot", "Updating", "N/A"). */
        private val PLACEHOLDER_AUTHORS = setOf("bot", "admin", "updating", "unknown", "na", "none", "tba", "tbd", "anonymous", "various", "author", "artist")

        fun realAuthors(names: Collection<String>): List<String> = names.filter { n ->
            val c = TitleNormalizer.compact(n)
            c.isNotEmpty() && c !in PLACEHOLDER_AUTHORS
        }

        /**
         * Two romanisations of one name: "Mak Ellaha" ~ "Markellaha", "Kirin Green" ~ "Kiringreen". Sites spell Korean and
         * Chinese names differently, so a near-identical spelling is not evidence of a different author (it is not
         * counted as an author in common either).
         */
        fun authorsAlike(a: Collection<String>, b: Collection<String>): Boolean {
            val ka = a.flatMap { splitNames(it) }.map { TitleNormalizer.compact(it) }.filter { it.length >= 5 }.toSet()
            val kb = b.flatMap { splitNames(it) }.map { TitleNormalizer.compact(it) }.filter { it.length >= 5 }.toSet()
            return ka.any { x ->
                kb.any { y -> x.contains(y) || y.contains(x) || 1.0 - TitleNormalizer.levenshtein(x, y).toDouble() / maxOf(x.length, y.length) >= 0.8 }
            }
        }

        /** "Chugong (추공)" also counts as "Chugong" and "추공"; lists split on the usual separators. */
        private fun splitNames(s: String) = s.split(',', ';', '/', '&', '、').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { n ->
            val inner = PAREN.findAll(n).map { it.groupValues[1].trim() }.toList()
            listOf(n, PAREN.replace(n, " ").trim()) + inner
        }.filter { it.isNotEmpty() }.distinct()

        private val PAREN = Regex("""[(（]([^)）]*)[)）]""")

        private fun sharesNameWord(a: Collection<String>, b: Collection<String>): Boolean {
            val wa = a.flatMap { TitleNormalizer.normalize(it).split(' ') }.filter { it.length >= 3 }.toSet()
            return b.flatMap { TitleNormalizer.normalize(it).split(' ') }.any { it.length >= 3 && it in wa }
        }

        private fun latinOnly(s: String) = s.all { !it.isLetter() || it.code < 0x250 }
    }
}
