package alexandria.core.metadata

import alexandria.core.model.ContentKind
import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.model.ExternalIds
import alexandria.core.model.PublicationStatus
import alexandria.core.model.SeriesMetadata
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AniList GraphQL (https://docs.anilist.co). Unauthenticated, read-only. The API currently advertises
 * `X-RateLimit-Limit: 30` per minute, so requests share a 30/min budget; 429 responses are retried after
 * `Retry-After` by [fetchText].
 */
class AniListClient(private val client: OkHttpClient, private val json: Json, private val userAgent: String) {
    private val endpoint = "https://graphql.anilist.co"
    private val limiter = RateLimiter.shared("anilist", 5, 10_000) // 30/min with small bursts

    private val mediaFields = """
        id idMal title { romaji english native } synonyms format status countryOfOrigin chapters
        startDate { year } description(asHtml: false) genres coverImage { extraLarge large }
        staff(perPage: 4) { edges { role node { name { full } } } } siteUrl
    """.trimIndent()

    private suspend fun query(query: String, variables: Map<String, Any>): AlResponse {
        val body = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject {
                variables.forEach { (k, v) ->
                    when (v) {
                        is Number -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            })
        }.toString().toRequestBody(JSON)
        val req = Request.Builder().url(endpoint).post(body)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        return json.decodeFromString(AlResponse.serializer(), client.fetchText(req, limiter = limiter))
    }

    suspend fun search(text: String, perPage: Int = 8, adult: Boolean = true): List<SeriesMetadata> {
        val adultFilter = if (adult) "" else ",isAdult:false"
        val q = "query(\$s:String,\$n:Int){Page(perPage:\$n){media(search:\$s,type:MANGA,sort:SEARCH_MATCH$adultFilter){$mediaFields}}}"
        return query(q, mapOf("s" to text, "n" to perPage)).data?.page?.media.orEmpty().map { it.toMetadata() }
    }

    suspend fun popular(page: Int, perPage: Int = 30): List<SeriesMetadata> {
        val q = "query(\$p:Int,\$n:Int){Page(page:\$p,perPage:\$n){media(type:MANGA,sort:POPULARITY_DESC,isAdult:false){$mediaFields}}}"
        return query(q, mapOf("p" to page, "n" to perPage)).data?.page?.media.orEmpty().map { it.toMetadata() }
    }

    suspend fun byId(id: Long): SeriesMetadata? {
        val q = "query(\$id:Int){Media(id:\$id,type:MANGA){$mediaFields}}"
        return query(q, mapOf("id" to id)).data?.media?.toMetadata()
    }

    private fun AlMedia.toMetadata(): SeriesMetadata {
        val roles = staff?.edges.orEmpty()
        return SeriesMetadata(
            provider = "anilist",
            ids = ExternalIds(anilist = id, mal = idMal),
            title = title.english ?: title.romaji ?: title.native ?: "AniList #$id",
            englishTitle = title.english,
            romanizedTitle = title.romaji,
            nativeTitle = title.native,
            altTitles = synonyms,
            kind = when {
                format == "NOVEL" -> ContentKind.NOVEL
                countryOfOrigin == "KR" -> ContentKind.MANHWA
                countryOfOrigin == "CN" || countryOfOrigin == "TW" -> ContentKind.MANHUA
                countryOfOrigin == "JP" -> ContentKind.MANGA
                else -> ContentKind.parse(format)
            },
            status = when (status) {
                "RELEASING" -> PublicationStatus.ONGOING
                "FINISHED" -> PublicationStatus.COMPLETED
                "HIATUS" -> PublicationStatus.HIATUS
                "CANCELLED" -> PublicationStatus.CANCELLED
                else -> PublicationStatus.UNKNOWN
            },
            originalLanguage = when (countryOfOrigin) { "JP" -> "ja"; "KR" -> "ko"; "CN" -> "zh"; "TW" -> "zh-hk"; else -> null },
            year = startDate?.year,
            totalChapters = chapters?.toString(),
            description = description?.replace(Regex("<br\\s*/?>"), "\n")?.replace(Regex("<[^>]+>"), "")?.trim(),
            authors = roles.filter { it.role?.contains("Story", true) == true }.mapNotNull { it.node?.name?.full },
            artists = roles.filter { it.role?.contains("Art", true) == true }.mapNotNull { it.node?.name?.full },
            genres = genres,
            covers = listOfNotNull(
                coverImage?.extraLarge?.let { CoverCandidate(it, "anilist", CoverOrigin.METADATA_LARGE.name) },
            ),
            thumbnailUrl = coverImage?.large ?: coverImage?.extraLarge,
            webUrl = siteUrl ?: "https://anilist.co/manga/$id",
        )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable internal data class AlResponse(val data: AlData? = null)
@Serializable internal data class AlData(
    @kotlinx.serialization.SerialName("Page") val page: AlPage? = null,
    @kotlinx.serialization.SerialName("Media") val media: AlMedia? = null,
)
@Serializable internal data class AlPage(val media: List<AlMedia> = emptyList())
@Serializable internal data class AlMedia(
    val id: Long,
    val idMal: Long? = null,
    val title: AlTitle = AlTitle(),
    val synonyms: List<String> = emptyList(),
    val format: String? = null,
    val status: String? = null,
    val countryOfOrigin: String? = null,
    val chapters: Int? = null,
    val startDate: AlDate? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val coverImage: AlCover? = null,
    val staff: AlStaff? = null,
    val siteUrl: String? = null,
)
@Serializable internal data class AlTitle(val romaji: String? = null, val english: String? = null, val native: String? = null)
@Serializable internal data class AlDate(val year: Int? = null)
@Serializable internal data class AlCover(val extraLarge: String? = null, val large: String? = null)
@Serializable internal data class AlStaff(val edges: List<AlStaffEdge> = emptyList())
@Serializable internal data class AlStaffEdge(val role: String? = null, val node: AlStaffNode? = null)
@Serializable internal data class AlStaffNode(val name: AlName? = null)
@Serializable internal data class AlName(val full: String? = null)
