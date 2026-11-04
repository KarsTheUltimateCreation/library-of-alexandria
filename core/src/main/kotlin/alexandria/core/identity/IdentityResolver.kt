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

        score = score.coerceIn(0.0, 1.0)
        val decision = when {
            score >= confirmThreshold -> MatchDecision.CONFIRMED
            score >= ambiguousThreshold -> MatchDecision.AMBIGUOUS
            else -> MatchDecision.REJECTED
        }
        return MatchResult(score, decision, reasons)
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

    private companion object {
        const val ID_REASON = "shared database id"
    }
}
