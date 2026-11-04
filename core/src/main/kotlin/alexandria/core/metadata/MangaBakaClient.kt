package alexandria.core.metadata

import alexandria.core.model.ContentKind
import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.model.ExternalIds
import alexandria.core.model.PublicationStatus
import alexandria.core.model.SeriesMetadata
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * MangaBaka public API (https://mangabaka.org/developers). No authentication. Documented limits:
 * search 30 req/min, everything else 180 req/min, per IP; 429 carries Retry-After.
 * MangaBaka-original data is CC BY-NC-SA 4.0 — attribution is shown in the series description and README.
 */
class MangaBakaClient(private val client: OkHttpClient, private val json: Json, private val userAgent: String) {
    private val api = "https://api.mangabaka.org"
    private val searchLimiter = RateLimiter.shared("mangabaka-search", 5, 10_000) // 30/min
    private val defaultLimiter = RateLimiter.shared("mangabaka", 5, 1_700) // ~176/min

    private suspend fun getJson(url: String, limiter: RateLimiter): JsonObject {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()
        return json.parseToJsonElement(client.fetchText(req, limiter = limiter)).jsonObject
    }

    /**
     * Series search. [adult] false hides pornographic titles; [filters] narrow the result further (a content rating
     * filter can only narrow, never widen, what [adult] allows).
     */
    suspend fun search(
        query: String,
        limit: Int = 10,
        page: Int = 1,
        sortBy: String? = null,
        adult: Boolean = true,
        filters: CatalogFilters = CatalogFilters(),
    ): List<SeriesMetadata> {
        val allowed = if (adult) CatalogFilters.ALL_RATINGS else CatalogFilters.NON_ADULT_RATINGS
        val ratings = filters.contentRatings.filter { it in allowed }.ifEmpty { if (adult) emptyList() else allowed }
        val url = "$api/v1/series/search".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            addQueryParameter("limit", limit.toString())
            addQueryParameter("page", page.toString())
            (filters.sortBy ?: sortBy)?.let { addQueryParameter("sort_by", it) }
            // Comics only unless a type filter says otherwise; MangaBaka also catalogues novels.
            filters.types.ifEmpty { CatalogFilters.COMIC_TYPES }.forEach { addQueryParameter("type", it) }
            filters.statuses.forEach { addQueryParameter("status", it) }
            filters.genres.forEach { addQueryParameter("genre", it) }
            filters.excludedGenres.forEach { addQueryParameter("genre_not", it) }
            ratings.forEach { addQueryParameter("content_rating", it) }
        }.build().toString()
        val root = getJson(url, searchLimiter)
        return root["data"]?.jsonArray?.mapNotNull { runCatching { parseSeries(it.jsonObject) }.getOrNull() }
            .orEmpty()
            .filterNot { it.title.startsWith("unknown title", ignoreCase = true) } // unresolved catalogue placeholders
    }

    suspend fun series(id: Long): SeriesMetadata? {
        val root = getJson("$api/v1/series/$id", defaultLimiter)
        val data = root["data"]?.jsonObject ?: return null
        // Merged entries point at their replacement.
        val mergedWith = data["merged_with"]?.jsonPrimitive?.longOrNull
        if (data["state"]?.jsonPrimitive?.contentOrNull == "merged" && mergedWith != null && mergedWith != id) return series(mergedWith)
        return parseSeries(data)
    }

    suspend fun byAniList(anilistId: Long): SeriesMetadata? {
        val root = getJson("$api/v1/source/anilist/$anilistId", defaultLimiter)
        val data = root["data"]?.jsonObject ?: return null
        val series = data["series"]?.let { s -> (s as? JsonObject) ?: runCatching { s.jsonArray.firstOrNull()?.jsonObject }.getOrNull() }
        return series?.let { parseSeries(it) }
    }

    /** Additional cover artwork (volume covers) with known dimensions. */
    suspend fun images(id: Long, language: String? = null): List<CoverCandidate> {
        val url = "$api/v1/series/$id/images".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "24")
            .addQueryParameter("type", "volume")
            .apply { language?.let { addQueryParameter("language", it) } }
            .build().toString()
        val root = getJson(url, defaultLimiter)
        return root["data"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val raw = o["image"]?.jsonObject?.get("raw")?.jsonObject ?: return@mapNotNull null
            imageCandidate(raw, o["language"]?.jsonPrimitive?.contentOrNull)
        }.orEmpty()
    }

    private fun imageCandidate(raw: JsonObject, language: String?): CoverCandidate? {
        val url = raw["url"]?.jsonPrimitive?.contentOrNull ?: return null
        return CoverCandidate(
            url = url,
            provider = "mangabaka",
            origin = CoverOrigin.METADATA_ORIGINAL.name,
            width = raw["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            height = raw["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            bytes = raw["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            language = language,
        )
    }

    internal fun parseSeries(o: JsonObject): SeriesMetadata {
        fun str(key: String) = o[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        val source = o["source"] as? JsonObject
        fun sourceId(key: String): JsonElement? = (source?.get(key) as? JsonObject)?.get("id")
        val titles = (o["titles"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            (it as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val secondary = (o["secondary_titles"] as? JsonObject)?.values?.flatMap { v ->
            runCatching { v.jsonArray.mapNotNull { e -> e.jsonObject["title"]?.jsonPrimitive?.contentOrNull } }.getOrDefault(emptyList())
        }.orEmpty()
        val english = (o["titles"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }
            ?.filter { it["language"]?.jsonPrimitive?.contentOrNull == "en" }.orEmpty()
        // The primary English title, else the first English title listed.
        val englishTitle = (english.firstOrNull { it["is_primary"]?.jsonPrimitive?.contentOrNull == "true" } ?: english.firstOrNull())
            ?.get("title")?.jsonPrimitive?.contentOrNull
        val coverRaw = (o["cover"] as? JsonObject)?.get("raw") as? JsonObject
        val id = o["id"]!!.jsonPrimitive.content.toLong()
        val type = str("type")
        return SeriesMetadata(
            provider = "mangabaka",
            ids = ExternalIds(
                mangabaka = id,
                anilist = sourceId("anilist")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                mal = sourceId("my_anime_list")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                mangaUpdates = sourceId("manga_updates")?.jsonPrimitive?.contentOrNull,
            ),
            title = str("title") ?: englishTitle ?: titles.firstOrNull() ?: "MangaBaka #$id",
            englishTitle = englishTitle,
            romanizedTitle = str("romanized_title"),
            nativeTitle = str("native_title"),
            altTitles = (titles + secondary).distinct(),
            kind = when (type) {
                "manga" -> ContentKind.MANGA
                "manhwa" -> ContentKind.MANHWA
                "manhua" -> ContentKind.MANHUA
                "novel" -> ContentKind.NOVEL
                "oel" -> ContentKind.COMIC
                else -> ContentKind.parse(type)
            },
            status = PublicationStatus.parse(str("status")),
            originalLanguage = str("original_language"),
            year = str("year")?.toIntOrNull(),
            totalChapters = str("total_chapters")?.takeIf { it.isNotBlank() },
            description = str("description"),
            authors = (o["authors"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            artists = (o["artists"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            genres = (o["genres"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.map { g -> g.replace('_', ' ').replaceFirstChar { it.titlecase() } }.orEmpty(),
            covers = listOfNotNull(coverRaw?.let { imageCandidate(it, null) }),
            thumbnailUrl = ((o["cover"] as? JsonObject)?.get("x350") as? JsonObject)?.get("x2")?.jsonPrimitive?.contentOrNull,
            webUrl = str("canonical_url") ?: "https://mangabaka.org/$id",
        )
    }
}
