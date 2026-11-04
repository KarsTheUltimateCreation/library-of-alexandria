package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.HttpError
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetch
import alexandria.core.net.fetchText
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceAdapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MangaTaro (mangataro.org), an English aggregator on WordPress with JSON endpoints. Search results already carry
 * authors and alternative titles, so matching needs no extra request.
 *
 * The chapter list needs a token derived from the current hour (`md5("<epoch>mng_ch_<yyyyMMddHH UTC>")`, first 16
 * hex digits) that the server accepts for about a minute; when the device clock is off, the server's `Date` header
 * corrects it and the request is retried once. Endpoints and the token scheme are adapted from
 * keiyoushi/extensions-source `lib-multisrc/mangataro` (Apache-2.0).
 *
 * Series id: "<numeric id>/<slug>". Chapter id: the numeric chapter id.
 */
class MangaTaroAdapter(
    private val ctx: AdapterContext,
    override val baseUrl: String = "https://mangataro.org",
    private val clock: () -> Long = System::currentTimeMillis,
) : SourceAdapter {
    override val id = ID
    override val name = "MangaTaro"
    override val languages: Set<String> = setOf("en")
    override val imageHosts = setOf("mangataro.yachts", "mangataro.org")
    private val limiter = RateLimiter.shared(ID, 2, 1_000)

    @Volatile private var serverOffsetMs = 0L

    @Serializable
    internal class SearchResponse(val results: List<Hit> = emptyList())

    @Serializable
    internal class Hit(
        val id: Long,
        val slug: String,
        val title: String,
        val thumbnail: String? = null,
        val type: String? = null,
        val description: String? = null,
        val status: String? = null,
        @SerialName("alt_titles") val altTitles: List<String> = emptyList(),
        val authors: List<String> = emptyList(),
        val year: String? = null,
    )

    @Serializable
    internal class ChapterList(val chapters: List<Chapter> = emptyList(), @SerialName("has_more") val hasMore: Boolean = false)

    @Serializable
    internal class Chapter(
        val id: String? = null,
        val url: String,
        val chapter: String,
        val title: String? = null,
        val language: String = "en",
        @SerialName("group_name") val groupName: String? = null,
    )

    @Serializable
    internal class Pages(val images: List<String> = emptyList())

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", ctx.browserUserAgent)
        .header("Accept", "application/json")
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Referer", "$baseUrl/")

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        val body = buildJsonObject { put("limit", 25); put("query", query.trim()) }.toString().toRequestBody(JSON)
        return parseSearch(ctx.client.fetchText(request("$baseUrl/auth/search").post(body).build(), limiter = limiter))
    }

    internal fun parseSearch(json: String): List<SourceSeries> =
        ctx.json.decodeFromString<SearchResponse>(json).results.filter { !it.type.equals("novel", true) }.map { h ->
            val title = unescape(h.title)
            SourceSeries(
                sourceId = ID, id = "${h.id}/${h.slug}", title = title,
                altTitles = h.altTitles.map(::unescape).filter { it.isNotBlank() && !it.equals(title, true) },
                coverUrl = h.thumbnail?.takeIf { it.startsWith("http") },
                kind = ContentKind.parse(h.type),
                status = PublicationStatus.parse(h.status),
                year = h.year?.trim()?.toIntOrNull()?.takeIf { it in 1900..2100 },
                description = h.description?.let(::unescape)?.trim()?.ifEmpty { null },
                authors = h.authors.map { it.trim() }.filter { it.isNotEmpty() },
                webUrl = "$baseUrl/manga/${h.slug}",
                // Search already reports what matching needs (authors, alternative titles).
                detailed = h.authors.isNotEmpty(),
            )
        }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val mangaId = series.id.substringBefore('/')
        val all = mutableListOf<Chapter>()
        var offset = 0
        while (true) {
            val page = chapterPage(mangaId, offset)
            all += page.chapters
            offset = all.size
            if (!page.hasMore || page.chapters.isEmpty() || offset >= MAX_CHAPTERS) break
        }
        return parseChapters(series.id, all)
    }

    private suspend fun chapterPage(mangaId: String, offset: Int): ChapterList {
        suspend fun attempt(): String = ctx.client.fetchText(request(chapterUrl(mangaId, offset)).build(), retries = 0, limiter = limiter)
        val text = try {
            attempt()
        } catch (e: HttpError) {
            if (e.code != 403) throw e
            // The token is tied to the server's clock: learn it from the Date header and try once more.
            val serverTime = runCatching {
                ctx.client.fetch(request("$baseUrl/").head().build(), retries = 0, limiter = limiter).use { r -> r.headers.getDate("Date")?.time }
            }.getOrNull() ?: throw e
            serverOffsetMs = serverTime - clock()
            attempt()
        }
        return ctx.json.decodeFromString(text)
    }

    internal fun chapterUrl(mangaId: String, offset: Int): String {
        val now = Instant.ofEpochMilli(clock() + serverOffsetMs)
        val token = md5("${now.epochSecond}mng_ch_${HOUR.format(now)}").substring(0, 16)
        return "$baseUrl/auth/manga-chapters".toHttpUrl().newBuilder()
            .addQueryParameter("manga_id", mangaId).addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", "500").addQueryParameter("order", "DESC")
            .addQueryParameter("_t", token).addQueryParameter("_ts", now.epochSecond.toString())
            .build().toString()
    }

    internal fun parseChapters(seriesId: String, list: List<Chapter>): List<SourceChapter> = list
        .filter { it.language.equals("en", true) }
        .mapNotNull { c ->
            val chapterId = c.url.trimEnd('/').substringAfterLast('/').substringAfterLast('-').takeIf { it.isNotEmpty() } ?: c.id ?: return@mapNotNull null
            val n = c.chapter.trim().removeSuffix(".0")
            val title = c.title?.let(::unescape)?.trim()?.takeUnless { it in PLACEHOLDERS }
            SourceChapter(
                sourceId = ID, seriesId = seriesId, id = chapterId,
                label = if (title != null) "Chapter $n: $title" else "Chapter $n",
                number = n.takeIf { it.toDoubleOrNull() != null },
                title = title,
                group = c.groupName?.trim()?.takeUnless { it in PLACEHOLDERS },
                webUrl = c.url,
            )
        }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val url = "$baseUrl/auth/chapter-content".toHttpUrl().newBuilder().addQueryParameter("chapter_id", chapter.id).build().toString()
        return parsePages(ctx.client.fetchText(request(url).build(), limiter = limiter))
    }

    internal fun parsePages(json: String): List<ReaderPage> {
        val images = ctx.json.decodeFromString<Pages>(json).images
        check(images.isNotEmpty()) { "MangaTaro returned no pages" }
        return images.mapIndexed { i, u -> ReaderPage(i, u, imageHeaders(u)) }
    }

    override fun imageHeaders(imageUrl: String) = mapOf("Referer" to "$baseUrl/", "User-Agent" to ctx.browserUserAgent)

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/manga/${seriesId.substringAfter('/')}"

    private fun unescape(s: String) = Parser.unescapeEntities(Jsoup.parseBodyFragment(s).text(), false)

    companion object {
        const val ID = "loa_mangataro"
        private const val MAX_CHAPTERS = 5_000
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH", Locale.US).withZone(ZoneOffset.UTC)
        private val PLACEHOLDERS = setOf("", "N/A", "—", "-")

        internal fun md5(input: String): String =
            MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
