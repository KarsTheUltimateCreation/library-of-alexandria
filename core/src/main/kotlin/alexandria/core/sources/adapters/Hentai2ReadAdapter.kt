package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.HtmlAdapter
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.nodes.Document

/**
 * Hentai2Read (English hentai manga and doujinshi, multi-chapter). HTML; the search form, selectors and the image
 * list format (`'images' : [...]` relative to static.hentaicdn.com) follow the Tachiyomi Hentai2Read extension
 * (yuzono/cursed-manga-extensions, Apache-2.0).
 *
 * The site sits behind a Cloudflare challenge, like ManhwaRead: requests go through the host's client, which solves
 * it when it can; otherwise the provider simply contributes nothing and the health tracker skips it for a while.
 *
 * Series ids are slugs ("naruto_at_the_end"); chapter ids are "slug/number".
 */
class Hentai2ReadAdapter(ctx: AdapterContext, override val baseUrl: String = "https://hentai2read.com") : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "Hentai2Read"
    override val languages: Set<String> = setOf("en")
    override val adult = true
    override val imageHosts = setOf("hentaicdn.com")
    override val limiter: RateLimiter = RateLimiter.shared("hentai2read", 2, 1_000)

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        val form = FormBody.Builder()
            .add("cmd_wpm_wgt_mng_sch_sbm", "Search")
            .add("txt_wpm_wgt_mng_sch_nme", "")
            .add("cmd_wpm_pag_mng_sch_sbm", "")
            .add("txt_wpm_pag_mng_sch_nme", query.trim())
            .add("cbo_wpm_pag_mng_sch_nme", "0")
            .build()
        return list(document(Request.Builder().url("$baseUrl/hentai-list/advanced-search").headers(headers()).post(form).build()))
    }

    override suspend fun popular(page: Int): List<SourceSeries> = list(document("$baseUrl/hentai-list/all/any/all/most-popular/$page/"))

    override suspend fun latest(page: Int): List<SourceSeries> = list(document("$baseUrl/hentai-list/all/any/all/last-updated/$page/"))

    internal fun list(doc: Document): List<SourceSeries> = doc.select("div.book-grid-item").mapNotNull { el ->
        val a = el.selectFirst("div.overlay-title a") ?: return@mapNotNull null
        val slug = slugFrom(a.attr("abs:href")) ?: return@mapNotNull null
        SourceSeries(
            sourceId = id,
            id = slug,
            title = a.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
            coverUrl = el.selectFirst("img")?.attr("abs:src")?.takeIf { it.isNotBlank() },
            kind = ContentKind.MANGA,
            webUrl = seriesWebUrl(slug),
        )
    }.distinctBy { it.id }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val doc = document(seriesWebUrl(series.id))
        val info = doc.selectFirst("ul.list-simple-mini") ?: return series.copy(detailed = true)
        fun field(label: String) = info.select("li:contains($label) a").map { it.text().trim() }.filter { it.isNotEmpty() && it != "-" }
        val title = doc.selectFirst("h3.block-title > a")?.ownText()?.trim()?.takeIf { it.isNotEmpty() } ?: series.title
        val alt = info.selectFirst("li")?.text()?.trim()?.takeIf { it != "-" }?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val status = info.select("li:contains(Status) a").text()
        return series.copy(
            title = title,
            altTitles = (series.altTitles + alt).filter { !it.equals(title, true) }.distinct(),
            coverUrl = doc.selectFirst("a#js-linkNext img")?.attr("abs:src")?.takeIf { it.isNotBlank() } ?: series.coverUrl,
            authors = (field("Author") + field("Artist")).distinct(),
            genres = (field("Category") + field("Content")).distinct(),
            status = when {
                "Ongoing" in status -> PublicationStatus.ONGOING
                "Completed" in status -> PublicationStatus.COMPLETED
                else -> series.status
            },
            description = info.select("li:contains(Storyline) > p").text().trim().takeIf { it.isNotEmpty() && it != "-" } ?: series.description,
            detailed = true,
        )
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val doc = document(seriesWebUrl(series.id))
        return doc.select("ul.nav-chapters > li > div.media > a").mapNotNull { a ->
            val href = a.attr("abs:href")
            val path = href.removePrefix(baseUrl).trim('/').takeIf { it.count { c -> c == '/' } == 1 } ?: return@mapNotNull null
            SourceChapter(
                sourceId = id,
                seriesId = series.id,
                id = path,
                label = a.ownText().trim().takeIf { it.isNotEmpty() } ?: "Chapter ${path.substringAfter('/')}",
                number = path.substringAfter('/').takeIf { n -> n.isNotEmpty() && n.all { it.isDigit() || it == '.' } },
                language = "en",
                webUrl = href,
            )
        }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val html = ctx.client.fetchText(Request.Builder().url("$baseUrl/${chapter.id}/").headers(headers()).build(), limiter = limiter)
        return parsePages(html).mapIndexed { i, url -> ReaderPage(i, url, imageHeaders(url)) }
    }

    internal fun parsePages(html: String): List<String> {
        val list = IMAGES.find(html)?.groupValues?.get(1) ?: error("Hentai2Read: image list not found")
        return list.split(',').map { it.trim().trim('"').replace("\\/", "/") }.filter { it.isNotEmpty() }.map { IMAGE_BASE + it }
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/$seriesId/"

    private fun slugFrom(url: String): String? = url.removePrefix(baseUrl).trim('/').substringBefore('/').takeIf { it.isNotBlank() }

    companion object {
        const val ID = "hentai2read"
        private const val IMAGE_BASE = "https://static.hentaicdn.com/hentai"
        private val IMAGES = Regex("""'images'\s*:\s*\["(.*?),?"]""")
    }
}
