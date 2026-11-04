package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.HttpError
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceAdapter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.Instant

/**
 * Hiperdex (hiperdex.tv, English, mostly adult manhwa). tRPC JSON API; request shapes follow
 * keiyoushi/extensions-source `lib-multisrc/hiper` (Apache-2.0).
 *
 * The API answers 401 until the site's session cookie is set, which happens on an ordinary visit to the home
 * page (the host app's cookie jar keeps it). The page-list endpoint refuses non-browser clients, so pages are
 * derived from the chapter page instead: it embeds the first images of the chapter directory
 * (`.../<dir>/01.webp`), and the chapter list reports each chapter's page count; images are numbered 01..N.
 *
 * Series ids are "numericId/slug"; chapter ids are "slug/number/pageCount".
 */
class HiperdexAdapter(
    private val ctx: AdapterContext,
    override val baseUrl: String = "https://hiperdex.tv",
) : SourceAdapter {
    override val id = ID
    override val name = "Hiperdex"
    override val languages: Set<String> = setOf("en")
    override val adult = true
    override val imageHosts = setOf("r2d2storage.com")
    private val limiter = RateLimiter.shared("hiperdex", 3, 1_000)
    private val sessionLock = Mutex()

    @Serializable
    private class Hit(
        val id: Long,
        val slug: String,
        val title: String,
        val alternativeTitles: JsonElement? = null,
        val synopsis: String? = null,
        val coverUrl: String? = null,
        val type: String? = null,
        val status: String? = null,
        val contentRating: String? = null,
        val year: Int? = null,
        val genres: List<String>? = null,
        val authors: List<String>? = null,
    )

    @Serializable
    private class Hits(val hits: List<Hit> = emptyList())

    @Serializable
    private class Chapter(
        val id: Long,
        val number: Double,
        val title: String? = null,
        val language: String? = null,
        val status: String? = null,
        val pagesCount: Int? = null,
        val isDeleted: Boolean = false,
        val createdAt: String? = null,
    )

    private fun request(url: HttpUrl, accept: String) = Request.Builder().url(url)
        .header("User-Agent", ctx.browserUserAgent)
        .header("Accept", accept)
        .header("Referer", "$baseUrl/")
        .build()

    /** Fetches [url]; on 401 visits the home page once (sets the session cookie) and retries. */
    private suspend fun fetch(url: HttpUrl, accept: String = "application/json"): String = try {
        ctx.client.fetchText(request(url, accept), limiter = limiter)
    } catch (e: HttpError) {
        if (e.code != 401) throw e
        sessionLock.withLock {
            ctx.client.fetchText(request(baseUrl.toHttpUrl(), "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"), limiter = limiter)
        }
        ctx.client.fetchText(request(url, accept), limiter = limiter)
    }

    /** tRPC batch call; returns the `result.data.json` of the last procedure. */
    private suspend fun trpc(procedures: String, input: JsonObject): JsonElement? {
        val url = "$baseUrl/api/trpc/$procedures".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", input.toString())
            .build()
        val last = ctx.json.parseToJsonElement(fetch(url)).jsonArray.last().jsonObject
        last["error"]?.let { error("Hiperdex: ${it.toString().take(200)}") }
        return (last["result"] as? JsonObject)?.get("data")?.let { (it as? JsonObject)?.get("json") }?.takeUnless { it is JsonNull }
    }

    private suspend fun searchQuery(query: String, sort: String, page: Int): List<SourceSeries> {
        val undefined = buildJsonArray { add("undefined") }
        val input = buildJsonObject {
            putJsonObject("0") {
                putJsonObject("json") {
                    put("q", query)
                    put("sort", sort)
                    putJsonObject("filters") {
                        listOf("genres", "type", "status", "contentRating", "author", "artist", "year").forEach { put(it, JsonNull) }
                    }
                    put("limit", PAGE_SIZE)
                    put("offset", (page - 1) * PAGE_SIZE)
                    put("maxRating", "pornographic")
                }
                putJsonObject("meta") {
                    putJsonObject("values") {
                        listOf("genres", "type", "status", "contentRating", "author", "artist", "year").forEach { put("filters.$it", undefined) }
                    }
                }
            }
        }
        val data = trpc("search.query", input) ?: return emptyList()
        return ctx.json.decodeFromJsonElement<Hits>(data).hits.map { it.toSeries() }
    }

    override suspend fun search(query: String): List<SourceSeries> =
        if (query.isBlank()) emptyList() else searchQuery(query.trim(), "relevance", 1)

    override suspend fun popular(page: Int) = searchQuery("", "popular", page)

    override suspend fun latest(page: Int) = searchQuery("", "recent", page)

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val input = buildJsonObject {
            putJsonObject("0") {
                put("json", JsonNull)
                putJsonObject("meta") { put("values", buildJsonArray { add("undefined") }) }
            }
            putJsonObject("1") { putJsonObject("json") { put("slug", slugOf(series.id)) } }
        }
        val data = trpc("auth.me,series.bySlugWithGenres", input) ?: return series.copy(detailed = true)
        val fresh = ctx.json.decodeFromJsonElement<Hit>(data).toSeries()
        return fresh.copy(altTitles = (series.altTitles + fresh.altTitles).distinct(), coverUrl = fresh.coverUrl ?: series.coverUrl, detailed = true)
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val numericId = series.id.substringBefore('/').toLongOrNull() ?: return emptyList()
        val slug = slugOf(series.id)
        val input = buildJsonObject {
            putJsonObject("0") { putJsonObject("json") { put("values", buildJsonArray { add("undefined") }) } }
            putJsonObject("1") {
                putJsonObject("json") {
                    put("seriesId", numericId)
                    put("chapterId", JsonNull)
                    put("sort", "best")
                    put("page", 1)
                    put("limit", 20)
                }
                putJsonObject("meta") { putJsonObject("values") { put("chapterId", buildJsonArray { add("undefined") }) } }
            }
            putJsonObject("2") { putJsonObject("json") { put("seriesId", numericId) } }
        }
        val list = trpc("auth.me,series.chapters", input)?.let { ctx.json.decodeFromJsonElement<List<Chapter>>(it) }.orEmpty()
        return list.filter { !it.isDeleted && (it.status == null || it.status == "published") && (it.language ?: "en") == "en" }
            .distinctBy { it.number }
            .map { c ->
                val number = formatNumber(c.number)
                SourceChapter(
                    sourceId = id,
                    seriesId = series.id,
                    id = "$slug/$number/${c.pagesCount ?: 0}",
                    label = "Chapter $number" + (c.title?.trim()?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""),
                    number = number,
                    language = "en",
                    uploadedAt = c.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                    pageCount = c.pagesCount,
                    webUrl = "$baseUrl/manga/$slug/$number",
                )
            }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val parts = chapter.id.split('/')
        val slug = parts.getOrNull(0) ?: error("Hiperdex: bad chapter id ${chapter.id}")
        val number = parts.getOrNull(1) ?: error("Hiperdex: bad chapter id ${chapter.id}")
        val count = parts.getOrNull(2)?.toIntOrNull() ?: chapter.pageCount ?: 0
        val html = fetch("$baseUrl/manga/$slug/$number".toHttpUrl(), "text/html,application/xhtml+xml")
        return pagesFromHtml(html, count).mapIndexed { i, url -> ReaderPage(i, url, imageHeaders(url)) }
    }

    override fun imageHeaders(imageUrl: String): Map<String, String> = mapOf("Referer" to "$baseUrl/", "User-Agent" to ctx.browserUserAgent)

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/manga/${slugOf(seriesId)}"

    private fun slugOf(seriesId: String) = seriesId.substringAfter('/')

    private fun Hit.toSeries(): SourceSeries = SourceSeries(
        sourceId = this@HiperdexAdapter.id,
        id = "$id/$slug",
        title = title.trim(),
        altTitles = altTitles(alternativeTitles).filter { !it.equals(title, true) },
        coverUrl = coverUrl?.takeIf { it.isNotBlank() },
        kind = ContentKind.parse(type),
        status = PublicationStatus.parse(status),
        year = year,
        description = synopsis?.trim()?.ifEmpty { null },
        authors = authors.orEmpty(),
        genres = genres.orEmpty(),
        webUrl = "$baseUrl/manga/$slug",
    )

    /** Search returns a JSON array; the detail endpoint returns the same array encoded as a string. */
    private fun altTitles(e: JsonElement?): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> e.contentOrNull?.let { s -> runCatching { altTitles(ctx.json.parseToJsonElement(s)) }.getOrNull() }.orEmpty()
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    companion object {
        const val ID = "hiperdex"
        private const val PAGE_SIZE = 30
        private val PAGE_IMAGE = Regex("""https?://[^"'\s<>]+?/(\d{1,4})\.(webp|avif|jpe?g|png)""")

        internal fun formatNumber(n: Double): String =
            if (n == Math.floor(n)) n.toLong().toString() else n.toBigDecimal().stripTrailingZeros().toPlainString()

        /**
         * Page URLs from the chapter page: the embedded images give the directory, file-name padding and format;
         * the remaining pages follow the same numbering up to [count].
         */
        internal fun pagesFromHtml(html: String, count: Int): List<String> {
            val found = PAGE_IMAGE.findAll(html).map { it.value }.filter { "r2d2storage" in it || "/manga_" in it }.distinct().toList()
            val first = found.firstOrNull { PAGE_IMAGE.find(it)!!.groupValues[1].toInt() == 1 } ?: error("Hiperdex: no page images in chapter page")
            val m = PAGE_IMAGE.find(first)!!
            val dir = first.substring(0, first.length - m.groupValues[1].length - m.groupValues[2].length - 1)
            val inDir = found.filter { it.startsWith(dir) }
            if (count <= inDir.size) return inDir.sortedBy { PAGE_IMAGE.find(it)!!.groupValues[1].toInt() }
            val width = m.groupValues[1].length
            return (1..count).map { n -> dir + n.toString().padStart(width, '0') + "." + m.groupValues[2] }
        }
    }
}
