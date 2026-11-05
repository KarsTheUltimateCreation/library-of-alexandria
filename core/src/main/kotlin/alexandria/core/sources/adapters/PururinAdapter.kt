package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.HtmlAdapter
import alexandria.core.sources.ProviderRole
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

/**
 * Pururin (English doujinshi and hentai manga). HTML. The full search form is submitted (title query plus page
 * range) because a bare `?q=` is redirected to a tag page whenever the words match a tag name. Images are numbered
 * in the gallery's image directory (`i.pururin.me/<dir>/1.jpg` ... N), so pages need no viewer requests.
 *
 * Series and chapter ids are "galleryId/slug".
 */
class PururinAdapter(ctx: AdapterContext, override val baseUrl: String = "https://pururin.me") : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "Pururin"
    override val languages: Set<String> = setOf("en")
    override val adult = true
    override val role = ProviderRole.GALLERY
    override val imageHosts = setOf("i.pururin.me")
    override val limiter: RateLimiter = RateLimiter.shared("pururin", 3, 1_000)

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("start_page", "1")
            .addQueryParameter("last_page", "9999")
            .addQueryParameter("included_tags", "")
            .addQueryParameter("excluded_tags", "")
            .build()
        return list(document(url.toString()))
    }

    override suspend fun popular(page: Int): List<SourceSeries> = list(document("$baseUrl/browse?sort=most-popular&page=$page"))

    override suspend fun latest(page: Int): List<SourceSeries> = list(document("$baseUrl/browse?page=$page"))

    internal fun list(doc: Document): List<SourceSeries> {
        // "This tag has no results" pages still show suggestions; they are not results.
        if (doc.selectFirst(".box:contains(has no results)") != null) return emptyList()
        return doc.select("a.card-gallery[href*=/gallery/]").mapNotNull { a ->
            val sid = GALLERY.find(a.attr("abs:href"))?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: return@mapNotNull null
            val raw = a.selectFirst(".title h2")?.let { h -> h.textNodes().firstOrNull { it.text().isNotBlank() }?.text() }?.trim()
                ?: a.attr("title").substringBefore(" / ").trim()
            if (raw.isEmpty()) return@mapNotNull null
            SourceSeries(
                sourceId = id,
                id = sid,
                title = Galleries.cleanTitle(raw),
                altTitles = Galleries.titles(a.attr("title")).filter { !it.equals(Galleries.cleanTitle(raw), true) },
                related = Galleries.references(listOf(raw, a.attr("title"))),
                coverUrl = a.selectFirst("img.card-img-top")?.attr("abs:src")?.takeIf { it.isNotBlank() },
                kind = ContentKind.OTHER,
                status = PublicationStatus.COMPLETED,
                webUrl = seriesWebUrl(sid),
            )
        }.distinctBy { it.id }
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val doc = document(seriesWebUrl(series.id))
        fun row(label: String) = row(doc, label)
        val full = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val title = doc.selectFirst(".title h1")?.textNodes()?.firstOrNull { it.text().isNotBlank() }?.text()?.trim()
            ?: full.substringBefore(" / ").trim().ifEmpty { series.title }
        return series.copy(
            title = Galleries.cleanTitle(title),
            altTitles = Galleries.titles(full).filter { !it.equals(Galleries.cleanTitle(title), true) },
            related = Galleries.references(listOf(title, full), row("Parody")),
            coverUrl = doc.selectFirst("img.cover")?.attr("abs:src")?.takeIf { it.isNotBlank() } ?: series.coverUrl,
            authors = (row("Artist") + row("Circle")).distinct(),
            genres = (row("Category") + row("Contents")).distinct(),
            kind = ContentKind.OTHER,
            status = PublicationStatus.COMPLETED,
            description = listOfNotNull(
                row("Parody").takeIf { it.isNotEmpty() }?.let { "Parody: ${it.joinToString()}" },
                row("Character").takeIf { it.isNotEmpty() }?.let { "Characters: ${it.joinToString()}" },
                pageCount(doc)?.let { "$it pages" },
            ).joinToString("\n").ifEmpty { null },
            detailed = true,
        )
    }

    /** Links in the gallery info table row named [label] ("Circle", "Parody", "Contents", ...). */
    private fun row(doc: Document, label: String): List<String> = doc.select("table.table-info tr")
        .firstOrNull { it.selectFirst("td")?.text()?.trim().equals(label, ignoreCase = true) }
        ?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    private fun pageCount(doc: Document): Int? =
        doc.selectFirst("[itemprop=numberOfPages]")?.text()?.trim()?.toIntOrNull()
            ?: doc.select("table.table-info tr").firstOrNull { it.selectFirst("td")?.text()?.trim() == "Pages" }
                ?.select("td")?.getOrNull(1)?.text()?.trim()?.substringBefore(' ')?.toIntOrNull()

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val doc = document(seriesWebUrl(series.id))
        return listOf(Galleries.chapter(id, series.id, null, pageCount(doc), seriesWebUrl(series.id)))
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val count = chapter.pageCount ?: pageCount(document(seriesWebUrl(chapter.id))) ?: error("Pururin: unknown page count")
        val gid = chapter.id.substringBefore('/')
        val slug = chapter.id.substringAfter('/')
        // The reader names the first image; the others share its directory and extension.
        val reader = document("$baseUrl/read/$gid/01/$slug")
        val first = reader.select("img[src*=i.pururin.me]").map { it.attr("abs:src") }.firstOrNull { FIRST_IMAGE.containsMatchIn(it) }
            ?: Regex("""https://i\.pururin\.me/[^"'\s]+/1\.[a-z]+""").find(reader.html())?.value
            ?: error("Pururin: image directory not found")
        val dir = first.substringBeforeLast('/')
        val ext = first.substringAfterLast('.')
        return (1..count).map { n -> "$dir/$n.$ext".let { ReaderPage(n - 1, it, imageHeaders(it)) } }
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/gallery/$seriesId"

    companion object {
        const val ID = "pururin"
        private val GALLERY = Regex("""/gallery/(\d+)/([^/?#]+)""")
        private val FIRST_IMAGE = Regex("""/1\.[a-z]+$""")
    }
}
