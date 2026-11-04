package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.model.ExternalIds
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceAdapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.OffsetDateTime

/**
 * MangaDex via its public REST API (https://api.mangadex.org/docs/). Respects the documented global limit
 * (~5 req/s) and the at-home server limit (40/min). MangaDex rejects spoofed browser user agents, so a
 * descriptive client user agent is sent.
 */
class MangaDexAdapter(private val ctx: AdapterContext) : SourceAdapter {
    override val id = ID
    override val name = "MangaDex"
    override val baseUrl = "https://mangadex.org"
    override val languages: Set<String>? = null

    private val api = "https://api.mangadex.org"
    private val limiter = RateLimiter.shared("mangadex", 4, 1_000)
    private val atHomeLimiter = RateLimiter.shared("mangadex-athome", 40, 60_000)

    private val headers = Headers.Builder().add("User-Agent", ctx.appUserAgent).build()

    private suspend inline fun <reified T> get(url: HttpUrl, rl: RateLimiter = limiter): T {
        val text = ctx.client.fetchText(Request.Builder().url(url).headers(headers).build(), limiter = rl)
        return ctx.json.decodeFromString(text)
    }

    private fun HttpUrl.Builder.contentRatings() = apply {
        listOf("safe", "suggestive", "erotica").forEach { addQueryParameter("contentRating[]", it) }
    }

    override suspend fun search(query: String): List<SourceSeries> {
        val url = "$api/manga".toHttpUrl().newBuilder()
            .addQueryParameter("title", query)
            .addQueryParameter("limit", "15")
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("order[relevance]", "desc")
            .contentRatings()
            .build()
        return get<MdList<MdManga>>(url).data.map { it.toSeries() }
    }

    override suspend fun popular(page: Int): List<SourceSeries> = listing("followedCount", page)

    override suspend fun latest(page: Int): List<SourceSeries> = listing("latestUploadedChapter", page)

