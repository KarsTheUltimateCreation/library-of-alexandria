package alexandria.core.ranking

import alexandria.core.config.AggregatorSettings
import alexandria.core.config.QualityMode
import alexandria.core.model.ReaderPage
import alexandria.core.quality.ImageInfo
import alexandria.core.reconciliation.Release

/**
 * Orders releases of one canonical chapter *before* any page is fetched, using cheap signals only:
 * explicit preference, user source order, language order, official vs fan release, provider health and
 * match confidence.
 */
class SourceRanker(private val health: ProviderHealth) {

    fun score(r: Release, settings: AggregatorSettings): Double {
        var s = 0.0
        if (settings.preferredSource == r.sourceId) s += 100
        s -= 8.0 * settings.sourceRank(r.sourceId).coerceAtMost(10)
        val lang = settings.languageRank(r.language)
        s -= if (lang == Int.MAX_VALUE) 200.0 else 40.0 * lang
        if (settings.preferOfficial && r.official == true) s += 12
        s += 20 * health.get(r.sourceId).successRate
        s += 30 * r.confidence
        if (health.isCircuitOpen(r.sourceId)) s -= 50
        // The service asked us to pause (429/503 + Retry-After): its requests would fail fast without being sent.
        if (alexandria.core.net.ServiceGate.isCoolingDown(r.sourceId)) s -= 40
        return s
    }

    fun rank(releases: List<Release>, settings: AggregatorSettings): List<Release> =
        releases.filter { !it.externalOnly }.sortedByDescending { score(it, settings) }
}

/** Evidence gathered for one candidate release at read time. */
data class CandidateEvidence(
    val release: Release,
    val pages: List<ReaderPage>?,
    val probes: List<ImageInfo?>,
    /** False when the first image could not be fetched at all (HTTP error / network); null when not checked. */
    val firstPageReachable: Boolean? = null,
    val error: Throwable? = null,
)

data class QualityVerdict(val release: Release, val score: Double, val completeness: Double, val notes: List<String>)

/**
 * Scores candidates for one chapter from their page lists plus a few probed image headers.
 *
 * Completeness dominates every mode: a complete, readable chapter outranks an incomplete one with sharper
 * images. Duplicate page URLs and unreadable first pages are penalised. Translation accuracy is *not*
 * assessed — there is no reliable signal for it.
 */
class ChapterQualityAnalyzer(private val ranker: SourceRanker, private val health: ProviderHealth) {

    fun evaluate(evidence: List<CandidateEvidence>, settings: AggregatorSettings): List<QualityVerdict> {
        val valid = evidence.filter { !it.pages.isNullOrEmpty() }
        if (valid.isEmpty()) return emptyList()
        val maxPages = valid.maxOf { it.pages!!.size }.toDouble()
        // Long-strip chapters (webtoons) are sliced into a different number of images per provider (the same
        // chapter can be 16 or 165 images), so page counts say nothing about completeness. Slice heights vary too
        // much for a couple of probes to measure length precisely (estimates were off by up to ~45% on complete
        // chapters), so strips are only flagged when the estimated length is grossly short (a teaser, a cut-off
        // upload); otherwise resolution, reliability and preference decide.
        fun aspect(e: CandidateEvidence) = e.probes.filterNotNull().filter { it.width > 0 }.map { it.height.toDouble() / it.width }.average().takeIf { !it.isNaN() }
        val stripLike = valid.any { (aspect(it) ?: 0.0) > STRIP_ASPECT }
        val lengths = if (stripLike) valid.associateWith { e -> aspect(e)?.let { it * e.pages!!.size } } else emptyMap()
        val maxLength = lengths.values.filterNotNull().maxOrNull()
        fun stripCompleteness(e: CandidateEvidence): Double {
            val ratio = lengths[e]?.let { len -> maxLength?.let { (len / it).coerceAtMost(1.0) } } ?: return 1.0
            return if (ratio >= STRIP_TRUNCATED) 1.0 else ratio
        }
        val prefScores = evidence.associate { it.release to ranker.score(it.release, settings) }
        val prefMin = prefScores.values.minOrNull() ?: 0.0
        val prefMax = prefScores.values.maxOrNull() ?: 0.0
        // Data saver compares sizes relative to the lightest candidate (absolute thresholds hide real differences).
        fun avgBytes(e: CandidateEvidence) = e.probes.filterNotNull().mapNotNull { it.bytes }.average().takeIf { !it.isNaN() }
        val lightest = valid.mapNotNull { avgBytes(it) }.minOrNull()

        return valid.map { e ->
            val notes = mutableListOf<String>()
            val pages = e.pages!!
            val distinct = pages.map { it.imageUrl.substringBefore('#') }.toSet().size
            var completeness = if (stripLike) stripCompleteness(e) else pages.size / maxPages
            if (distinct < pages.size) {
                completeness *= distinct.toDouble() / pages.size
                notes += "${pages.size - distinct} duplicate page(s)"
            }

            val known = e.probes.filterNotNull()
            if (known.isNotEmpty()) {
                val kb = known.mapNotNull { it.bytes }.average().takeIf { !it.isNaN() }?.let { " ~%.0fKB".format(it / 1024) } ?: ""
                notes += "%.0fpx%s".format(known.map { it.width }.average(), kb)
            }
            val quality = when (settings.qualityMode) {
                QualityMode.DATA_SAVER -> {
                    val bytes = avgBytes(e)
                    if (bytes == null || lightest == null) 0.5 else (lightest / bytes).coerceIn(0.0, 1.0)
                }
                else -> {
                    if (known.isEmpty()) 0.5 else {
                        val width = known.map { it.width }.average()
                        var q = (width / 2000.0).coerceAtMost(1.0) // saturates at 2000px (high-res scans exceed 1400px)
                        val bpp = known.mapNotNull { it.bytesPerPixel }.average().takeIf { !it.isNaN() }
                        if (bpp != null && bpp < 0.05) { q *= 0.8; notes += "heavily compressed" }
                        if (!settings.preferOriginalResolution) q = 0.5 + q / 2
                        q
                    }
                }
            }
            val reliability = health.get(e.release.sourceId).successRate
            val pref = if (prefMax > prefMin) (prefScores.getValue(e.release) - prefMin) / (prefMax - prefMin) else 1.0

            val (wc, wq, wr, wp) = when (settings.qualityMode) {
                QualityMode.BEST -> listOf(0.45, 0.30, 0.15, 0.10)
                QualityMode.BALANCED -> listOf(0.45, 0.15, 0.25, 0.15)
                QualityMode.DATA_SAVER -> listOf(0.45, 0.30, 0.15, 0.10)
            }
            var score = wc * completeness + wq * quality + wr * reliability + wp * pref
            if (completeness < 0.9) {
                score *= 0.6
                notes += if (stripLike) "incomplete (~%.0f%% of strip length)".format(completeness * 100)
                else "incomplete (%d/%d pages)".format(pages.size, maxPages.toInt())
            }
            if (e.firstPageReachable == false) { score *= 0.3; notes += "first page unreadable" }
            QualityVerdict(e.release, score, completeness, notes)
        }.sortedByDescending { it.score }
    }

    private companion object {
        /** Height/width above which images are long-strip slices rather than single pages. */
        const val STRIP_ASPECT = 2.0

        /** Estimated strip length below this fraction of the longest candidate counts as truncated. */
        const val STRIP_TRUNCATED = 0.4
    }
}
