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
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Hentai20 (English adult manhwa), a WordPress site on the MangaThemesia theme: search and series pages are HTML,
 * and the chapter page embeds its image list in `ts_reader.run({...})`.
 *
 * Series ids are slugs ("affair-with-my-boss"); chapter ids are chapter slugs ("affair-with-my-boss-chapter-25").
 */
class Hentai20Adapter(ctx: AdapterContext, override val baseUrl: String = "https://hentai20.io") : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "Hentai20"
    override val languages: Set<String> = setOf("en")
    override val adult = true
    override val imageHosts = setOf("hentai1.io", "hentai20.io")
    override val limiter: RateLimiter = RateLimiter.shared("hentai20", 3, 1_000)

    @Serializable
    private class ReaderSource(val images: List<String> = emptyList())

    @Serializable
    private class Reader(val sources: List<ReaderSource> = emptyList())

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        return list(document("$baseUrl/".toHttpUrl().newBuilder().addQueryParameter("s", query.trim()).build().toString()))
    }

    override suspend fun popular(page: Int): List<SourceSeries> = list(document("$baseUrl/manga/?page=$page&order=popular"))

    override suspend fun latest(page: Int): List<SourceSeries> = list(document("$baseUrl/manga/?page=$page&order=update"))

    internal fun list(doc: Document): List<SourceSeries> = doc.select(".listupd .bs .bsx > a, .bs .bsx > a").mapNotNull { a ->
        val slug = slugFrom(a.attr("abs:href")) ?: return@mapNotNull null
        SourceSeries(
            sourceId = id,
            id = slug,
            title = a.attr("title").trim().ifEmpty { a.selectFirst(".tt")?.text()?.trim().orEmpty() }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
            coverUrl = a.selectFirst("img")?.let { it.attr("abs:data-src").ifBlank { it.attr("abs:src") } }?.takeIf { it.isNotBlank() },
            kind = ContentKind.MANHWA,
            webUrl = seriesWebUrl(slug),
        )
    }.distinctBy { it.id }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val doc = document(seriesWebUrl(series.id))
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: series.title
        val alt = doc.selectFirst(".seriestualt, .alternative")?.text()?.split(',', '|', ';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val status = infoRow(doc, "Status")?.lowercase().orEmpty()
        return series.copy(
            title = title,
            altTitles = (series.altTitles + alt).filter { !it.equals(title, true) }.distinct(),
            coverUrl = doc.selectFirst(".thumb img")?.attr("abs:src")?.takeIf { it.isNotBlank() } ?: series.coverUrl,
            authors = listOfNotNull(infoRow(doc, "Author")?.takeIf { it.isNotBlank() && it != "-" }),
            genres = doc.select(".seriestugenre a, .mgen a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            status = when {
                "ongoing" in status -> PublicationStatus.ONGOING
                "completed" in status -> PublicationStatus.COMPLETED
                "hiatus" in status -> PublicationStatus.HIATUS
                "dropped" in status || "cancel" in status -> PublicationStatus.CANCELLED
                else -> series.status
            },
            description = doc.selectFirst("[itemprop=description]")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: series.description,
            detailed = true,
        )
    }

    private fun infoRow(doc: Document, label: String): String? = doc.select("table.infotable tr, .tsinfo .imptdt")
        .firstOrNull { it.selectFirst("td, h1, b")?.text()?.trim()?.startsWith(label, ignoreCase = true) == true }
        ?.let { it.select("td").getOrNull(1)?.text() ?: it.selectFirst("i, a")?.text() }?.trim()

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val doc = document(seriesWebUrl(series.id))
        return doc.select("#chapterlist li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("abs:href")
            val chapterSlug = href.removePrefix(baseUrl).trim('/').takeIf { it.isNotEmpty() && !it.contains('/') } ?: return@mapNotNull null
            SourceChapter(
                sourceId = id,
                seriesId = series.id,
                id = chapterSlug,
                label = li.selectFirst(".chapternum")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                number = li.attr("data-num").trim().takeIf { it.isNotEmpty() && it.first().isDigit() },
                language = "en",
                uploadedAt = li.selectFirst(".chapterdate")?.text()?.let(::parseDate),
                webUrl = href,
            )
        }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val html = ctx.client.fetchText(Request.Builder().url("$baseUrl/${chapter.id}/").headers(headers()).build(), limiter = limiter)
        return parsePages(html).mapIndexed { i, url -> ReaderPage(i, url, imageHeaders(url)) }
    }

    internal fun parsePages(html: String): List<String> {
        val raw = READER.find(html)?.groupValues?.get(1) ?: error("Hentai20: reader data not found")
        return ctx.json.decodeFromString(Reader.serializer(), raw).sources.firstOrNull { it.images.isNotEmpty() }?.images
            ?: error("Hentai20: no images")
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/manga/$seriesId/"

    private fun slugFrom(url: String): String? = url.substringAfter("/manga/", "").trim('/').substringBefore('/').takeIf { it.isNotBlank() }

    companion object {
        const val ID = "hentai20"
        private val READER = Regex("""ts_reader\.run\((\{.*?\})\);""", RegexOption.DOT_MATCHES_ALL)
        private val DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

        internal fun parseDate(raw: String): Long? =
            runCatching { LocalDate.parse(raw.trim(), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }
}
