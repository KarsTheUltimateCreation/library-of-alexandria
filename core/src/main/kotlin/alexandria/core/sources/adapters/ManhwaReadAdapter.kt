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
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * ManhwaRead (English, adult manhwa). HTML; selectors and the page-data format follow
 * keiyoushi/extensions-source `src/en/manhwaread` (Apache-2.0).
 *
 * The site sits behind a Cloudflare challenge. Requests go through the host app's client, which solves it
 * (Mihon: WebView-backed Cloudflare interceptor); a host that cannot solve it simply gets no results here
 * and the provider is skipped by the health tracker.
 *
 * Series ids are slugs ("dont-tell-mom"); chapter ids are "slug/chapter-path".
 */
class ManhwaReadAdapter(ctx: AdapterContext, override val baseUrl: String = "https://manhwaread.com") : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "ManhwaRead"
    override val languages: Set<String> = setOf("en")
    override val adult = true
    override val limiter: RateLimiter = RateLimiter.shared("manhwaread", 2, 1_000)

    @Serializable
    private class ChapterData(val data: String, val base: String)

    @Serializable
    private class Image(val src: String)

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        return list(document("$baseUrl/".toHttpUrl().newBuilder().addQueryParameter("s", query.trim()).build().toString()))
    }

    private fun list(doc: Document): List<SourceSeries> = doc.select(".main-container .manga-item").mapNotNull(::card).distinctBy { it.id }

    private fun card(el: Element): SourceSeries? {
        val a = el.selectFirst("a.manga-item__link") ?: return null
        val slug = slugFrom(a.attr("abs:href")) ?: return null
        return SourceSeries(
            sourceId = id,
            id = slug,
            title = a.text().trim().takeIf { it.isNotEmpty() } ?: return null,
            coverUrl = el.selectFirst(".manga-item__img img")?.let { it.attr("abs:data-src").ifBlank { it.attr("abs:src") } }?.takeIf { it.isNotBlank() },
            kind = ContentKind.MANHWA,
            webUrl = seriesWebUrl(slug),
        )
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        return parseDetails(document(seriesWebUrl(series.id)), series)
    }

    private fun parseDetails(doc: Document, series: SourceSeries): SourceSeries {
        val alt = doc.selectFirst("#mangaSummary .manga-titles h2")?.text()?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val genres = doc.select("#mangaSummary .manga-genres a").map { it.text() } +
            doc.select("#mangaSummary .text-primary:contains(Tags:) + .flex a span:first-child").map { it.text() }
        val title = doc.selectFirst("#mangaSummary .manga-titles h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: series.title
        return series.copy(
            title = title,
            altTitles = (series.altTitles + alt).filter { !it.equals(title, true) }.distinct(),
            coverUrl = doc.selectFirst("head meta[property=og:image]")?.attr("abs:content")?.takeIf { it.isNotBlank() } ?: series.coverUrl,
            authors = doc.select("#mangaSummary .text-primary:contains(Author:) + .flex a span:first-child").map { it.text() },
            genres = genres.distinct(),
            status = when (doc.selectFirst("#mangaSummary .manga-status")?.attr("data-status")) {
                "ongoing" -> PublicationStatus.ONGOING
                "completed" -> PublicationStatus.COMPLETED
                "canceled" -> PublicationStatus.CANCELLED
                "on-hold" -> PublicationStatus.HIATUS
                else -> series.status
            },
            description = doc.selectFirst("#mangaDesc > .manga-desc__content")?.text() ?: series.description,
            detailed = true,
        )
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val doc = document(seriesWebUrl(series.id))
        return doc.select("#chaptersList > a.chapter-item").mapNotNull { a ->
            val href = a.attr("abs:href")
            val path = href.substringAfter("/manhwa/", "").trim('/').takeIf { it.contains('/') } ?: return@mapNotNull null
            SourceChapter(
                sourceId = id,
                seriesId = series.id,
                id = path,
                label = a.selectFirst("span.chapter-item__name")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                language = "en",
                uploadedAt = a.selectFirst("span.chapter-item__date")?.text()?.let(::parseDate),
                webUrl = href,
            )
        }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val request = Request.Builder().url("$baseUrl/manhwa/${chapter.id}/").headers(headers()).build()
        val html = ctx.client.fetchText(request, limiter = limiter)
        return parsePages(html).mapIndexed { i, url -> ReaderPage(i, url, imageHeaders(url)) }
    }

    internal fun parsePages(html: String): List<String> {
        val raw = CHAPTER_DATA.find(html)?.groupValues?.get(1) ?: error("ManhwaRead: chapter data not found")
        val data = ctx.json.decodeFromString(ChapterData.serializer(), raw)
        val decoded = String(Base64.getMimeDecoder().decode(data.data))
        return ctx.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Image.serializer()), decoded)
            .map { "${data.base.trimEnd('/')}/${it.src.trimStart('/')}" }
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/manhwa/$seriesId/"

    private fun slugFrom(url: String): String? =
        url.substringAfter("/manhwa/", "").trim('/').substringBefore('/').takeIf { it.isNotBlank() }

    companion object {
        const val ID = "manhwaread"
        private val CHAPTER_DATA = Regex("""var\s+chapterData\s*=\s*(\{.*\})""")
        private val DATE = DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ROOT)

        internal fun parseDate(raw: String): Long? =
            runCatching { LocalDate.parse(raw.trim(), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }
}