    private suspend fun listing(order: String, page: Int): List<SourceSeries> {
        val url = "$api/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "20")
            .addQueryParameter("offset", ((page - 1) * 20).toString())
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("order[$order]", "desc")
            .addQueryParameter("hasAvailableChapters", "true")
            .contentRatings()
            .build()
        return get<MdList<MdManga>>(url).data.map { it.toSeries() }
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val url = "$api/manga/${series.id}".toHttpUrl().newBuilder().addQueryParameter("includes[]", "cover_art").build()
        return get<MdSingle<MdManga>>(url).data.toSeries()
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val out = mutableListOf<SourceChapter>()
        var offset = 0
        var total: Int
        do {
            val url = "$api/manga/${series.id}/feed".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "500")
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("includes[]", "scanlation_group")
                .addQueryParameter("order[volume]", "asc")
                .addQueryParameter("order[chapter]", "asc")
                .addQueryParameter("includeFuturePublishAt", "0")
                .addQueryParameter("includeExternalUrl", "0")
                .addQueryParameter("includeEmptyPages", "0")
                // Chapters removed from MangaDex@Home (e.g. DMCA) still list but 404 on /at-home.
                .addQueryParameter("includeUnavailable", "0")
                .contentRatings()
                .apply { languages.forEach { addQueryParameter("translatedLanguage[]", it) } }
                .build()
            val page = get<MdList<MdChapter>>(url)
            total = page.total
            out += page.data.map { it.toChapter(series.id) }
            offset += page.data.size
        } while (page.data.isNotEmpty() && offset < total && offset < 10_000)
        return out
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val res = get<MdAtHome>("$api/at-home/server/${chapter.id}".toHttpUrl(), atHomeLimiter)
        val files = if (dataSaver) res.chapter.dataSaver else res.chapter.data
        val dir = if (dataSaver) "data-saver" else "data"
        return files.mapIndexed { i, f -> ReaderPage(i, "${res.baseUrl}/$dir/${res.chapter.hash}/$f") }
    }

    override suspend fun covers(series: SourceSeries): List<CoverCandidate> {
        val url = "$api/cover".toHttpUrl().newBuilder()
            .addQueryParameter("manga[]", series.id)
            .addQueryParameter("limit", "100")
            .addQueryParameter("order[volume]", "asc")
            .build()
        val main = series.coverUrl
        val all = runCatching { get<MdList<MdCover>>(url).data }.getOrDefault(emptyList())
        val candidates = all.map { c ->
            CoverCandidate(
                url = "https://uploads.mangadex.org/covers/${series.id}/${c.attributes.fileName}",
                provider = id,
                origin = CoverOrigin.PROVIDER_ORIGINAL.name,
                language = c.attributes.locale,
            )
        }
        // Main cover first, then the rest (volume order).
        val mainOriginal = main?.removeSuffix(".512.jpg")
        return candidates.sortedBy { if (it.url == mainOriginal) 0 else 1 }.ifEmpty {
            listOfNotNull(mainOriginal?.let { CoverCandidate(it, id, CoverOrigin.PROVIDER_ORIGINAL.name) })
        }
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/title/$seriesId"
    override fun chapterWebUrl(chapter: SourceChapter) = "$baseUrl/chapter/${chapter.id}"

    private fun MdManga.toSeries(): SourceSeries {
        val a = attributes
        val titles = a.title.values.toList()
        val alt = a.altTitles.flatMap { it.values }
        val links = (a.links as? JsonObject)?.mapValues { runCatching { it.value.jsonPrimitive.content }.getOrNull() } ?: emptyMap()
        val cover = relationships.firstOrNull { it.type == "cover_art" }?.attributes?.let { (it as? JsonObject)?.get("fileName")?.jsonPrimitive?.content }
        val en = a.title["en"] ?: a.altTitles.firstNotNullOfOrNull { it["en"] }
        val primary = en ?: titles.firstOrNull() ?: id
        val description = (a.description as? JsonObject)?.let { d -> (d["en"] ?: d.values.firstOrNull())?.jsonPrimitive?.content }
        return SourceSeries(
            sourceId = ID,
            id = id,
            title = primary,
            altTitles = (titles + alt).filter { it != primary }.distinct(),
            coverUrl = cover?.let { "https://uploads.mangadex.org/covers/$id/$it.512.jpg" },
            ids = ExternalIds(
                anilist = links["al"]?.toLongOrNull(),
                mal = links["mal"]?.toLongOrNull(),
                mangaUpdates = links["mu"]?.takeIf { it.any(Char::isLetter) },
                mangadex = id,
            ),
            kind = when (a.originalLanguage) {
                "ja" -> ContentKind.MANGA
                "ko" -> ContentKind.MANHWA
                "zh", "zh-hk" -> ContentKind.MANHUA
                null -> ContentKind.UNKNOWN
                else -> ContentKind.COMIC
            },
            status = PublicationStatus.parse(a.status),
            year = a.year,
            originalLanguage = a.originalLanguage,
            description = description,
            webUrl = seriesWebUrl(id),
            detailed = true,
        )
    }

    private fun MdChapter.toChapter(seriesId: String): SourceChapter {
        val a = attributes
        val group = relationships.firstOrNull { it.type == "scanlation_group" }?.attributes as? JsonObject
        val groupName = group?.get("name")?.jsonPrimitive?.content
        val official = group?.get("official")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
        val label = buildString {
            if (!a.volume.isNullOrBlank()) append("Vol.").append(a.volume).append(' ')
            if (!a.chapter.isNullOrBlank()) append("Ch.").append(a.chapter) else append("Oneshot")
            if (!a.title.isNullOrBlank()) append(" - ").append(a.title)
        }
        return SourceChapter(
            sourceId = ID,
            seriesId = seriesId,
            id = id,
            label = label,
            number = a.chapter?.takeIf { it.isNotBlank() },
            volume = a.volume?.takeIf { it.isNotBlank() },
            title = a.title?.takeIf { it.isNotBlank() },
            language = a.translatedLanguage ?: "en",
            group = groupName,
            official = official,
            uploadedAt = (a.publishAt ?: a.readableAt)?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
            pageCount = a.pages,
            externalOnly = a.externalUrl != null || a.isUnavailable,
            webUrl = "$baseUrl/chapter/$id",
        )
    }

    companion object {
        const val ID = "mangadex"
    }
}

@Serializable internal data class MdList<T>(val data: List<T> = emptyList(), val total: Int = 0, val offset: Int = 0)
@Serializable internal data class MdSingle<T>(val data: T)
@Serializable internal data class MdRelationship(val id: String, val type: String, val attributes: JsonElement? = null)

@Serializable internal data class MdManga(val id: String, val attributes: MdMangaAttributes, val relationships: List<MdRelationship> = emptyList())

@Serializable internal data class MdMangaAttributes(
    val title: Map<String, String> = emptyMap(),
    val altTitles: List<Map<String, String>> = emptyList(),
    val description: JsonElement? = null,
    val links: JsonElement? = null,
    val originalLanguage: String? = null,
    val status: String? = null,
    val year: Int? = null,
)

@Serializable internal data class MdChapter(val id: String, val attributes: MdChapterAttributes, val relationships: List<MdRelationship> = emptyList())

@Serializable internal data class MdChapterAttributes(
    val volume: String? = null,
    val chapter: String? = null,
    val title: String? = null,
    val translatedLanguage: String? = null,
    val externalUrl: String? = null,
    val publishAt: String? = null,
    val readableAt: String? = null,
    val pages: Int? = null,
    val isUnavailable: Boolean = false,
)

@Serializable internal data class MdAtHome(val baseUrl: String, val chapter: MdAtHomeChapter)
@Serializable internal data class MdAtHomeChapter(val hash: String, val data: List<String> = emptyList(), val dataSaver: List<String> = emptyList())

@Serializable internal data class MdCover(val id: String, val attributes: MdCoverAttributes)
@Serializable internal data class MdCoverAttributes(val fileName: String, val volume: String? = null, val locale: String? = null)
