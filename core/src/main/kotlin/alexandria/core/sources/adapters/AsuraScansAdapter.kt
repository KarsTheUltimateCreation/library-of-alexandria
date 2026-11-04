package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceAdapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import java.time.Instant

/**
 * Asura Scans (English manhwa/manhua group). Search, details and chapter lists come from its JSON API; the page list
 * is read from the chapter page's Astro island props. Endpoints, the Astro prop unwrapping and the premium /
 * early-access rules are adapted from keiyoushi/extensions-source `src/en/asurascans` (Apache-2.0).
 *
 * Not supported on purpose: premium and early-access chapters (need an account; left out of the list) and
 * *scrambled* pages (tiles that Keiyoushi reassembles with Android's Bitmap API, which is not portable to Tachimanga's
 * runtime). A chapter with scrambled pages fails, so the reader falls back to another provider's release.
 *
 * Series ids are Asura's stable slug ("overgeared"); the site's public URLs carry a random suffix that changes, and
 * the bare slug redirects to the current one.
 */
class AsuraScansAdapter(
    private val ctx: AdapterContext,
    override val baseUrl: String = "https://asurascans.com",
    private val apiUrl: String = "https://api.asurascans.com/api",
    private val clock: () -> Long = System::currentTimeMillis,
) : SourceAdapter {
    override val id = ID
    override val name = "Asura Scans"
    override val languages: Set<String> = setOf("en")
    override val imageHosts = setOf("cdn.asurascans.com")
    override val specialties = setOf(ContentKind.MANHWA, ContentKind.MANHUA)
    private val limiter = RateLimiter.shared(ID, 2, 2_000) // Keiyoushi: 2 requests / 2 s

    @Serializable
    private class Data<T>(val data: T? = null)

    @Serializable
    private class Series(
        val slug: String,
        val title: String,
        val cover: String? = null,
        val description: String? = null,
        val status: String? = null,
        val type: String? = null,
        val author: String? = null,
        val artist: String? = null,
        @SerialName("release_year") val releaseYear: Int? = null,
        @SerialName("alternative_titles") val alternativeTitles: String? = null,
        val genres: List<Genre>? = null,
    )

    @Serializable
    private class Genre(val name: String)

    @Serializable
    private class Chapter(
        val number: Double,
        val title: String? = null,
        @SerialName("page_count") val pageCount: Int? = null,
        @SerialName("is_premium") val isPremium: Boolean = false,
        @SerialName("early_access_until") val earlyAccessUntil: String? = null,
        @SerialName("published_at") val publishedAt: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
    )

    private suspend fun get(url: String, accept: String = "application/json"): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", ctx.browserUserAgent)
            .header("Accept", accept)
            .header("Referer", "$baseUrl/")
            .build()
        return ctx.client.fetchText(request, limiter = limiter)
    }

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim()).addQueryParameter("limit", "20").addQueryParameter("offset", "0").build()
        return ctx.json.decodeFromString<Data<List<Series>>>(get(url.toString())).data.orEmpty().map { it.toSeries() }
    }

    override suspend fun popular(page: Int) = listing("popular", page)

    override suspend fun latest(page: Int) = listing("latest", page)

    private suspend fun listing(sort: String, page: Int): List<SourceSeries> {
        val url = "$apiUrl/series".toHttpUrl().newBuilder().addQueryParameter("sort", sort)
            .addQueryParameter("limit", "20").addQueryParameter("offset", ((page - 1) * 20).toString()).build()
        return ctx.json.decodeFromString<Data<List<Series>>>(get(url.toString())).data.orEmpty().map { it.toSeries() }
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val fresh = ctx.json.decodeFromString<Data<Series>>(get("$apiUrl/series/${series.id}")).data?.toSeries() ?: return series
        return fresh.copy(altTitles = (fresh.altTitles + series.altTitles).distinct(), detailed = true)
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val list = ctx.json.decodeFromString<Data<List<Chapter>>>(get("$apiUrl/series/${series.id}/chapters")).data.orEmpty()
        return parseChapters(series.id, list.map { ChapterInfo(it.number, it.title, it.pageCount, it.isPremium, it.earlyAccessUntil, it.publishedAt ?: it.createdAt) })
    }

    /** What [parseChapters] needs from the API (separate from the DTO so it can be unit tested). */
    internal class ChapterInfo(val number: Double, val title: String?, val pageCount: Int?, val premium: Boolean, val earlyAccessUntil: String?, val publishedAt: String?)

    internal fun parseChapters(seriesId: String, list: List<ChapterInfo>): List<SourceChapter> {
        val now = clock()
        return list
            .filterNot { c -> c.premium || c.earlyAccessUntil?.let { instant(it) }?.let { it > now } == true }
            .map { c ->
                val n = number(c.number)
                SourceChapter(
                    sourceId = id, seriesId = seriesId, id = "$seriesId/$n",
                    label = "Chapter $n" + (c.title?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""),
                    number = n, title = c.title?.takeIf { it.isNotBlank() }, language = "en",
                    uploadedAt = c.publishedAt?.let { instant(it) }, pageCount = c.pageCount?.takeIf { it > 0 },
                    webUrl = "$baseUrl/comics/$seriesId/chapter/$n",
                )
            }
            .sortedBy { it.number?.toDoubleOrNull() ?: 0.0 }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val n = chapter.id.substringAfterLast('/')
        val html = get("$baseUrl/comics/${chapter.seriesId}/chapter/$n", "text/html,application/xhtml+xml")
        return parsePages(html)
    }

    /** Page images from the chapter page's Astro props; throws for scrambled (tiled) pages, see the class note. */
    internal fun parsePages(html: String): List<ReaderPage> {
        val props = Jsoup.parse(html).select("[props*=pages]").map { it.attr("props") }
        val pages = props.firstNotNullOfOrNull { raw ->
            runCatching { unwrap(ctx.json.parseToJsonElement(raw)).jsonObject["pages"]?.jsonArray }.getOrNull()
        } ?: throw IllegalStateException("Asura Scans: no page list (locked or removed chapter)")
        return pages.mapIndexed { i, el ->
            val o = el.jsonObject
            val tiles = o["tiles"]
            if (tiles is JsonArray && tiles.isNotEmpty()) throw UnsupportedOperationException("Asura Scans: scrambled pages are not supported")
            val url = o["url"]?.jsonPrimitive?.content ?: throw IllegalStateException("Asura Scans: page without url")
            ReaderPage(i, url, imageHeaders(url))
        }
    }

    /** Astro serialises props as `[type, value]` pairs: `[0, x]` is a value, `[1, [...]]` an array. */
    private fun unwrap(e: JsonElement): JsonElement = when (e) {
        is JsonArray -> when {
            e.isEmpty() -> JsonNull
            e.size == 1 && (e[0] as? JsonPrimitive)?.intOrNull != null -> JsonNull
            e.size == 2 && (e[0] as? JsonPrimitive)?.intOrNull != null -> unwrap(e[1])
            else -> JsonArray(e.map { unwrap(it) })
        }
        is JsonObject -> JsonObject(e.mapValues { unwrap(it.value) })
        else -> e
    }

    private fun Series.toSeries() = SourceSeries(
        sourceId = this@AsuraScansAdapter.id,
        id = slug,
        title = title,
        altTitles = alternativeTitles?.split('•', ',')?.map { it.trim() }?.filter { it.isNotEmpty() && it.length < 120 }.orEmpty(),
        coverUrl = cover,
        kind = ContentKind.parse(type),
        status = when (status?.lowercase()) {
            "dropped", "axed" -> PublicationStatus.CANCELLED
            else -> PublicationStatus.parse(status)
        },
        year = releaseYear,
        description = description?.let { Jsoup.parseBodyFragment(it).text() },
        authors = listOfNotNull(author, artist).flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() && it != "_" }.distinct(),
        genres = genres?.map { it.name }.orEmpty(),
        webUrl = seriesWebUrl(slug),
    )

    private fun number(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toBigDecimal().stripTrailingZeros().toPlainString()

    private fun instant(s: String): Long? = runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()

    override fun imageHeaders(imageUrl: String): Map<String, String> = mapOf("Referer" to "$baseUrl/", "User-Agent" to ctx.browserUserAgent)

    /** Full-size artwork (about 1100x1400). */
    override fun coverPixels(coverUrl: String) = 1100L * 1400

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/comics/$seriesId"

    companion object {
        /** Providers added from 1.6.6 on are namespaced with "loa_" so they can never collide with older ids. */
        const val ID = "loa_asurascans"
    }
}
