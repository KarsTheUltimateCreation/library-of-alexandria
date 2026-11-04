package alexandria.core.covers

import alexandria.core.config.AggregatorSettings
import alexandria.core.config.CoverProvider
import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.quality.ImageInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class ScoredCover(val candidate: CoverCandidate, val score: Double, val verified: Boolean, val notes: List<String>)

/**
 * Picks the best authentic cover. Uses metadata dimensions when available (MangaBaka reports raw width,
 * height and size), and otherwise probes only the first bytes of a few high-trust candidates. Never upscales
 * or generates artwork.
 *
 * Scoring (0..1):
 *  - resolution (40%): pixel area, saturating at ~1500×2250;
 *  - source trust (35%): original uploads > resized metadata art > provider thumbnails;
 *  - compression (15%): bytes/pixel; very low values indicate heavy compression or an upscaled thumbnail;
 *  - aspect (10%): portrait covers between 1:1.2 and 1:1.8.
 * Translated/edition covers are discounted when "Prefer Original Artwork" is on.
 */
class CoverResolver(
    private val probe: suspend (CoverCandidate) -> ImageInfo?,
    private val maxProbes: Int = 2,
) {

    suspend fun rank(candidates: List<CoverCandidate>, settings: AggregatorSettings, originalLanguage: String?, primarySourceId: String?): List<ScoredCover> {
        val unique = candidates.distinctBy { it.url }
        if (unique.isEmpty()) return emptyList()

        if (!settings.highQualityCovers) {
            // Feature off: the primary content source's own cover, falling back to anything else.
            val primary = unique.filter { it.provider == primarySourceId }
            return (primary + (unique - primary.toSet())).map { ScoredCover(it, 0.0, false, listOf("high-quality covers disabled")) }
        }

        // Enrich a few unknown-dimension candidates, trusted ones first.
        val toProbe = unique.filter { it.width == null || it.height == null }
            .sortedByDescending { it.coverOrigin.trust }
            .take(maxProbes)
        val probed: Map<String, ImageInfo?> = coroutineScope {
            toProbe.map { c -> async { c.url to withTimeoutOrNull(5_000) { runCatching { probe(c) }.getOrNull() } } }.awaitAll().toMap()
        }

        val scored = unique.map { c ->
            val info = probed[c.url]
            val w = c.width ?: info?.width
            val h = c.height ?: info?.height
            val bytes = c.bytes ?: info?.bytes
            val wasProbed = c.url in probed
            val notes = mutableListOf<String>()
            if (wasProbed && info == null) notes += "probe failed"

            val area = if (w != null && h != null) (w.toDouble() * h / (1500.0 * 2250.0)).coerceAtMost(1.0) else defaultArea(c.coverOrigin)
            val aspect = if (w != null && h != null && w > 0) {
                val r = h.toDouble() / w
                if (r in 1.2..1.8) 1.0 else if (r in 0.9..2.2) 0.75 else 0.4
            } else 0.8
            val bpp = if (w != null && h != null && bytes != null && w * h > 0) bytes.toDouble() / (w.toLong() * h) else null
            val compression = when {
                bpp == null -> 0.75
                bpp >= 0.12 -> 1.0
                bpp >= 0.05 -> 0.85
                else -> { notes += "low bytes/pixel (%.3f)".format(bpp); 0.55 }
            }
            var score = 0.40 * area + 0.35 * c.coverOrigin.trust + 0.15 * compression + 0.10 * aspect
            if (settings.preferOriginalArtwork && c.language != null && originalLanguage != null && !c.language.equals(originalLanguage, true)) {
                score *= 0.85
                notes += "translated edition cover"
            }
            if (wasProbed && info == null) score *= 0.3 // unreachable or not an image
            if (settings.coverProvider != CoverProvider.AUTO && matchesProvider(c, settings.coverProvider, primarySourceId)) score += 1.0
            ScoredCover(c.copy(width = w, height = h, bytes = bytes), score, verified = info != null || c.width != null, notes = notes)
        }
        return scored.sortedByDescending { it.score }
    }

    private fun defaultArea(origin: CoverOrigin) = when (origin) {
        CoverOrigin.METADATA_ORIGINAL, CoverOrigin.PROVIDER_ORIGINAL -> 0.5
        CoverOrigin.METADATA_LARGE -> 0.12 // AniList extraLarge is ~460px wide
        CoverOrigin.PROVIDER_THUMBNAIL -> 0.05
    }

    private fun matchesProvider(c: CoverCandidate, p: CoverProvider, primarySourceId: String?) = when (p) {
        CoverProvider.AUTO -> false
        CoverProvider.MANGABAKA -> c.provider == "mangabaka"
        CoverProvider.ANILIST -> c.provider == "anilist"
        CoverProvider.MANGADEX -> c.provider == "mangadex"
        CoverProvider.PRIMARY_SOURCE -> c.provider == primarySourceId
    }
}
