package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.ExternalIds
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Manga Ball (mangaball.com), a multilingual aggregator with a JSON API; only its English chapters are used.
 * Endpoints and response shapes are adapted from keiyoushi/extensions-source `src/all/mangaball` (Apache-2.0).
 *
 * Its chapters are stored on two image mirrors. Mirror `cdn_1` sits behind a Cloudflare browser challenge (verified
 * Nov 4 2026: every page image answered 403 "Just a moment…"), which only a WebView can pass; mirror `cdn_2` serves
 * images directly. Only `cdn_2` chapters are listed, so every release this provider offers is readable without a
 * WebView; the other chapters usually come from other providers anyway (Manga Ball re-hosts other sites' uploads).
 * Covers on the challenged host are skipped for the same reason.
 *
 * Series id: Manga Ball's title id. Chapter id: its chapter id.
 */
class MangaBallAdapter(private val ctx: AdapterContext, override val baseUrl: String = "https://mangaball.com") : SourceAdapter {
    override val id = ID
    override val name = "Manga Ball"
    override val languages: Set<String> = setOf("en")
    override val imageHosts = setOf("red-and-blue.net", "hencover.xyz", "mancover.xyz")
    private val limiter = RateLimiter.shared(ID, 2, 1_000)

    @Serializable
    internal class Search(val data: List<Title> = emptyList())

    @Serializable
    internal class Detail(val data: Title)

    @Serializable
    internal class Title(
        val id: String,
        val slug: String? = null,
        val name: String,
        val alternateName: List<String> = emptyList(),
        val image: Image? = null,
        val description: List<String> = emptyList(),
        val tags: List<Named> = emptyList(),
        val author: List<Named> = emptyList(),
        val status: String? = null,
        val type: String? = null,
        val links: Map<String, JsonElement> = emptyMap(),
        val year: JsonElement? = null,
    )

    @Serializable
    internal class Named(val name: String)

    @Serializable
    internal class Image(
        val file: String? = null,
        @SerialName("cdn_mangaupdate") val cdnMangaupdate: String? = null,
        @SerialName("cdn_mangaupdates") val cdnMangaupdates: String? = null,
    )

    @Serializable
    internal class ChapterList(val data: List<Chapter> = emptyList())

    @Serializable
    internal class Chapter(
        val id: String,
        val name: String? = null,
        val number: Double? = null,
        val volume: Double = 0.0,
        val lang: String,
        val storage: String? = null,
        val status: String? = null,
        @SerialName("group_name") val groupName: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
    )

    @Serializable
    internal class ChapterDetail(val data: Inner) {
        @Serializable
        internal class Inner(val chapter: Pages)

        @Serializable
        internal class Pages(val pages: List<String> = emptyList())
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", ctx.browserUserAgent)
        .header("Accept", "application/json")
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Referer", "$baseUrl/")

    private suspend fun get(url: String) = ctx.client.fetchText(request(url).build(), limiter = limiter)

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        val url = "$baseUrl/api/v1/title/search-advanced".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1").addQueryParameter("limit", "24").addQueryParameter("keyword", query.trim())
            .addQueryParameter("adult_mode", "all").addQueryParameter("tag_mode", "AND").build()
        return parseSearch(get(url.toString()))
    }

    override suspend fun popular(page: Int) = listing("views", page)

    override suspend fun latest(page: Int) = listing("lastupdate", page)

    private suspend fun listing(sort: String, page: Int): List<SourceSeries> {
        val url = "$baseUrl/api/v1/title/search-advanced".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString()).addQueryParameter("limit", "24").addQueryParameter("sort_by", sort)
            .addQueryParameter("sort_order", "desc").addQueryParameter("adult_mode", "all").addQueryParameter("tag_mode", "AND").build()
        return parseSearch(get(url.toString()))
    }

    internal fun parseSearch(json: String): List<SourceSeries> = ctx.json.decodeFromString<Search>(json).data.map { it.toSeries(false) }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        return parseDetails(get("$baseUrl/api/v1/title/detail/${series.id}")).let { it.copy(altTitles = (it.altTitles + series.altTitles).distinct()) }
    }

    internal fun parseDetails(json: String): SourceSeries = ctx.json.decodeFromString<Detail>(json).data.toSeries(true)

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        // Bytes, not a String: OkHttp would append "; charset=utf-8", which this endpoint rejects with HTTP 400.
        val body = buildJsonObject { put("title_id", series.id) }.toString().toByteArray().toRequestBody(JSON)
        return parseChapters(series.id, ctx.client.fetchText(request("$baseUrl/api/v1/chapter/chapter-listing-by-title-id").post(body).build(), limiter = limiter))
    }

    internal fun parseChapters(seriesId: String, json: String): List<SourceChapter> =
        ctx.json.decodeFromString<ChapterList>(json).data
            .filter { it.lang.equals("en", true) && it.storage !in CHALLENGED_STORAGE && (it.status == null || it.status == "published") }
            .map { c ->
                val n = c.number?.toString()?.removeSuffix(".0")
                val name = c.name?.trim().orEmpty()
                SourceChapter(
                    sourceId = ID, seriesId = seriesId, id = c.id,
                    label = when {
                        n == null -> name.ifEmpty { "Chapter ?" }
                        name.isEmpty() -> "Chapter $n"
                        name.contains(n) -> name
                        else -> "Chapter $n: $name"
                    },
                    number = n,
                    volume = c.volume.takeIf { it > 0 }?.toString()?.removeSuffix(".0"),
                    title = name.takeIf { it.isNotEmpty() && n != null && !it.contains(n) },
                    group = c.groupName?.trim()?.ifEmpty { null },
                    uploadedAt = c.createdAt?.let { runCatching { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull() },
                    webUrl = "$baseUrl/chapter-detail/${c.id}",
                )
            }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> = parsePages(get("$baseUrl/api/v1/chapter-detail?chapter_id=${chapter.id}"))

    internal fun parsePages(json: String): List<ReaderPage> {
        val pages = ctx.json.decodeFromString<ChapterDetail>(json).data.chapter.pages
        check(pages.isNotEmpty()) { "Manga Ball returned no pages" }
        return pages.mapIndexed { i, u -> ReaderPage(i, u, imageHeaders(u)) }
    }

    /**
     * Its hentai and manhwa covers (hencover.xyz, mancover.xyz) answer 403 to any Referer, an empty one included only as "none": the Referer is
     * blanked for them (the host's default Referer would otherwise be sent).
     */
    override fun imageHeaders(imageUrl: String) =
        if (NO_REFERER_HOSTS.any { "://$it/" in imageUrl || ".$it/" in imageUrl }) mapOf("Referer" to "", "User-Agent" to ctx.browserUserAgent)
        else mapOf("Referer" to "$baseUrl/", "User-Agent" to ctx.browserUserAgent)

    /** Google image previews are about 180x275; its other covers vary. */
    override fun coverPixels(coverUrl: String) = if ("gstatic.com" in coverUrl) 180L * 275 else 400L * 600

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/title-detail/$seriesId"

    private fun Title.toSeries(detailed: Boolean): SourceSeries {
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
        val mangadex = str(links["mangadex"])?.takeIf { UUID.matches(it) }
        return SourceSeries(
            sourceId = ID, id = id, title = name.trim(),
            altTitles = alternateName.map { it.trim() }.filter { it.isNotEmpty() && !it.equals(name, true) },
            // Covers on the challenged host would not load; MangaDex's CDN rejects other sites' requests. Google image
            // previews (encrypted-tbn*.gstatic.com, ~180x275) are used only when nothing else is offered.
            coverUrl = listOfNotNull(image?.file, image?.cdnMangaupdate, image?.cdnMangaupdates)
                .filter { it.startsWith("http") && CHALLENGED_HOSTS.none { h -> h in it } && "mangadex" !in it }
                .sortedBy { "gstatic.com" in it }
                .firstOrNull(),
            ids = ExternalIds(mangadex = mangadex),
            kind = ContentKind.parse(type),
            status = PublicationStatus.parse(status),
            year = str(year)?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 },
            description = description.joinToString("\n\n").trim().ifEmpty { null },
            authors = author.map { it.name.trim() }.filter { it.isNotEmpty() },
            genres = tags.map { it.name },
            webUrl = "$baseUrl/title-detail/${slug ?: id}",
            detailed = detailed,
        )
    }

    companion object {
        const val ID = "loa_mangaball"
        private val JSON = "application/json".toMediaType()
        private val CHALLENGED_STORAGE = setOf("cdn_1")
        private val CHALLENGED_HOSTS = setOf("poke-black-and-white.net")
        private val NO_REFERER_HOSTS = setOf("hencover.xyz", "mancover.xyz")
        private val UUID = Regex("^[0-9a-f-]{36}$")
    }
}
