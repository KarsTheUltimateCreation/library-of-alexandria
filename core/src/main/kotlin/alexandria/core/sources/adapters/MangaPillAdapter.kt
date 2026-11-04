package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.HtmlAdapter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Element

/**
 * MangaPill (English). Selectors adapted from keiyoushi/extensions-source `src/en/mangapill` (Apache-2.0).
 * Images are served from a CDN that requires the site Referer.
 */
class MangaPillAdapter(ctx: AdapterContext) : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "MangaPill"
    override val baseUrl = "https://mangapill.com"
    override val languages: Set<String> = setOf("en")
    override val limiter: RateLimiter = RateLimiter.shared("mangapill", 2, 1_000)

    override suspend fun search(query: String): List<SourceSeries> {
        val url = "$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("page", "1").build()
        return document(url.toString()).select(".grid > div:not([class])").mapNotNull { card(it) }.distinctBy { it.id }
    }

    override suspend fun popular(page: Int): List<SourceSeries> {
        if (page > 1) return emptyList()
        return document("$baseUrl/").select("div:has(h4:contains(Trending)) > .grid > div:not([class])").mapNotNull { card(it) }
    }

    override suspend fun latest(page: Int): List<SourceSeries> {
        if (page > 1) return emptyList()
        return document("$baseUrl/chapters").select(".grid > div:not([class])").mapNotNull { card(it) }.distinctBy { it.id }
    }

    /** "/manga/2/solo-leveling" -> "2/solo-leveling" */
    private fun card(el: Element): SourceSeries? {
        val link = el.selectFirst("a[href^='/manga/']") ?: return null
        val path = link.attr("href").removePrefix("/manga/").trim('/')
        if (path.isEmpty()) return null
        val badges = el.select("div.flex.flex-wrap.gap-1").firstOrNull()?.children()?.map { it.text().trim() }.orEmpty()
        return SourceSeries(
            sourceId = id,
            id = path,
            title = el.selectFirst("div.font-black")?.text() ?: el.selectFirst("div.line-clamp-2")?.text() ?: return null,
            altTitles = listOfNotNull(el.selectFirst("div.text-xs.line-clamp-2, div.line-clamp-2.text-xs")?.text()),
            coverUrl = el.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() },
            kind = ContentKind.parse(badges.getOrNull(0)),
            year = badges.firstNotNullOfOrNull { it.toIntOrNull()?.takeIf { y -> y in 1900..2100 } },
            status = PublicationStatus.parse(badges.firstOrNull { PublicationStatus.parse(it) != PublicationStatus.UNKNOWN }),
            webUrl = seriesWebUrl(path),
        )
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val doc = document(seriesWebUrl(series.id))
        // The detail page has no alternative titles; those only appear on search cards.
        fun field(label: String) = doc.select("label.text-secondary").firstOrNull { it.text().equals(label, true) }?.nextElementSibling()?.text()
        return series.copy(
            title = doc.selectFirst("h1")?.text() ?: series.title,
            description = doc.selectFirst("p.text-sm")?.text() ?: series.description,
            kind = ContentKind.parse(field("Type")).takeIf { it != ContentKind.UNKNOWN } ?: series.kind,
            status = PublicationStatus.parse(field("Status")).takeIf { it != PublicationStatus.UNKNOWN } ?: series.status,
            year = field("Year")?.toIntOrNull() ?: series.year,
            coverUrl = doc.selectFirst("img[data-src]")?.attr("data-src")?.takeIf { it.isNotBlank() } ?: series.coverUrl,
            genres = doc.select("a[href*=genre]").map { it.text() },
            detailed = true,
        )
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val doc = document(seriesWebUrl(series.id))
        return doc.select("#chapters > div > a").mapNotNull { a ->
            val href = a.attr("href")
            val chapterPath = href.removePrefix("/chapters/").trim('/')
            if (chapterPath.isEmpty()) return@mapNotNull null
            SourceChapter(
                sourceId = id,
                seriesId = series.id,
                id = chapterPath,
                label = a.text(),
                language = "en",
                webUrl = "$baseUrl/chapters/$chapterPath",
            )
        }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val doc = document("$baseUrl/chapters/${chapter.id}")
        return doc.select("picture img").mapIndexedNotNull { i, img ->
            val src = img.attr("data-src").ifBlank { img.attr("src") }.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            ReaderPage(i, src, imageHeaders(src))
        }
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/manga/$seriesId"

    companion object {
        const val ID = "mangapill"
    }
}
