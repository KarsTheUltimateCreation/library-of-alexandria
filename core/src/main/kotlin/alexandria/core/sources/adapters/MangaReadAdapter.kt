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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * MangaRead.org (English), a WordPress "Madara" theme site. Selectors and the `ajax/chapters/` chapter-list
 * request follow keiyoushi/extensions-source `lib-multisrc/madara` (Apache-2.0). Series ids are the slug
 * ("one-piece"); chapter ids are "slug/chapter-slug".
 */
class MangaReadAdapter(ctx: AdapterContext, override val baseUrl: String = "https://www.mangaread.org") : HtmlAdapter(ctx) {
    override val id = ID
    override val name = "MangaRead"
    override val languages: Set<String> = setOf("en")
    override val limiter: RateLimiter = RateLimiter.shared("mangaread", 2, 1_000)

    override suspend fun search(query: String): List<SourceSeries> {
        val url = "$baseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")
            .build()
        // WordPress search also matches descriptions; keep hits whose titles share a word with the query.
        return document(url.toString()).select("div.c-tabs-item__content").mapNotNull { searchCard(it) }.distinctBy { it.id }
            .filter { hit -> sharesWord(query, hit.allTitles()) }
    }

    override suspend fun popular(page: Int) = list("$baseUrl/manga/page/$page/?m_orderby=views")

    override suspend fun latest(page: Int) = list("$baseUrl/manga/page/$page/?m_orderby=latest")

    private suspend fun list(url: String): List<SourceSeries> =
        document(url).select("div.page-item-detail").mapNotNull { el ->
            val a = el.selectFirst(".post-title a") ?: return@mapNotNull null
            val slug = slugFrom(a.attr("abs:href")) ?: return@mapNotNull null
            SourceSeries(
                sourceId = id,
                id = slug,
                title = a.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                coverUrl = el.selectFirst("img")?.imageUrl(),
                webUrl = seriesWebUrl(slug),
            )
        }.distinctBy { it.id }

    private fun searchCard(el: Element): SourceSeries? {
        val a = el.selectFirst(".post-title a") ?: return null
        val slug = slugFrom(a.attr("abs:href")) ?: return null
        fun item(cls: String) = el.selectFirst(".post-content_item.$cls .summary-content")
        return SourceSeries(
            sourceId = id,
            id = slug,
            title = a.text().takeIf { it.isNotBlank() } ?: return null,
            altTitles = splitAlt(item("mg_alternative")?.text()),
            coverUrl = el.selectFirst(".tab-thumb img")?.imageUrl(),
            authors = item("mg_author")?.select("a")?.map { it.text() }.orEmpty(),
            status = PublicationStatus.parse(item("mg_status")?.text()),
            year = el.selectFirst(".post-content_item.mg_release .summary-content, .release-year")?.text()?.trim()?.toIntOrNull(),
            webUrl = seriesWebUrl(slug),
        )
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val doc = document(seriesWebUrl(series.id))
        val fields = summaryFields(doc)
        fun field(vararg names: String) = names.firstNotNullOfOrNull { n -> fields.entries.firstOrNull { it.key.startsWith(n, true) }?.value }
        // Madara titles may carry badge spans ("HOT", "SS"); take the heading's own text.
        val title = doc.selectFirst(".post-title h1")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
        val cover = doc.selectFirst(".summary_image img")?.imageUrl()?.takeUnless { it.endsWith(".gif", true) }
        return series.copy(
            title = title ?: series.title,
            altTitles = (series.altTitles + splitAlt(field("Alternative")?.text())).distinct().filterNot { it.equals(title ?: series.title, true) },
            coverUrl = series.coverUrl?.let(::fullSizeCover) ?: cover,
            authors = field("Author")?.select("a")?.map { it.text() }?.ifEmpty { null } ?: series.authors,
            genres = field("Genre")?.select("a")?.map { it.text() }.orEmpty(),
            kind = ContentKind.parse(field("Type")?.text()).takeIf { it != ContentKind.UNKNOWN } ?: series.kind,
            status = PublicationStatus.parse(field("Status")?.text()).takeIf { it != PublicationStatus.UNKNOWN } ?: series.status,
            year = field("Release")?.text()?.trim()?.toIntOrNull() ?: series.year,
            description = doc.selectFirst("div.summary__content")?.select("p")
                ?.map { it.text() }?.filterNot { it.startsWith("A brief description", true) }?.joinToString("\n")?.ifBlank { null }
                ?: series.description,
            detailed = true,
        )
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val request = Request.Builder()
            .url("${seriesWebUrl(series.id)}ajax/chapters/")
            .headers(headers().newBuilder().set("Referer", seriesWebUrl(series.id)).add("X-Requested-With", "XMLHttpRequest").build())
            .post(ByteArray(0).toRequestBody())
            .build()
        return document(request).select("li.wp-manga-chapter").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("abs:href")
            val chapterId = href.substringAfter("/manga/", "").trim('/').takeIf { it.contains('/') } ?: return@mapNotNull null
            SourceChapter(
                sourceId = id,
                seriesId = series.id,
                id = chapterId,
                label = a.text(),
                language = "en",
                uploadedAt = li.selectFirst(".chapter-release-date")?.text()?.let(::parseDate),
                webUrl = href,
            )
        }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val doc = document("$baseUrl/manga/${chapter.id}/")
        return doc.select("div.reading-content .page-break img").mapNotNull { it.imageUrl() }
            .mapIndexed { i, src -> ReaderPage(i, src, imageHeaders(src)) }
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/manga/$seriesId/"

    private fun summaryFields(doc: Document): Map<String, Element> =
        doc.select(".post-content_item").mapNotNull { item ->
            val key = item.selectFirst(".summary-heading")?.text()?.trim() ?: return@mapNotNull null
            val value = item.selectFirst(".summary-content") ?: return@mapNotNull null
            key to value
        }.toMap()

    private fun slugFrom(url: String): String? =
        url.substringAfter("/manga/", "").trim('/').takeIf { it.isNotEmpty() && '/' !in it && '?' !in it }

    companion object {
        const val ID = "mangaread"
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        private val THUMB_SIZE = Regex("""-\d{2,4}x\d{2,4}(\.\w+)$""")

        internal fun parseDate(raw: String): Long? =
            runCatching { LocalDate.parse(raw.trim(), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

        private val WORD = Regex("""[\p{L}\p{N}]+""")

        private fun words(s: String) = WORD.findAll(s.lowercase()).map { it.value }.filter { it.length >= 3 || it.any(Char::isDigit) }.toSet()

        /** True when no query word is usable (very short or non-Latin queries) or some title shares one. */
        internal fun sharesWord(query: String, titles: List<String>): Boolean {
            val q = words(query)
            return q.isEmpty() || titles.any { t -> words(t).any { it in q } } || titles.any { t -> t.contains(query.trim(), ignoreCase = true) }
        }

        /** WordPress thumbnails are "name-193x278.jpg"; the original upload is "name.jpg". */
        internal fun fullSizeCover(url: String): String = url.replace(THUMB_SIZE, "$1")

        private fun splitAlt(raw: String?): List<String> =
            raw?.split(";", " / ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        /** Lazy-load aware; Madara often pads the URL with whitespace (`src=" https://..."`). */
        private fun Element.imageUrl(): String? =
            listOf("data-src", "data-lazy-src", "src").asSequence()
                .map { attr(it).trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }
                ?.let { if (it.startsWith("http")) it else absUrl(it).ifBlank { null } ?: it }
    }
}
