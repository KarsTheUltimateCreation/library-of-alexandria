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
import alexandria.core.sources.ProviderRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document

/**
 * E-Hentai (galleries: doujinshi, manga, artist CG, western), English galleries only (`language:english$`).
 * Search is the HTML gallery list; details come from the official JSON API (`gdata`). The gallery page links one
 * viewer page per image (20 per gallery page) and only the viewer page names the image, which is served by a
 * rotating Hentai@Home node, so pages are handed out lazily ([ReaderPage.lazy]) and resolved one at a time while
 * reading ([resolveImage]) instead of fetching every viewer page up front.
 *
 * Series and chapter ids are "gid/token".
 */
class EHentaiAdapter(ctx: AdapterContext, override val baseUrl: String = "https://e-hentai.org") : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "E-Hentai"
    override val languages: Set<String> = setOf("en")
    override val adult = true
    override val role = ProviderRole.GALLERY
    override val imageHosts = setOf("ehgt.org", "hath.network")
    // E-Hentai temporarily bans clients that load pages too quickly.
    override val limiter: RateLimiter = RateLimiter.shared("ehentai", 2, 1_000)

    @Serializable
    private class Meta(
        val gid: Long,
        val token: String,
        val title: String,
        val title_jpn: String? = null,
        val category: String? = null,
        val thumb: String? = null,
        val posted: String? = null,
        val filecount: String? = null,
        val tags: List<String> = emptyList(),
        val expunged: Boolean = false,
    )

    @Serializable
    private class MetaResponse(val gmetadata: List<Meta> = emptyList())

    /** Skips the "offensive content" interstitial some galleries show first. */
    override fun headers(): Headers = super.headers().newBuilder().add("Cookie", "nw=1").build()

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        val url = "$baseUrl/".toHttpUrl().newBuilder().addQueryParameter("f_search", "language:english$ ${query.trim()}").build()
        return list(document(url.toString()))
    }

    override suspend fun latest(page: Int): List<SourceSeries> =
        list(document("$baseUrl/".toHttpUrl().newBuilder().addQueryParameter("f_search", "language:english$").build().toString()))

    override suspend fun popular(page: Int): List<SourceSeries> =
        list(document("$baseUrl/popular".toHttpUrl().newBuilder().addQueryParameter("f_search", "language:english$").build().toString()))

    internal fun list(doc: Document): List<SourceSeries> = doc.select("table.itg tr, div.itg div.gl1t").mapNotNull { row ->
        val a = row.selectFirst("a[href*=/g/]") ?: return@mapNotNull null
        val gallery = GALLERY.find(a.attr("abs:href")) ?: return@mapNotNull null
        val raw = row.selectFirst(".glink")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val sid = "${gallery.groupValues[1]}/${gallery.groupValues[2]}"
        val img = row.selectFirst("img")
        SourceSeries(
            sourceId = id,
            id = sid,
            title = Galleries.cleanTitle(raw),
            altTitles = Galleries.titles(raw).drop(1),
            related = Galleries.references(listOf(raw)),
            coverUrl = img?.let { it.attr("abs:data-src").ifBlank { it.attr("abs:src") } }?.takeIf { it.startsWith("http") },
            kind = ContentKind.OTHER,
            status = PublicationStatus.COMPLETED,
            webUrl = seriesWebUrl(sid),
        )
    }.distinctBy { it.id }

    private suspend fun meta(seriesId: String): Meta {
        val (gid, token) = seriesId.split('/').let { it[0].toLong() to it[1] }
        val body = buildJsonObject {
            put("method", "gdata")
            putJsonArray("gidlist") { addJsonArray { add(gid); add(token) } }
            put("namespace", 1)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(API).post(body).header("User-Agent", ctx.browserUserAgent).build()
        return ctx.json.decodeFromString(MetaResponse.serializer(), ctx.client.fetchText(req, limiter = limiter)).gmetadata.firstOrNull()
            ?: error("E-Hentai: gallery $seriesId not found")
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val m = meta(series.id)
        fun tags(ns: String) = m.tags.filter { it.startsWith("$ns:") }.map { it.substringAfter(':') }
        return series.copy(
            title = Galleries.cleanTitle(m.title),
            altTitles = Galleries.titles(m.title, m.title_jpn).drop(1),
            related = Galleries.references(listOf(m.title, m.title_jpn), tags("parody")),
            coverUrl = m.thumb ?: series.coverUrl,
            authors = tags("artist") + tags("group"),
            genres = listOfNotNull(m.category) + m.tags.filter { it.substringBefore(':') in setOf("female", "male", "mixed", "other") },
            kind = ContentKind.OTHER,
            status = PublicationStatus.COMPLETED,
            description = listOfNotNull(
                tags("parody").takeIf { it.isNotEmpty() }?.let { "Parody: ${it.joinToString()}" },
                tags("character").takeIf { it.isNotEmpty() }?.let { "Characters: ${it.joinToString()}" },
                m.filecount?.let { "$it pages" },
            ).joinToString("\n").ifEmpty { null },
            detailed = true,
        )
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val m = meta(series.id)
        if (m.expunged) return emptyList()
        return listOf(Galleries.chapter(id, series.id, m.posted?.toLongOrNull()?.times(1000), m.filecount?.toIntOrNull(), seriesWebUrl(series.id)))
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val first = document(seriesWebUrl(chapter.id))
        val firstLinks = viewerLinks(first)
        if (firstLinks.isEmpty()) error("E-Hentai: no pages listed")
        val total = chapter.pageCount ?: first.select("td.gdt2:contains(pages)").text().filter(Char::isDigit).toIntOrNull() ?: firstLinks.size
        val galleryPages = ((total + firstLinks.size - 1) / firstLinks.size).coerceIn(1, MAX_GALLERY_PAGES)
        val rest = coroutineScope {
            (1 until galleryPages).map { p -> async { viewerLinks(document("${seriesWebUrl(chapter.id)}?p=$p")) } }.awaitAll()
        }
        return (firstLinks + rest.flatten()).distinct().mapIndexed { i, url -> ReaderPage(i, url, lazy = true) }
    }

    private fun viewerLinks(doc: Document): List<String> =
        doc.select("#gdt a[href*=/s/]").map { it.attr("abs:href") }.filter { VIEWER.containsMatchIn(it) }

    override suspend fun resolveImage(pageUrl: String): ReaderPage {
        val doc = document(pageUrl)
        val src = doc.selectFirst("img#img")?.attr("abs:src")?.takeIf { it.isNotBlank() } ?: error("E-Hentai: image not found on $pageUrl")
        val index = pageUrl.substringAfterLast('-').toIntOrNull()?.minus(1) ?: 0
        return ReaderPage(index, src, imageHeaders(src))
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/g/$seriesId/"

    companion object {
        const val ID = "ehentai"
        private const val API = "https://api.e-hentai.org/api.php"
        private const val MAX_GALLERY_PAGES = 50
        private val GALLERY = Regex("""/g/(\d+)/([0-9a-f]+)""")
        private val VIEWER = Regex("""/s/[0-9a-f]+/\d+-\d+""")
    }
}
