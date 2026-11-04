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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MangaKatana (English). Request and parsing approach follows keiyoushi/extensions-source `src/en/mangakatana`
 * (Apache-2.0). Series ids are the "slug.number" path segment ("one-piece.49"); chapter ids are
 * "slug.number/c1000". Page images are listed in an inline script array (a decoy array sits next to it).
 */
class MangaKatanaAdapter(ctx: AdapterContext, override val baseUrl: String = "https://mangakatana.com") : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "MangaKatana"
    override val languages: Set<String> = setOf("en")
    override val limiter: RateLimiter = RateLimiter.shared("mangakatana", 2, 1_000)

    override suspend fun search(query: String): List<SourceSeries> {
        val url = "$baseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("search_by", "book_name")
            .build()
        val doc = document(url.toString())
        // An exact match may redirect straight to the series page.
        if (doc.selectFirst("h1.heading") != null && doc.selectFirst("#book_list") == null) {
            val canonical = doc.selectFirst("meta[property=og:url]")?.attr("content") ?: doc.location()
            val sid = seriesIdFrom(canonical) ?: return emptyList()
            return listOf(parseDetails(doc, SourceSeries(sourceId = id, id = sid, title = "", webUrl = seriesWebUrl(sid))))
        }
        return list(doc)
    }

    override suspend fun popular(page: Int) = list(document("$baseUrl/manga/page/$page?order=numc"))

    override suspend fun latest(page: Int) = list(document("$baseUrl/latest/page/$page"))

    private fun list(doc: Document): List<SourceSeries> =
        doc.select("#book_list > div.item").mapNotNull { card(it) }.distinctBy { it.id }

    private fun card(el: Element): SourceSeries? {
        val a = el.selectFirst("h3.title > a") ?: return null
        val sid = seriesIdFrom(a.attr("abs:href")) ?: return null
        return SourceSeries(
            sourceId = id,
            id = sid,
            title = a.text().takeIf { it.isNotBlank() } ?: return null,
            coverUrl = el.selectFirst(".wrap_img img")?.attr("abs:src")?.takeIf { it.isNotBlank() },
            status = PublicationStatus.parse(el.selectFirst(".status")?.text()),
            genres = el.select(".genres a").map { it.text() },
            kind = kindFromGenres(el.select(".genres a").map { it.text() }),
            webUrl = seriesWebUrl(sid),
        )
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        return parseDetails(document(seriesWebUrl(series.id)), series)
    }

    private fun parseDetails(doc: Document, series: SourceSeries): SourceSeries {
        val alt = doc.selectFirst(".alt_name")?.text()?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val genres = doc.select(".genres > a").map { it.text() }
        return series.copy(
            title = doc.selectFirst("h1.heading")?.text()?.takeIf { it.isNotBlank() } ?: series.title,
            altTitles = (series.altTitles + alt).distinct(),
            coverUrl = doc.selectFirst("div.media div.cover img")?.attr("abs:src")?.takeIf { it.isNotBlank() } ?: series.coverUrl,
            authors = doc.select(".authors > a").map { it.text() },
            genres = genres.ifEmpty { series.genres },
            kind = kindFromGenres(genres).takeIf { it != ContentKind.UNKNOWN } ?: series.kind,
            status = PublicationStatus.parse(doc.selectFirst(".value.status")?.text()).takeIf { it != PublicationStatus.UNKNOWN } ?: series.status,
            description = doc.selectFirst(".summary > p")?.text() ?: series.description,
            detailed = true,
        )
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val doc = document(seriesWebUrl(series.id))
        return doc.select(".chapters tr:has(.chapter)").mapNotNull { tr ->
            val a = tr.selectFirst(".chapter a") ?: return@mapNotNull null
            val href = a.attr("abs:href")
            val chapterId = href.substringAfter("/manga/", "").trim('/').takeIf { it.contains('/') } ?: return@mapNotNull null
            SourceChapter(
                sourceId = id,
                seriesId = series.id,
                id = chapterId,
                label = a.text(),
                language = "en",
                uploadedAt = tr.selectFirst(".update_time")?.text()?.let(::parseDate),
                webUrl = href,
            )
        }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val doc = document("$baseUrl/manga/${chapter.id}")
        val script = doc.select("script").map { it.data() }.firstOrNull { IMAGE_ARRAY_NAME.containsMatchIn(it) }
            ?: error("MangaKatana: image script not found")
        val arrayName = IMAGE_ARRAY_NAME.find(script)!!.groupValues[1]
        val body = Regex("""var\s+${Regex.escape(arrayName)}\s*=\s*\[([^\]]*)]""").find(script)?.groupValues?.get(1)
            ?: error("MangaKatana: image array $arrayName not found")
        return QUOTED.findAll(body).map { it.groupValues[1] }.filter { it.isNotBlank() }
            .mapIndexed { i, src -> ReaderPage(i, src, imageHeaders(src)) }.toList()
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/manga/$seriesId"

    private fun seriesIdFrom(url: String): String? =
        url.substringAfter("/manga/", "").substringBefore('/').substringBefore('?').takeIf { SERIES_ID.matches(it) }

    companion object {
        const val ID = "mangakatana"
        private val SERIES_ID = Regex("""[^/]+\.\d+""")
        private val IMAGE_ARRAY_NAME = Regex("""data-src['"]\s*,\s*(\w+)""")
        private val QUOTED = Regex("""['"]([^'"]+)['"]""")
        private val DATE = DateTimeFormatter.ofPattern("MMM-dd-yyyy", Locale.US)

        internal fun parseDate(raw: String): Long? =
            runCatching { LocalDate.parse(raw.trim(), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

        internal fun kindFromGenres(genres: List<String>): ContentKind {
            val g = genres.map { it.lowercase() }
            return when {
                g.any { "manhwa" in it || "webtoon" in it } -> ContentKind.MANHWA
                g.any { "manhua" in it } -> ContentKind.MANHUA
                else -> ContentKind.UNKNOWN
            }
        }
    }
}
