package alexandria.core.sources.adapters

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.HttpError
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import alexandria.core.normalization.TitleNormalizer
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceAdapter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Flame Comics (flamecomics.xyz), an English manhwa scanlation group. Data comes from the site's Next.js data routes
 * (`/_next/data/<buildId>/…json`); the build id changes on every deploy, so it is read from the home page and
 * re-read once when a data route answers 404. The site has no search endpoint: its full series list (~170 entries)
 * is fetched once, kept for 30 minutes and filtered locally. Routes, image URL layout and the build-id refresh are
 * adapted from keiyoushi/extensions-source `src/en/flamecomics` (Apache-2.0).
 *
 * Series id: Flame's numeric series id. Chapter id: the chapter token.
 */
class FlameComicsAdapter(
    private val ctx: AdapterContext,
    override val baseUrl: String = "https://flamecomics.xyz",
    private val cdnUrl: String = "https://cdn.flamecomics.xyz",
    private val clock: () -> Long = System::currentTimeMillis,
) : SourceAdapter {
    override val id = ID
    override val name = "Flame Comics"
    override val languages: Set<String> = setOf("en")
    override val imageHosts = setOf("cdn.flamecomics.xyz")
    override val specialties = setOf(ContentKind.MANHWA, ContentKind.MANHUA)
    private val limiter = RateLimiter.shared(ID, 2, 2_000) // Keiyoushi: 2 requests / 2 s

    @Volatile private var buildId: String? = null
    private val listLock = Mutex()
    @Volatile private var browse: Pair<Long, List<SourceSeries>>? = null

    @Serializable
    internal class Next<T>(val pageProps: T)

    @Serializable
    internal class Browse(val series: List<Series> = emptyList())

    @Serializable
    internal class Latest(val allSeries: List<Series> = emptyList())

    @Serializable
    internal class SeriesPage(val series: Series, val chapters: List<Chapter> = emptyList())

    @Serializable
    internal class ChapterPage(val chapter: Images)

    @Serializable
    internal class Series(
        val title: String,
        val altTitles: JsonElement? = null,
        val description: String? = null,
        val cover: String? = null,
        val type: String? = null,
        val tags: List<String>? = null,
        val author: List<String>? = null,
        val artist: List<String>? = null,
        val status: String? = null,
        @SerialName("series_id") val seriesId: Int? = null,
        @SerialName("last_edit") val lastEdit: Long? = null,
        val year: JsonElement? = null,
    )

    @Serializable
    internal class Chapter(
        val chapter: String,
        val title: String? = null,
        @SerialName("release_date") val releaseDate: Long? = null,
        @SerialName("series_id") val seriesId: Int,
        val token: String,
    )

    @Serializable
    internal class Images(@SerialName("release_date") val releaseDate: Long? = null, @SerialName("series_id") val seriesId: Int, val token: String, val images: Map<String, Image> = emptyMap())

    @Serializable
    internal class Image(val name: String, val width: Int? = null, val height: Int? = null)

    private fun request(url: String, accept: String = "application/json") = Request.Builder().url(url)
        .header("User-Agent", ctx.browserUserAgent).header("Accept", accept).header("Referer", "$baseUrl/").build()

    private suspend fun currentBuildId(refresh: Boolean = false): String {
        if (!refresh) buildId?.let { return it }
        val html = ctx.client.fetchText(request("$baseUrl/", "text/html"), limiter = limiter)
        val data = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: error("Flame Comics: no build id")
        return BUILD_ID.find(data)?.groupValues?.get(1)?.also { buildId = it } ?: error("Flame Comics: no build id")
    }

    /** One data route; a 404 means the site was redeployed: refresh the build id once and retry. */
    private suspend fun data(path: String): String = try {
        ctx.client.fetchText(request("$baseUrl/_next/data/${currentBuildId()}/$path"), limiter = limiter)
    } catch (e: HttpError) {
        if (e.code != 404) throw e
        ctx.client.fetchText(request("$baseUrl/_next/data/${currentBuildId(refresh = true)}/$path"), limiter = limiter)
    }

    private suspend fun allSeries(): List<SourceSeries> = listLock.withLock {
        browse?.takeIf { clock() - it.first < LIST_TTL }?.let { return it.second }
        parseBrowse(data("browse.json")).also { browse = clock() to it }
    }

    override suspend fun search(query: String): List<SourceSeries> {
        val q = TitleNormalizer.compact(query)
        if (q.isEmpty()) return emptyList()
        return allSeries().filter { s -> s.allTitles().any { q in TitleNormalizer.compact(it) } }
    }

    override suspend fun popular(page: Int) = if (page == 1) allSeries() else emptyList()

    override suspend fun latest(page: Int) =
        if (page == 1) ctx.json.decodeFromString<Next<Latest>>(data("latest.json")).pageProps.allSeries.mapNotNull { it.toSeries() } else emptyList()

    internal fun parseBrowse(json: String): List<SourceSeries> = ctx.json.decodeFromString<Next<Browse>>(json).pageProps.series.mapNotNull { it.toSeries() }

    override suspend fun details(series: SourceSeries): SourceSeries {
        if (series.detailed) return series
        return parseSeriesPage(data("series/${series.id}.json?id=${series.id}")).first
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> =
        parseSeriesPage(data("series/${series.id}.json?id=${series.id}")).second

    internal fun parseSeriesPage(json: String): Pair<SourceSeries, List<SourceChapter>> {
        val page = ctx.json.decodeFromString<Next<SeriesPage>>(json).pageProps
        val series = page.series.toSeries()?.copy(detailed = true) ?: error("Flame Comics: series without id")
        val chapters = page.chapters.map { c ->
            val n = c.chapter.trim().toDoubleOrNull()?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() } ?: c.chapter.trim()
            val title = c.title?.trim()?.ifEmpty { null }
            SourceChapter(
                sourceId = ID, seriesId = series.id, id = c.token,
                label = if (title != null) "Chapter $n: $title" else "Chapter $n",
                number = n.takeIf { it.toDoubleOrNull() != null }, title = title, group = name,
                uploadedAt = c.releaseDate?.times(1000),
                webUrl = "$baseUrl/series/${c.seriesId}/${c.token}",
            )
        }
        return series to chapters
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> =
        parsePages(data("series/${chapter.seriesId}/${chapter.id}.json?id=${chapter.seriesId}&token=${chapter.id}"))

    internal fun parsePages(json: String): List<ReaderPage> {
        val c = ctx.json.decodeFromString<Next<ChapterPage>>(json).pageProps.chapter
        check(c.images.isNotEmpty()) { "Flame Comics returned no pages" }
        return c.images.values.mapIndexed { i, img ->
            val url = "$cdnUrl/uploads/images/series/${c.seriesId}/${c.token}/${img.name}" + (c.releaseDate?.let { "?$it" } ?: "")
            ReaderPage(i, url, imageHeaders(url))
        }
    }

    override fun imageHeaders(imageUrl: String) = mapOf("Referer" to "$baseUrl/", "User-Agent" to ctx.browserUserAgent)

    /** The stored cover is the full-size upload, not a resized thumbnail. */
    override suspend fun covers(series: SourceSeries): List<alexandria.core.model.CoverCandidate> =
        listOfNotNull(series.coverUrl?.let { alexandria.core.model.CoverCandidate(it, id, alexandria.core.model.CoverOrigin.PROVIDER_ORIGINAL.name, headers = imageHeaders(it)) })

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/series/$seriesId"

    private fun Series.toSeries(): SourceSeries? {
        val sid = seriesId ?: return null
        val alts = when (val a = altTitles) {
            is kotlinx.serialization.json.JsonArray -> a.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> a.contentOrNull?.let { s -> runCatching { ctx.json.decodeFromString<List<String>>(s) }.getOrElse { s.split(',') } }.orEmpty()
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() && !it.equals(title, true) }
        return SourceSeries(
            sourceId = ID, id = sid.toString(), title = title.trim(), altTitles = alts,
            // The stored cover file is the full-size upload.
            coverUrl = cover?.takeIf { it.isNotBlank() }?.let { "$cdnUrl/uploads/images/series/$sid/$it" + (lastEdit?.let { e -> "?$e" } ?: "") },
            kind = ContentKind.parse(type),
            status = PublicationStatus.parse(status),
            year = (year as? JsonPrimitive)?.contentOrNull?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 },
            description = description?.let { Jsoup.parseBodyFragment(it).text().trim() }?.ifEmpty { null },
            authors = (author.orEmpty() + artist.orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            genres = tags.orEmpty(),
            webUrl = "$baseUrl/series/$sid",
            // The series list already carries authors and alternative titles: enough to match.
            detailed = !author.isNullOrEmpty(),
        )
    }

    companion object {
        const val ID = "loa_flamecomics"
        private const val LIST_TTL = 30 * 60_000L
        private val BUILD_ID = Regex(""""buildId"\s*:\s*"([^"]+)"""")
    }
}
