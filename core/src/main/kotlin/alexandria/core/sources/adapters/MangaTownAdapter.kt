package alexandria.core.sources.adapters

import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.HtmlAdapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MangaTown (English; a large general manga catalogue). Selectors adapted from keiyoushi/extensions-source
 * `src/en/mangatown` (Apache-2.0).
 *
 * Chapter labels repeat the series name ("Berserk 386"), so numbers and volumes are taken from the chapter URL
 * (`/v01/c010.5/`). Paged chapters list one viewer page per image: they are returned as lazy pages and resolved one
 * at a time while reading; long-strip chapters list their images directly. Images need the site Referer.
 * Series marked "has been licensed" have no readable chapters and yield an empty list.
 */
class MangaTownAdapter(ctx: AdapterContext, override val baseUrl: String = "https://www.mangatown.com") : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "MangaTown"
    override val languages: Set<String> = setOf("en")
    override val limiter: RateLimiter = RateLimiter.shared(ID, 2, 1_000)
    override val imageHosts = setOf("mangahere.org", "mangahere.com", "mangatown.com")

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        val url = "$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("name", query.trim()).addQueryParameter("page", "1").build()
        return parseList(document(url.toString()))
    }

    override suspend fun popular(page: Int) = parseList(document("$baseUrl/directory/0-0-0-0-0-0/$page.htm"))

    override suspend fun latest(page: Int) = parseList(document("$baseUrl/latest/$page.htm"))

    internal fun parseList(doc: Document): List<SourceSeries> = doc.select("li:has(a.manga_cover)").mapNotNull { li ->
        val a = li.selectFirst("p.title a") ?: return@mapNotNull null
        val slug = slugOf(a.attr("href")) ?: return@mapNotNull null
        SourceSeries(
            sourceId = id, id = slug, title = a.attr("title").ifBlank { a.text() }.trim(),
            coverUrl = li.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() },
            webUrl = seriesWebUrl(slug),
        )
    }.distinctBy { it.id }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        return parseDetails(series, document(seriesWebUrl(series.id)))
    }

    internal fun parseDetails(series: SourceSeries, doc: Document): SourceSeries {
        val info = doc.selectFirst("div.article_content") ?: return series
        fun field(label: String): Element? = info.select("li:has(b)").firstOrNull { it.selectFirst("b")?.text()?.startsWith(label, true) == true }
        val alt = field("Alternative Name")?.ownText()?.split(';', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val licensed = info.selectFirst("div.chapter_content:contains(has been licensed)") != null
        return series.copy(
            title = info.selectFirst("h1")?.text()?.trim()?.ifEmpty { null } ?: series.title,
            altTitles = (series.altTitles + alt).distinct(),
            authors = (listOfNotNull(field("Author")) + listOfNotNull(field("Artist"))).flatMap { it.select("a").map { a -> a.text().trim() } }.filter { it.isNotEmpty() }.distinct(),
            genres = field("Genre")?.select("a")?.map { it.text().trim() }.orEmpty(),
            status = if (licensed) PublicationStatus.UNKNOWN else PublicationStatus.parse(field("Status")?.ownText()?.trim()),
            description = doc.selectFirst("span#show")?.text()?.removeSuffix("HIDE")?.trim(),
            coverUrl = doc.selectFirst("div.detail_info img")?.absUrl("src")?.takeIf { it.isNotBlank() } ?: series.coverUrl,
            detailed = true,
        )
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> =
        parseChapters(series.id, document(seriesWebUrl(series.id)))

    internal fun parseChapters(seriesId: String, doc: Document): List<SourceChapter> =
        doc.select("ul.chapter_list li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val path = a.attr("href").substringAfter("/manga/$seriesId/", "").trim('/')
            val m = CHAPTER_PATH.find(path) ?: return@mapNotNull null
            val volume = m.groupValues[1].takeIf { it.isNotEmpty() }?.trimStart('0')?.ifEmpty { "0" }
            val number = m.groupValues[2].let { n -> n.substringBefore('.').trimStart('0').ifEmpty { "0" } + n.substringAfter('.', "").let { f -> if (f.isEmpty()) "" else ".$f" } }
            val title = li.select("span:not(.time):not(.new)").joinToString(" ") { it.text().trim() }.trim().ifEmpty { null }
            SourceChapter(
                sourceId = id, seriesId = seriesId, id = "$seriesId/$path",
                label = "Chapter $number" + (title?.let { " - $it" } ?: ""),
                number = number, volume = volume, title = title, language = "en",
                uploadedAt = date(li.selectFirst("span.time")?.text()),
                webUrl = "$baseUrl/manga/$seriesId/$path/",
            )
        }.reversed() // the site lists newest first

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> =
        parsePages(document("$baseUrl/manga/${chapter.id}/"))

    internal fun parsePages(doc: Document): List<ReaderPage> {
        val viewerPages = doc.select("div.page_select select option").filterNot { it.text().contains("featured", true) }
            .map { it.absUrl("value") }.filter { it.isNotBlank() }.distinct()
        if (viewerPages.isNotEmpty()) return viewerPages.mapIndexed { i, url -> ReaderPage(i, url, lazy = true) }
        return doc.select("div#viewer img").mapNotNull { img -> img.absUrl("src").takeIf { it.isNotBlank() } }
            .mapIndexed { i, src -> ReaderPage(i, src, imageHeaders(src)) }
    }

    override suspend fun resolveImage(pageUrl: String): ReaderPage {
        val src = document(pageUrl).selectFirst("div#viewer img#image, div#viewer img")?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("MangaTown: no image on $pageUrl")
        return ReaderPage(0, src, imageHeaders(src))
    }

    private fun slugOf(href: String): String? = href.substringAfter("/manga/", "").trim('/').takeIf { it.isNotEmpty() && '/' !in it }

    private fun date(text: String?): Long? {
        val t = text?.trim().orEmpty()
        val today = LocalDate.now(ZoneOffset.UTC)
        val day = when {
            t.isEmpty() -> return null
            t.contains("Today", true) -> today
            t.contains("Yesterday", true) -> today.minusDays(1)
            else -> runCatching { LocalDate.parse(t, DATE) }.getOrNull() ?: return null
        }
        return day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/manga/$seriesId/"

    companion object {
        /** Providers added from 1.6.6 on are namespaced with "loa_" so they can never collide with older ids. */
        const val ID = "loa_mangatown"
        private val CHAPTER_PATH = Regex("""^(?:v(\d+)/)?c(\d+(?:\.\d+)?)$""")
        private val DATE = DateTimeFormatter.ofPattern("MMM dd,yyyy", Locale.US)
    }
}
