package alexandria.core

/**
 * Stable identity of a unified series, used as the host-visible manga URL. It never changes after the
 * user adds the series to their library, even when new providers are linked to it later.
 *
 *  - `mb:<id>`               MangaBaka series
 *  - `al:<id>`               AniList media (MangaBaka disabled/unavailable)
 *  - `p:<source>:<seriesId>` a provider entry that matched no metadata record
 */
sealed class SeriesKey {
    abstract val value: String

    data class MangaBaka(val id: Long) : SeriesKey() { override val value get() = "mb:$id" }
    data class AniList(val id: Long) : SeriesKey() { override val value get() = "al:$id" }
    data class Provider(val sourceId: String, val seriesId: String) : SeriesKey() { override val value get() = "p:$sourceId:$seriesId" }

    override fun toString() = value

    companion object {
        private val SAFE_ID = Regex("""^[A-Za-z0-9._~/\-]{1,200}$""")

        fun parse(raw: String): SeriesKey? {
            val v = raw.trim().removePrefix("/")
            return when {
                v.startsWith("mb:") -> v.removePrefix("mb:").toLongOrNull()?.takeIf { it > 0 }?.let { MangaBaka(it) }
                v.startsWith("al:") -> v.removePrefix("al:").toLongOrNull()?.takeIf { it > 0 }?.let { AniList(it) }
                v.startsWith("p:") -> {
                    val rest = v.removePrefix("p:")
                    val source = rest.substringBefore(':', "")
                    val id = rest.substringAfter(':', "")
                    if (source.isEmpty() || !SAFE_ID.matches(id)) null else Provider(source, id)
                }
                else -> null
            }
        }
    }
}

/** Host-visible chapter URL: "<seriesKey>|<chapterKey>". */
object ChapterUrl {
    fun build(series: SeriesKey, chapterKey: String) = "${series.value}|$chapterKey"

    fun parse(url: String): Pair<SeriesKey, String>? {
        val i = url.indexOf('|')
        if (i <= 0) return null
        val key = SeriesKey.parse(url.substring(0, i)) ?: return null
        return key to url.substring(i + 1)
    }
}
