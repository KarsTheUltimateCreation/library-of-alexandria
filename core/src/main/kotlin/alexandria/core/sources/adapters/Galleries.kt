package alexandria.core.sources.adapters

import alexandria.core.model.SourceChapter

/** Helpers shared by the single-gallery providers (nhentai, Hitomi, E-Hentai, Pururin). */
internal object Galleries {
    private val SPACES = Regex("""\s+""")
    private const val OPEN = "[(（【{"
    private const val CLOSE = "])）】}"

    /** Length of the balanced bracket group starting at [from] (which must be an opening bracket), or -1. */
    private fun groupEnd(s: String, from: Int): Int {
        var depth = 0
        for (i in from until s.length) {
            when {
                s[i] in OPEN -> depth++
                s[i] in CLOSE -> if (--depth == 0) return i
            }
        }
        return -1
    }

    /** Removes bracket groups at the start ("(C104) [Circle (Artist)] ") and at the end (" (Parody) [English]"). */
    private fun stripGroups(raw: String): String {
        var s = raw.trim()
        while (s.isNotEmpty() && s[0] in OPEN) {
            val end = groupEnd(s, 0)
            if (end < 0 || end == s.length - 1) break
            s = s.substring(end + 1).trim()
        }
        while (s.isNotEmpty() && s.last() in CLOSE) {
            // Find the opening bracket matching the final closing one.
            var depth = 0
            var start = -1
            for (i in s.indices.reversed()) {
                if (s[i] in CLOSE) depth++ else if (s[i] in OPEN && --depth == 0) { start = i; break }
            }
            if (start <= 0) break
            s = s.substring(0, start).trim()
        }
        return s
    }

    /**
     * The work's own title from a gallery name: "(C104) [Circle (Artist)] Title (Parody) [English] [Digital]"
     * becomes "Title". Falls back to the raw name when nothing would be left.
     */
    fun cleanTitle(raw: String): String {
        // "Original title | English title" (nhentai) or "English / Japanese" (Pururin): keep the half in Latin script.
        val halves = raw.split(" | ").map { stripGroups(it).replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }
        val latin = halves.lastOrNull { h -> h.count { it.code < 0x250 && it.isLetter() } * 2 >= h.count { it.isLetter() } }
        return (latin ?: halves.firstOrNull())?.ifEmpty { null } ?: raw.trim()
    }

    /** Every name a gallery goes by, cleaned title first. */
    fun titles(vararg raw: String?): List<String> = raw.filterNotNull().filter { it.isNotBlank() }
        .flatMap { listOf(cleanTitle(it), it.trim()) + it.split(" | ").map(::stripGroups) }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

    /** A gallery is one chapter. */
    fun chapter(sourceId: String, galleryId: String, uploadedAt: Long?, pageCount: Int?, webUrl: String) = SourceChapter(
        sourceId = sourceId,
        seriesId = galleryId,
        id = galleryId,
        label = "Chapter 1",
        number = "1",
        language = "en",
        uploadedAt = uploadedAt,
        pageCount = pageCount,
        webUrl = webUrl,
    )
}
