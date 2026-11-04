package alexandria.core.sources

import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** What a provider's entries are. */
enum class ProviderRole {
    /** Ongoing series with chapter lists; linked to catalogue entries (MangaBaka / AniList). */
    SERIES,

    /**
     * Single-gallery sites (doujinshi, one-shots: nhentai, E-Hentai, ...). Their entries are standalone search
     * results with one chapter; they are never searched to link catalogue series, which keeps opening a series fast.
     */
    GALLERY,
}

/**
 * Internal contract every content provider implements. This is *not* the Mihon extension API: providers are
 * bundled inside Library of Alexandria because installed extensions cannot call one another (Mihon only exposes
 * Application, Json, ProtoBuf, XML, NetworkHelper and JavaScriptEngine to extensions through Injekt).
 */
interface SourceAdapter {
    val id: String
    val name: String
    val baseUrl: String

    /** Languages this provider serves, or null when it is multilingual and filters server-side. */
    val languages: Set<String>?

    /** Adult (18+) provider: searched to link a catalogue series only when that series is adult content. */
    val adult: Boolean get() = false

    val role: ProviderRole get() = ProviderRole.SERIES

    /**
     * Whether hits that match no catalogue entry may appear as search results of their own. MangaDex's are hidden:
     * its catalogue is mirrored by MangaBaka, and its image CDN serves a placeholder to other sites' requests.
     */
    val standaloneResults: Boolean get() = true

    /**
     * Image hosts (suffix match) whose requests need [imageHeaders]. Used to add the right Referer to cover
     * requests the host app makes with the source's default headers. Hosts seen in this provider's cover URLs
     * are learned automatically; list CDNs here so library covers load before anything was searched.
     */
    val imageHosts: Set<String> get() = emptySet()

    /**
     * Content types this provider mostly carries (null = general). Used only to order progressive discovery, so a
     * manhwa-focused site is asked later for a Japanese manga; it never excludes the provider.
     */
    val specialties: Set<alexandria.core.model.ContentKind>? get() = null

    suspend fun search(query: String): List<SourceSeries>

    /** Fills alt titles / ids / status for a search hit. Default: unchanged. */
    suspend fun details(series: SourceSeries): SourceSeries = series

    suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter>

    suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage>

    suspend fun popular(page: Int): List<SourceSeries> = emptyList()

    suspend fun latest(page: Int): List<SourceSeries> = emptyList()

    /** Cover candidates beyond the search thumbnail (e.g. MangaDex's original uploads). */
    suspend fun covers(series: SourceSeries): List<CoverCandidate> =
        listOfNotNull(series.coverUrl?.let { CoverCandidate(it, id, CoverOrigin.PROVIDER_THUMBNAIL.name, headers = imageHeaders(it)) })

    /**
     * Typical pixel area of this provider's [SourceSeries.coverUrl] (measured Nov 2026), used to choose a search
     * result's cover among several providers before anything was measured. Default: a common 400x600 cover.
     */
    fun coverPixels(coverUrl: String): Long = 400L * 600

    /** Headers needed to load this provider's images (Referer, etc.). */
    fun imageHeaders(imageUrl: String): Map<String, String> = emptyMap()

    /** Resolves a [ReaderPage.lazy] viewer page into the actual image. */
    suspend fun resolveImage(pageUrl: String): ReaderPage = throw UnsupportedOperationException("$id has no lazy pages")

    fun seriesWebUrl(seriesId: String): String
    fun chapterWebUrl(chapter: SourceChapter): String = chapter.webUrl ?: seriesWebUrl(chapter.seriesId)
}

/** Shared dependencies handed to adapters. The client is the host's client (cookies, Cloudflare handling). */
class AdapterContext(
    val client: OkHttpClient,
    val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true },
    val appUserAgent: String = "LibraryOfAlexandria/1.0",
    val browserUserAgent: String = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36",
)

/** Helpers for HTML providers. */
abstract class HtmlAdapter(protected val ctx: AdapterContext) : SourceAdapter {
    protected abstract val limiter: RateLimiter?

    protected open fun headers(): Headers = Headers.Builder()
        .add("User-Agent", ctx.browserUserAgent)
        .add("Referer", "$baseUrl/")
        .build()

    protected suspend fun document(url: String): Document =
        document(Request.Builder().url(url).headers(headers()).build())

    /** For non-GET or custom-header requests; the base URI for relative links is the request URL. */
    protected suspend fun document(request: Request): Document {
        val html = ctx.client.fetchText(request, limiter = limiter)
        return Jsoup.parse(html, request.url.toString())
    }

    override fun imageHeaders(imageUrl: String): Map<String, String> =
        mapOf("Referer" to "$baseUrl/", "User-Agent" to ctx.browserUserAgent)
}

internal object ExternalLinkIds {
    private val ANILIST = Regex("""anilist\.co/manga/(\d+)""")
    private val MAL = Regex("""myanimelist\.net/manga/(\d+)""")
    private val MU_NEW = Regex("""mangaupdates\.com/series/([a-z0-9]+)""", RegexOption.IGNORE_CASE)
    private val MANGADEX = Regex("""mangadex\.org/title/([0-9a-f-]{36})""")
    private val MANGABAKA = Regex("""mangabaka\.(?:org|dev)/(?:[a-z]+/)?(\d+)""")

    fun from(urls: Collection<String>): alexandria.core.model.ExternalIds {
        var ids = alexandria.core.model.ExternalIds()
        for (u in urls) {
            ANILIST.find(u)?.let { ids = ids.copy(anilist = ids.anilist ?: it.groupValues[1].toLong()) }
            MAL.find(u)?.let { ids = ids.copy(mal = ids.mal ?: it.groupValues[1].toLong()) }
            MU_NEW.find(u)?.let { ids = ids.copy(mangaUpdates = ids.mangaUpdates ?: it.groupValues[1]) }
            MANGADEX.find(u)?.let { ids = ids.copy(mangadex = ids.mangadex ?: it.groupValues[1]) }
            MANGABAKA.find(u)?.let { ids = ids.copy(mangabaka = ids.mangabaka ?: it.groupValues[1].toLong()) }
        }
        return ids
    }
}
