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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset

/**
 * Atsumaru (atsu.moe, English). JSON API; endpoints and search parameters follow keiyoushi/extensions-source
 * `src/en/atsumaru` (Apache-2.0).
 *
 * Series ids are Atsumaru ids ("oZOG5"); chapter ids are "seriesId/chapterId". Detail pages carry AniList,
 * MyAnimeList, MangaBaka and MangaUpdates ids, so identity matching is exact. Most chapters have uploads from
 * several scanlation groups; each is returned as its own release with the group name.
 */
class AtsumaruAdapter(
    private val ctx: AdapterContext,
    override val baseUrl: String = "https://atsu.moe",
    /** Images and posters are served from the CDN host ("https://cdn.atsu.moe"). */
    private val cdnUrl: String = "https://cdn.atsu.moe",
) : SourceAdapter {
    override val id = ID
    override val name = "Atsumaru"
    override val languages: Set<String> = setOf("en")
    private val limiter = RateLimiter.shared("atsumaru", 2, 1_000)

    @Serializable
    private class SearchResults(val hits: List<Hit> = emptyList()) {
        @Serializable
        class Hit(val document: Manga)
    }

    @Serializable
    private class Browse(val items: List<Manga> = emptyList())

    @Serializable
    private class MangaPage(val mangaPage: Manga)

    @Serializable
    private class Manga(
        val id: String,
        val title: String,
        val englishTitle: String? = null,
        val otherNames: List<String> = emptyList(),
        val poster: JsonElement? = null,
        val image: JsonElement? = null,
        val authors: JsonElement? = null,
        val genres: JsonElement? = null,
        val synopsis: String? = null,
        val status: String? = null,
        val type: String? = null,
        val medium: String? = null,
        val released: Long? = null,
        val year: Int? = null,
        val anilistId: String? = null,
        val malId: String? = null,
        val mangaBakaId: String? = null,
        val mangaUpdatesId: String? = null,
        val scanlators: List<Scanlator> = emptyList(),
    )

    @Serializable
    private class Scanlator(val id: String, val name: String)

    @Serializable
    private class AllChapters(val chapters: List<Chapter> = emptyList())

    @Serializable
    private class Chapter(
        val id: String,
        val number: JsonPrimitive? = null,
        val title: String? = null,
        val scanlationMangaId: String? = null,
        val createdAt: JsonPrimitive? = null,
        val pageCount: Int? = null,
    )

    @Serializable
    private class Read(val readChapter: Pages) {
        @Serializable
        class Pages(val pages: List<Page> = emptyList())

        @Serializable
        class Page(val image: String)
    }

    private suspend inline fun <reified T> get(url: HttpUrl): T {
        val request = Request.Builder().url(url)
            .header("User-Agent", ctx.browserUserAgent)
            .header("Accept", "application/json")
            .header("Referer", "$baseUrl/")
            .build()
        return ctx.json.decodeFromString(ctx.client.fetchText(request, limiter = limiter))
    }

    override suspend fun search(query: String): List<SourceSeries> {
        val url = "$baseUrl/collections/manga/documents/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim().ifEmpty { return emptyList() })
            .addQueryParameter("filter_by", "hidden:!=true && isAdult:=false && medium:!=[`Novel`] && views:>0")
            .addQueryParameter("query_by", "title,englishTitle,otherNames,authors")
            .addQueryParameter("query_by_weights", "4,3,2,1")
            .addQueryParameter("num_typos", "4,3,2,1")
            .addQueryParameter("page", "1")
            .addQueryParameter("per_page", "20")
            .build()
        return get<SearchResults>(url).hits.map { it.document.toSeries() }
    }

    override suspend fun popular(page: Int) = browse("popular", page, "&timeframe=daily")

    override suspend fun latest(page: Int) = browse("recentlyUpdated", page, "")

    private suspend fun browse(list: String, page: Int, extra: String): List<SourceSeries> {
        val offset = (page - 1) * BROWSE_LIMIT
        val url = "$baseUrl/api/home2/$list?offset=$offset&limit=$BROWSE_LIMIT&types=Manga,Manwha,Manhua,OEL&mediums=Comic$extra".toHttpUrl()
        return get<Browse>(url).items.map { it.toSeries() }
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val fresh = page(series.id).toSeries()
        return fresh.copy(
            altTitles = (series.altTitles + fresh.altTitles).distinct(),
            coverUrl = fresh.coverUrl ?: series.coverUrl,
            detailed = true,
        )
    }

    private suspend fun page(seriesId: String): Manga =
        get<MangaPage>("$baseUrl/api/manga/page".toHttpUrl().newBuilder().addQueryParameter("id", seriesId).build()).mangaPage

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val groups = page(series.id).scanlators.associate { it.id to it.name }
        val url = "$baseUrl/api/manga/allChapters".toHttpUrl().newBuilder().addQueryParameter("mangaId", series.id).build()
        val all = get<AllChapters>(url).chapters
        fun numberOf(c: Chapter) = c.number?.contentOrNull?.takeIf { it.isNotBlank() }
        // Numbers some group uploaded without a part marker: there, "(Part N)" is an arc subtitle, not a split.
        val wholeNumbers = all.filter { c -> c.title?.let { PART_SUFFIX.containsMatchIn(it) } != true }.mapNotNull(::numberOf).toSet()
        return all.map { c ->
            val number = numberOf(c)
            val raw = c.title?.trim()?.ifEmpty { null } ?: "Chapter ${number ?: c.id}"
            SourceChapter(
                sourceId = id,
                seriesId = series.id,
                id = "${series.id}/${c.id}",
                label = if (number != null && number in wholeNumbers) arcPartAsTitle(raw) else raw,
                number = number?.removeSuffix(".0"),
                language = "en",
                group = c.scanlationMangaId?.let(groups::get),
                uploadedAt = c.createdAt?.let { it.longOrNull ?: runCatching { Instant.parse(it.content).toEpochMilli() }.getOrNull() },
                pageCount = c.pageCount,
                webUrl = "$baseUrl/read/${series.id}/${c.id}",
            )
        }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        val chapterId = chapter.id.substringAfter('/')
        val url = "$baseUrl/api/read/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("mangaId", chapter.seriesId)
            .addQueryParameter("chapterId", chapterId)
            .build()
        return get<Read>(url).readChapter.pages.mapIndexed { i, p -> imageUrl(p.image).let { ReaderPage(i, it, imageHeaders(it)) } }
    }

    override fun imageHeaders(imageUrl: String): Map<String, String> = mapOf("Referer" to "$baseUrl/")

    /** Posters are about 460x667. */
    override fun coverPixels(coverUrl: String) = 460L * 667

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/manga/$seriesId"

    /** "/static/pages/x/0.webp", "posters/x.jpg" or an absolute URL -> CDN URL. */
    private fun imageUrl(path: String): String = when {
        path.startsWith("http") -> path
        path.startsWith("//") -> "https:$path"
        else -> "$cdnUrl/static/" + path.removePrefix("/").removePrefix("static/")
    }

    private fun Manga.toSeries(): SourceSeries {
        val credits = (authors as? JsonArray).orEmpty().mapNotNull { a ->
            when (a) {
                is JsonPrimitive -> a.contentOrNull?.let { it to null }
                is JsonObject -> a["name"]?.jsonPrimitive?.contentOrNull?.let { it to a["type"]?.jsonPrimitive?.contentOrNull }
                else -> null
            }
        }
        val year = year ?: released?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year }
        return SourceSeries(
            sourceId = this@AtsumaruAdapter.id,
            id = id,
            title = title,
            altTitles = (listOfNotNull(englishTitle) + otherNames).map { it.trim() }.filter { it.isNotEmpty() && !it.equals(title, true) }
                .distinctBy { it.lowercase() },
            coverUrl = coverPath(poster) ?: coverPath(image),
            ids = ExternalIds(
                anilist = anilistId?.toLongOrNull(),
                mal = malId?.toLongOrNull(),
                mangabaka = mangaBakaId?.toLongOrNull(),
                // Only the current base-36 MangaUpdates ids; legacy numeric ids would falsely disagree.
                mangaUpdates = mangaUpdatesId?.takeIf { MU_ID.matches(it) },
            ),
            kind = kindOf(type, medium),
            status = PublicationStatus.parse(status),
            year = year,
            description = synopsis?.trim()?.ifEmpty { null },
            authors = credits.filter { it.second == null || it.second.equals("Author", true) }.map { it.first },
            genres = (genres as? JsonArray).orEmpty().mapNotNull { g ->
                (g as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: (g as? JsonPrimitive)?.contentOrNull
            },
            webUrl = seriesWebUrl(id),
        )
    }

    /** Prefers the original JPEG/PNG upload: AVIF variants are not decodable everywhere. */
    private fun coverPath(e: JsonElement?): String? {
        val path = when (e) {
            is JsonPrimitive -> e.contentOrNull
            is JsonObject -> (e["image"] ?: e["largeImage"])?.jsonPrimitive?.contentOrNull
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null
        return imageUrl(path)
    }

    companion object {
        const val ID = "atsumaru"
        private const val BROWSE_LIMIT = 40
        /** Current MangaUpdates ids are base-36 ("6z1uqw7"); legacy ones are all digits. */
        private val MU_ID = Regex("""(?=.*[a-z])[0-9a-z]{6,8}""")

        private val PART_SUFFIX = Regex("""\s*\(\s*part\s*(\d{1,3})\s*\)\s*$""", RegexOption.IGNORE_CASE)

        /** "Chapter 311 (Part 13)" -> "Chapter 311: Part 13" (a title, so it no longer reads as a split chapter). */
        internal fun arcPartAsTitle(label: String): String =
            PART_SUFFIX.find(label)?.let { m -> label.substring(0, m.range.first).trimEnd() + ": Part " + m.groupValues[1] } ?: label

        /** Atsumaru spells it "Manwha"; novels are excluded from search but labelled if they appear elsewhere. */
        internal fun kindOf(type: String?, medium: String?): ContentKind = when {
            medium.equals("Novel", true) -> ContentKind.NOVEL
            type.equals("Manwha", true) || type.equals("Manhwa", true) -> ContentKind.MANHWA
            type.equals("OEL", true) -> ContentKind.COMIC
            else -> ContentKind.parse(type)
        }
    }
}
