package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.ExternalLinkIds
import alexandria.core.sources.HtmlAdapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element
import java.time.Instant

/**
 * WeebCentral (English). Selectors adapted from keiyoushi/extensions-source `src/en/weebcentral`
 * (Apache-2.0). Uses the same request pacing as that extension (1 request / 2 s to the site host).
 */
class WeebCentralAdapter(ctx: AdapterContext) : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "WeebCentral"
    override val baseUrl = "https://weebcentral.com"
    override val languages: Set<String> = setOf("en")
    // Same long-run pace as the Keiyoushi extension (1 req / 2 s) but allows a burst of 3 when opening a series.
    override val limiter: RateLimiter = RateLimiter.shared("weebcentral", 3, 6_000)

    override suspend fun search(query: String): List<SourceSeries> = searchData(query.replace(EXCLUDED_SEARCH_CHARS, " ").trim(), "Best Match")

    override suspend fun popular(page: Int) = searchData("", "Popularity", page)

    override suspend fun latest(page: Int) = searchData("", "Latest Updates", page)

    private suspend fun searchData(text: String, sort: String, page: Int = 1): List<SourceSeries> {
        val url = "$baseUrl/search/data".toHttpUrl().newBuilder()
            .addQueryParameter("text", text)
            .addQueryParameter("sort", sort)
            .addQueryParameter("order", "Descending")
            .addQueryParameter("official", "Any")
            .addQueryParameter("anime", "Any")
            .addQueryParameter("adult", "Any")
            .addQueryParameter("display_mode", "Full Display")
            .addQueryParameter("limit", "32")
            .addQueryParameter("offset", ((page - 1) * 32).toString())
            .build()
        val doc = document(url.toString())
        return doc.select("article > section > a").mapNotNull { a ->
            val href = a.attr("abs:href")
            val seriesId = href.toHttpUrl().pathSegments.getOrNull(1) ?: return@mapNotNull null
            SourceSeries(
                sourceId = id,
                id = seriesId,
                title = a.selectFirst("div:not([class]):last-child")?.text() ?: return@mapNotNull null,
                coverUrl = a.sourceImg(),
                webUrl = href,
            )
        }.distinctBy { it.id }
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val doc = document(seriesWebUrl(series.id))
        val sections = doc.select("section[x-data] > section")
        val info = sections.getOrNull(0)
        val main = sections.getOrNull(1)
        val alt = main?.select("li:has(strong:contains(Associated Name)) li")?.map { it.text() }.orEmpty()
        val trackers = doc.select("li:has(strong:contains(Track)) a[href]").map { it.attr("abs:href") }
        return series.copy(
            title = main?.selectFirst("h1")?.text() ?: series.title,
            altTitles = (series.altTitles + alt).distinct(),
            coverUrl = info?.sourceImg() ?: series.coverUrl,
            authors = info?.select("ul > li:has(strong:contains(Author)) > span > a")?.map { it.text() }.orEmpty(),
            genres = info?.select("ul > li:has(strong:contains(Tag)) a")?.map { it.text() }.orEmpty(),
            kind = ContentKind.parse(info?.selectFirst("ul > li:has(strong:contains(Type)) a")?.text()),
            status = PublicationStatus.parse(info?.selectFirst("ul > li:has(strong:contains(Status)) > a")?.text()),
            year = info?.selectFirst("ul > li:has(strong:contains(Released)) span")?.text()?.trim()?.toIntOrNull(),
            description = main?.selectFirst("li:has(strong:contains(Description)) > p")?.text(),
            ids = series.ids.merge(ExternalLinkIds.from(trackers)),
            detailed = true,
        )
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val doc = document("$baseUrl/series/${series.id}/full-chapter-list")
        return doc.select("div[x-data] > a").mapNotNull { a ->
            val href = a.attr("abs:href")
            val chapterId = href.toHttpUrl().pathSegments.getOrNull(1) ?: return@mapNotNull null
            val label = a.selectFirst("span.flex > span")?.text() ?: return@mapNotNull null
            val official = a.select("img").any { it.attr("src").lowercase().contains("official") }
            SourceChapter(
                sourceId = id,
                seriesId = series.id,
                id = chapterId,
                label = label,
                language = "en",
                group = if (official) "Official" else null,
                official = official,
                uploadedAt = a.selectFirst("time[datetime]")?.attr("datetime")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                webUrl = href,
            )
        }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val url = "$baseUrl/chapters/${chapter.id}/images".toHttpUrl().newBuilder()
            .addQueryParameter("is_prev", "False")
            .addQueryParameter("reading_style", "long_strip")
            .build()
        val doc = document(url.toString())
        return doc.select("section[x-data~=scroll] > img").mapIndexed { i, img ->
            val src = img.attr("abs:src")
            ReaderPage(i, src, imageHeaders(src))
        }
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/series/$seriesId"

    private fun Element.sourceImg(): String? =
        selectFirst("source")?.attr("srcset")?.takeIf { it.isNotBlank() }?.replace("small", "normal")
            ?: selectFirst("img")?.attr("abs:src")

    companion object {
        const val ID = "weebcentral"
        private val EXCLUDED_SEARCH_CHARS = Regex("[!#:(),-]")
    }
}
