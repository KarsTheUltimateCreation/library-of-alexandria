package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.ProviderRole
import alexandria.core.sources.SourceAdapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * nhentai (doujinshi and one-shots), English galleries only. Public JSON API v2 (https://nhentai.net/api/v2/docs):
 * anonymous search is limited to 10 requests per minute, so this provider is only searched for user searches,
 * never to link catalogue series ([ProviderRole.GALLERY]).
 *
 * Series and chapter ids are the gallery id.
 */
class NHentaiAdapter(private val ctx: AdapterContext, override val baseUrl: String = "https://nhentai.net") : SourceAdapter {
    override val id = ID
    override val name = "NHentai"
    override val languages: Set<String> = setOf("en")
    override val adult = true
    override val role = ProviderRole.GALLERY
    override val imageHosts = setOf("nhentai.net")
    private val searchLimiter = RateLimiter.shared("nhentai-search", 3, 18_000) // 10/min
    private val limiter = RateLimiter.shared("nhentai", 2, 1_000)

    @Serializable
    private class Hit(
        val id: Long,
        @SerialName("media_id") val mediaId: String,
        @SerialName("english_title") val englishTitle: String? = null,
        @SerialName("japanese_title") val japaneseTitle: String? = null,
        val thumbnail: String? = null,
        @SerialName("num_pages") val pages: Int? = null,
        @SerialName("tag_ids") val tagIds: List<Long> = emptyList(),
    )

    @Serializable
    private class Hits(val result: List<Hit> = emptyList())

    @Serializable
    private class Title(val english: String? = null, val japanese: String? = null, val pretty: String? = null)

    @Serializable
    private class Image(val path: String)

    @Serializable
    private class Tag(val type: String, val name: String)

    @Serializable
    private class Page(val number: Int, val path: String)

    @Serializable
    private class Gallery(
        val id: Long,
        val title: Title = Title(),
        val cover: Image? = null,
        val thumbnail: Image? = null,
        @SerialName("upload_date") val uploadDate: Long? = null,
        val tags: List<Tag> = emptyList(),
        @SerialName("num_pages") val numPages: Int? = null,
        val pages: List<Page> = emptyList(),
    )

    private suspend fun get(url: HttpUrl, rl: RateLimiter = limiter): String = ctx.client.fetchText(
        Request.Builder().url(url).header("User-Agent", ctx.browserUserAgent).header("Accept", "application/json").header("Referer", "$baseUrl/").build(),
        limiter = rl,
    )

    override suspend fun search(query: String): List<SourceSeries> {
        if (query.isBlank()) return emptyList()
        val url = "$baseUrl/api/v2/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", "${query.trim()} language:english")
            .addQueryParameter("sort", "popular")
            .build()
        return hits(get(url, searchLimiter))
    }

    override suspend fun popular(page: Int): List<SourceSeries> = hits(
        get("$baseUrl/api/v2/search".toHttpUrl().newBuilder().addQueryParameter("query", "language:english")
            .addQueryParameter("sort", "popular-week").addQueryParameter("page", page.toString()).build(), searchLimiter),
    )

    override suspend fun latest(page: Int): List<SourceSeries> = hits(
        get("$baseUrl/api/v2/search".toHttpUrl().newBuilder().addQueryParameter("query", "language:english")
            .addQueryParameter("page", page.toString()).build(), searchLimiter),
    )

    internal fun hits(body: String): List<SourceSeries> = ctx.json.decodeFromString(Hits.serializer(), body).result
        .filter { ENGLISH_TAG in it.tagIds }
        .map { h ->
            val raw = h.englishTitle ?: h.japaneseTitle ?: "#${h.id}"
            SourceSeries(
                sourceId = id,
                id = h.id.toString(),
                title = Galleries.cleanTitle(raw),
                altTitles = Galleries.titles(raw, h.japaneseTitle).drop(1),
                coverUrl = h.thumbnail?.let { "$THUMBS/$it" },
                kind = ContentKind.OTHER,
                status = PublicationStatus.COMPLETED,
                webUrl = seriesWebUrl(h.id.toString()),
            )
        }

    private suspend fun gallery(galleryId: String): Gallery =
        ctx.json.decodeFromString(Gallery.serializer(), get("$baseUrl/api/v2/galleries/$galleryId".toHttpUrl()))

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        val g = gallery(series.id)
        val raw = g.title.english ?: g.title.pretty ?: series.title
        fun tags(type: String) = g.tags.filter { it.type == type }.map { it.name }
        return series.copy(
            title = Galleries.cleanTitle(g.title.pretty ?: raw),
            altTitles = Galleries.titles(raw, g.title.japanese).filter { !it.equals(Galleries.cleanTitle(g.title.pretty ?: raw), true) },
            coverUrl = g.cover?.let { "$THUMBS/${it.path}" } ?: series.coverUrl,
            authors = tags("artist") + tags("group"),
            genres = (tags("category") + tags("tag")).distinct(),
            kind = ContentKind.OTHER,
            status = PublicationStatus.COMPLETED,
            description = listOfNotNull(
                tags("parody").takeIf { it.isNotEmpty() }?.let { "Parody: ${it.joinToString()}" },
                tags("character").takeIf { it.isNotEmpty() }?.let { "Characters: ${it.joinToString()}" },
                g.numPages?.let { "$it pages" },
            ).joinToString("\n").ifEmpty { null },
            detailed = true,
        )
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        val g = gallery(series.id)
        return listOf(Galleries.chapter(id, series.id, g.uploadDate?.times(1000), g.numPages, seriesWebUrl(series.id)))
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> =
        gallery(chapter.id).pages.sortedBy { it.number }.mapIndexed { i, p ->
            val url = "${IMAGES[i % IMAGES.size]}/${p.path}"
            ReaderPage(i, url, imageHeaders(url))
        }

    override fun imageHeaders(imageUrl: String) = mapOf("Referer" to "$baseUrl/", "User-Agent" to ctx.browserUserAgent)

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/g/$seriesId/"

    companion object {
        const val ID = "nhentai"
        /** The "language: english" tag. */
        private const val ENGLISH_TAG = 12227L
        private const val THUMBS = "https://t3.nhentai.net"
        private val IMAGES = listOf("https://i1.nhentai.net", "https://i2.nhentai.net", "https://i3.nhentai.net")
    }
}
