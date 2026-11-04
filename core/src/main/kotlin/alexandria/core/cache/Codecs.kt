package alexandria.core.cache

import alexandria.core.model.SourceChapter
import alexandria.core.reconciliation.Release
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * One provider's chapter list in a compact form for persistence (provider and series ids stored once, short
 * field names). About a third of the size of the plain [SourceChapter] encoding.
 */
@Serializable
data class StoredChapterList(
    @SerialName("p") val sourceId: String,
    @SerialName("s") val seriesId: String,
    @SerialName("c") val chapters: List<StoredChapter>,
) {
    fun toChapters(): List<SourceChapter> = chapters.map { c ->
        SourceChapter(
            sourceId = sourceId, seriesId = seriesId, id = c.id, label = c.label, number = c.number, volume = c.volume,
            title = c.title, language = c.language ?: "en", group = c.group, official = c.official, uploadedAt = c.uploadedAt,
            pageCount = c.pageCount, externalOnly = c.externalOnly ?: false, webUrl = c.webUrl,
        )
    }

    companion object {
        fun of(sourceId: String, seriesId: String, list: List<SourceChapter>) = StoredChapterList(
            sourceId, seriesId,
            list.map { c ->
                StoredChapter(
                    c.id, c.label, c.number, c.volume, c.title, c.language.takeIf { it != "en" }, c.group, c.official,
                    c.uploadedAt, c.pageCount, c.externalOnly.takeIf { it }, c.webUrl,
                )
            },
        )
    }
}

@Serializable
data class StoredChapter(
    @SerialName("i") val id: String,
    @SerialName("l") val label: String,
    @SerialName("n") val number: String? = null,
    @SerialName("v") val volume: String? = null,
    @SerialName("t") val title: String? = null,
    @SerialName("g") val language: String? = null,
    @SerialName("r") val group: String? = null,
    @SerialName("o") val official: Boolean? = null,
    @SerialName("u") val uploadedAt: Long? = null,
    @SerialName("k") val pageCount: Int? = null,
    @SerialName("x") val externalOnly: Boolean? = null,
    @SerialName("w") val webUrl: String? = null,
)

/**
 * Compact per-chapter release memo stored in the host's chapter record:
 * `sourceId<US>seriesId<US>chapterId<US>confidence%`. The confidence travels with the release because it carries the
 * reconciler's numbering-mismatch and title-conflict evidence; without it a doubtful release would look certain.
 * Entries written by 1.6.5 (three fields) decode with *unknown* confidence, which is treated as doubtful until the
 * full chapter list is available again.
 */
object ReleaseMemo {
    const val SEP = "\u001f"

    /** Confidence given to releases decoded from memos that did not record one. */
    const val UNKNOWN_CONFIDENCE = 0.79

    fun encode(r: Release): String =
        listOf(r.sourceId, r.seriesId, r.chapterId, (r.confidence * 100).roundToInt().coerceIn(0, 100).toString()).joinToString(SEP)

    fun decode(raw: String, language: String = "en"): Release? {
        val parts = raw.split(SEP)
        if (parts.size !in 3..4 || parts.take(3).any { it.isEmpty() }) return null
        val confidence = parts.getOrNull(3)?.toIntOrNull()?.let { it / 100.0 } ?: UNKNOWN_CONFIDENCE
        return Release(parts[0], parts[1], parts[2], label = "", language = language, confidence = confidence)
    }
}

/**
 * Combines the full release list known from a chapter list (complete, with confidence) with a compact memo
 * (possibly truncated, or carrying a release that has since disappeared from the list). Known releases come first
 * in their order; memo-only releases are kept at the end as additional fallbacks.
 */
fun mergeReleases(known: List<Release>?, memo: List<Release>?): List<Release> {
    if (known.isNullOrEmpty()) return memo.orEmpty()
    if (memo.isNullOrEmpty()) return known
    val have = known.map { it.sourceId to it.chapterId }.toSet()
    return known + memo.filter { (it.sourceId to it.chapterId) !in have }
}
