package alexandria.core.sources.adapters

import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.HtmlAdapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * mgeko (English, mostly manhwa/manhua). Selectors follow keiyoushi/extensions-source `src/en/mangarawclub`
 * (Apache-2.0). Series ids are slugs ("manga-1003"); chapter ids are reader slugs ("manga-1003-chapter-12-eng-li").
 */
class MgekoAdapter(ctx: AdapterContext, override val baseUrl: String = "https://www.mgeko.cc") : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "mgeko"
    override val languages: Set<String> = setOf("en")
    override val limiter: RateLimiter = RateLimiter.shared("mgeko", 2, 1_000)

    override suspend fun search(query: String): List<SourceSeries> {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder().addQueryParameter("search", searchTerm(query)).build()
        return document(url.toString()).select(".novel-item").mapNotNull { el ->
            val a = el.selectFirst("a[href*=/manga/]") ?: return@mapNotNull null
            val slug = slugFrom(a.attr("abs:href")) ?: return@mapNotNull null
            SourceSeries(
                sourceId = id,
                id = slug,
                title = el.selectFirst(".novel-title")?.text()?.takeIf { it.isNotBlank() } ?: a.attr("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                coverUrl = el.selectFirst(".novel-cover img")?.lazySrc(),
                webUrl = seriesWebUrl(slug),
            )
        }.distinctBy { it.id }
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val doc = document(seriesWebUrl(series.id))
        val alt = parseAltNames(doc.selectFirst(".alternative-title")?.ownText())
        val author = doc.selectFirst(".author a")?.attr("title")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("updating", true) }
        return series.copy(
            title = doc.selectFirst(".novel-info h1, h1.novel-title")?.text()?.takeIf { it.isNotBlank() } ?: series.title,
            altTitles = (series.altTitles + alt).distinct(),
            coverUrl = doc.selectFirst(".cover img")?.lazySrc()?.takeUnless { "placeholder" in it } ?: series.coverUrl,
            authors = listOfNotNull(author).ifEmpty { series.authors },
            genres = doc.select(".categories a[href*=genre]").map { it.ownText().trim() }.filter { it.isNotEmpty() },
            status = when {
                doc.selectFirst("div.header-stats strong.completed") != null -> PublicationStatus.COMPLETED
                doc.selectFirst("div.header-stats strong.ongoing") != null -> PublicationStatus.ONGOING
                else -> series.status
            },
            description = doc.selectFirst(".description")?.text()?.substringAfter("The Summary is", "")?.trim()?.ifEmpty { null } ?: series.description,
            detailed = true,
        )
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val doc = document("${seriesWebUrl(series.id)}all-chapters/")
        return doc.select("ul.chapter-list > li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("abs:href")
            val chapterId = href.substringAfter("/reader/en/", "").trim('/').takeIf { it.isNotEmpty() && '/' !in it } ?: return@mapNotNull null
            val raw = li.selectFirst(".chapter-title, .chapter-number")?.ownText()?.trim().orEmpty()
            // Un-numbered posts such as "spoiler" or "announcement" are site notices, not chapters.
            if (NOTICE.matches(raw.removeSuffix("-eng-li"))) return@mapNotNull null
            SourceChapter(
                sourceId = id,
                seriesId = series.id,
                id = chapterId,
                label = chapterLabel(raw).ifEmpty { chapterId },
                language = "en",
                uploadedAt = li.selectFirst(".chapter-update")?.attr("datetime")?.let(::parseDate),
                webUrl = href,
            )
        }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val doc = document("$baseUrl/reader/en/${chapter.id}/")
        return doc.select("#chapter-reader img")
            .mapNotNull { it.lazySrc() }
            .filterNot { "credits-mgeko" in it }
            .mapIndexed { i, src -> ReaderPage(i, src, imageHeaders(src)) }
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/manga/$seriesId/"

    private fun slugFrom(url: String): String? = url.substringAfter("/manga/", "").trim('/').takeIf { it.isNotEmpty() && '/' !in it }

    companion object {
        const val ID = "mgeko"
        private val BULLET_OR_SEMICOLON = Regex("[•;]")
        private val DATE = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US)

        private fun Element.lazySrc(): String? =
            absUrl("data-src").ifEmpty { absUrl("src") }.takeIf { it.isNotBlank() && !it.endsWith("loading.gif") }

        private val NOTICE = Regex("""(?i)(spoilers?|announcements?|notice|hiatus|info|news)(-\d+)?""")
        private val NUMBERED = Regex("""^(\d+)(?:-(\d+))?(?:-(.+))?$""")

        /**
         * Reader slugs carry the label: "12-eng-li" -> "Chapter 12", "12-5-eng-li" -> "Chapter 12.5",
         * "200-side-story-21-the-end-eng-li" -> "Chapter 200: Side Story 21 The End", "side-story-1-eng-li" -> "Side Story 1".
         */
        internal fun chapterLabel(raw: String): String {
            val s = raw.trim().removeSuffix("-eng-li")
            fun words(t: String) = t.split('-').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            val m = NUMBERED.matchEntire(s) ?: return words(s)
            val (major, minor, rest) = m.destructured
            val number = if (minor.isNotEmpty()) "$major.$minor" else major
            return if (rest.isNotEmpty()) "Chapter $number: ${words(rest)}" else "Chapter $number"
        }

        private val PUNCTUATION = Regex("""[^\p{L}\p{N}\s]""")

        /**
         * The site matches the query as a literal substring of the title and writes apostrophes/colons inconsistently
         * ("Omniscient Reader’s Viewpoint"), so search the longest punctuation-free stretch of the query.
         */
        internal fun searchTerm(query: String): String =
            query.split(PUNCTUATION).map { it.trim().replace(Regex("""\s+"""), " ") }.maxByOrNull { it.length }?.ifEmpty { null } ?: query.trim()

        /** "Updating;Omniscient Reader;ORV • Lecteur omniscient" -> names. Commas are kept: titles contain them. */
        internal fun parseAltNames(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(BULLET_OR_SEMICOLON).map { it.trim() }.filter { it.isNotEmpty() && !it.equals("updating", true) }
        }

        /** Django-style "Jan. 7, 2026, 2:50 p.m." / "Sept. 3, 2025, noon". */
        internal fun parseDate(raw: String): Long? {
            val s = raw.replace(".", "").replace("Sept", "Sep").replace("noon", "12:00 pm").replace("midnight", "12:00 am")
                .replace(Regex("""(?<![:\d])(\d{1,2})\s*(am|pm)$""", RegexOption.IGNORE_CASE), "$1:00 $2") // "2 pm" -> "2:00 pm"
                .replace(Regex("""(am|pm)$""", RegexOption.IGNORE_CASE)) { it.value.uppercase() }
                .trim()
            return runCatching { LocalDateTime.parse(s, DATE).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }
    }
}
