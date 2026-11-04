package alexandria.core.normalization

import kotlinx.serialization.Serializable

/**
 * A lossless chapter number. "12.1" and "12.10" are different values: providers use both
 * conventions ("12.10" is frequently the tenth part of chapter 12), so numbers are never
 * converted to floating point for identity purposes.
 */
@Serializable
data class ChapterNumber(val whole: Long, val fraction: String? = null) : Comparable<ChapterNumber> {

    init {
        require(fraction == null || (fraction.isNotEmpty() && fraction.all { it.isDigit() })) { "bad fraction '$fraction'" }
    }

    /** Canonical text form, e.g. "10", "10.5", "10.10". Trailing zeros in the fraction are meaningful and kept. */
    override fun toString(): String = if (fraction == null) whole.toString() else "$whole.$fraction"

    /**
     * Ordering: by whole part, then by the fraction read as a sub-chapter index (".1" < ".5" < ".10"),
     * which matches how scanlators number split chapters. Leading zeros (".05") sort as decimals.
     */
    override fun compareTo(other: ChapterNumber): Int {
        if (whole != other.whole) return whole.compareTo(other.whole)
        val a = fraction
        val b = other.fraction
        if (a == b) return 0
        if (a == null) return -1
        if (b == null) return 1
        return fractionRank(a).compareTo(fractionRank(b))
    }

    /** Approximate decimal value, only for host-facing `chapter_number` (never for identity). */
    fun toFloat(): Float = toString().toFloatOrNull() ?: whole.toFloat()

    companion object {
        private fun fractionRank(f: String): Double =
            if (f.startsWith("0")) ("0.$f").toDouble() else f.trimStart('0').ifEmpty { "0" }.toDouble()

        private val NUMBER = Regex("""^\s*(\d{1,7})(?:[.,](\d{1,4}))?\s*$""")

        fun parse(text: String?): ChapterNumber? {
            if (text == null) return null
            val m = NUMBER.find(text) ?: return null
            val whole = m.groupValues[1].toLong()
            val frac = m.groupValues[2].takeIf { it.isNotEmpty() }
            // "10.0" is how some APIs write a plain chapter 10.
            val normalizedFrac = frac?.takeUnless { it.all { c -> c == '0' } }
            return ChapterNumber(whole, normalizedFrac)
        }
    }
}

enum class ChapterKind(val code: String) {
    MAIN("c"),
    EXTRA("x"),
    SIDE_STORY("side"),
    PROLOGUE("pro"),
    EPILOGUE("epi"),
    ONESHOT("one"),
    SPECIAL("sp"),
    NOTICE("notice"),

    /** No number could be recovered; identity falls back to the normalized label text. */
    UNKNOWN("t"),
}

enum class LabelFlag { REUPLOAD, OFFICIAL, FAN, COLORED, RAW, PREVIEW }

/** Structured identity of a chapter, independent of the provider that listed it. */
@Serializable
data class ChapterIdentity(
    val kind: ChapterKind,
    val number: ChapterNumber? = null,
    val part: Int? = null,
    val season: Int? = null,
    val volume: String? = null,
    /** Normalized label text; only part of the key for [ChapterKind.UNKNOWN]. */
    val text: String? = null,
) {
    /**
     * Stable key used for grouping releases. The volume is excluded by default because most
     * providers number chapters continuously and only some of them show the volume; it is
     * included only for providers detected to restart numbering every volume.
     */
    fun key(includeVolume: Boolean = false): String = buildString {
        if (includeVolume && volume != null) append("v").append(volume).append(':')
        if (season != null) append("s").append(season).append(':')
        append(kind.code)
        if (kind == ChapterKind.UNKNOWN) {
            append(':').append(text ?: "")
            return@buildString
        }
        if (number != null) append(':').append(number.toString())
        if (part != null) append(":p").append(part)
    }
}

data class ParsedLabel(
    val identity: ChapterIdentity,
    /** Chapter title with numbering and tags removed ("Awakening"), if any. */
    val title: String?,
    val flags: Set<LabelFlag> = emptySet(),
    /** Language detected from a tag such as "[English]". */
    val languageHint: String? = null,
)
