package alexandria.core.fallback

import alexandria.core.config.AggregatorSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Base64

/**
 * Image-level fallback that works *after* the page list has been handed to the host.
 *
 * Hosts (verified in Mihon's source: `HttpSource.getImage` -> `client.newCall(imageRequest(page))`) download
 * page images and covers through the extension's own OkHttp client, so an interceptor on that client sees
 * each image request and its failure. Library of Alexandria tags its image URLs with a URL *fragment*
 * (never sent over the network) that identifies the chapter/page or the alternative cover URLs. When the
 * request fails or returns something that is not an image, the interceptor fetches the same page index
 * from the next-best provider whose page count matches, or the next cover candidate.
 *
 * Limits: a page can only be substituted when the alternative release has the same number of pages; hosts
 * that load images with their own client (not verified for Tachimanga) simply ignore the fragment.
 */
class FallbackInterceptor(
    private val reader: () -> ReaderResolver?,
    private val settings: () -> AggregatorSettings,
) : Interceptor {

    private val gate = Semaphore(2)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fragment = request.url.fragment ?: return chain.proceed(request)
        return when {
            fragment.startsWith(PAGE_PREFIX) -> withPageFallback(chain, request, fragment.removePrefix(PAGE_PREFIX))
            fragment.startsWith(COVER_PREFIX) -> withCoverFallback(chain, request, fragment.removePrefix(COVER_PREFIX))
            else -> chain.proceed(request)
        }
    }

    private fun withPageFallback(chain: Interceptor.Chain, request: Request, token: String): Response {
        val primaryFailure = attempt(chain, request)
        if (primaryFailure.response != null) return primaryFailure.response
        val s = settings()
        val (chapterRef, index) = decodePageToken(token)?.let { it.chapterRef to it.index } ?: throw primaryFailure.error!!
        val resolver = reader()
        val resolution = resolver?.recentResolution(chapterRef)
        if (!s.sourceFallback || resolver == null || resolution == null) throw primaryFailure.error!!

        for (alt in resolution.fallbacks.take(MAX_ALTERNATIVES)) {
            val pages = runCatching {
                runBlocking { gate.withPermit { resolver.alternatePages(chapterRef, alt, s) } }
            }.getOrNull() ?: continue
            if (pages.size != resolution.pages.size) continue // cannot map page indices safely
            val page = pages.getOrNull(index) ?: continue
            val url = page.imageUrl.toHttpUrlOrNull() ?: continue
            val altRequest = request.newBuilder().url(url).apply {
                page.headers.forEach { (k, v) -> header(k, v) }
            }.build()
            val result = attempt(chain, altRequest)
            if (result.response != null) return result.response
        }
        throw primaryFailure.error!!
    }

    private fun withCoverFallback(chain: Interceptor.Chain, request: Request, token: String): Response {
        val primary = attempt(chain, request)
        if (primary.response != null) return primary.response
        if (!settings().coverFallback) throw primary.error!!
        val alternatives = decodeCoverToken(token)
        for (alt in alternatives) {
            val url = alt.u.toHttpUrlOrNull() ?: continue
            if (url.scheme != "https" && url.scheme != "http") continue
            val altRequest = Request.Builder().url(url).apply {
                request.header("User-Agent")?.let { header("User-Agent", it) }
                alt.h.forEach { (k, v) -> header(k, v) }
            }.build()
            val result = attempt(chain, altRequest)
            if (result.response != null) return result.response
        }
        throw primary.error!!
    }

    private class Attempt(val response: Response?, val error: IOException?)

    private fun attempt(chain: Interceptor.Chain, request: Request): Attempt = try {
        val response = chain.proceed(request)
        val type = response.header("Content-Type")?.lowercase()
        val looksLikeImage = type == null || type.startsWith("image/") || type.contains("octet-stream")
        if (response.isSuccessful && looksLikeImage) {
            Attempt(response, null)
        } else {
            response.close()
            Attempt(null, IOException("Image request failed: HTTP ${response.code} ${type ?: ""} ${request.url.host}"))
        }
    } catch (e: IOException) {
        Attempt(null, e)
    }

    data class PageToken(val chapterRef: String, val index: Int, val sourceId: String?)

    @Serializable
    data class CoverAlt(val u: String, val h: Map<String, String> = emptyMap())

    companion object {
        const val PAGE_PREFIX = "loa-p="
        const val COVER_PREFIX = "loa-c="
        private const val MAX_ALTERNATIVES = 2
        private val json = Json { ignoreUnknownKeys = true }
        private val b64e = Base64.getUrlEncoder().withoutPadding()
        private val b64d = Base64.getUrlDecoder()

        /** Tag format: `#loa-p=<base64url(chapterRef)>.<pageIndex>.<sourceId>` */
        fun tagPage(imageUrl: String, chapterRef: String, index: Int, sourceId: String): String =
            imageUrl.substringBefore('#') + "#" + PAGE_PREFIX + b64e.encodeToString(chapterRef.toByteArray()) + "." + index + "." + sourceId

        fun tagCover(imageUrl: String, alternatives: List<CoverAlt>): String {
            if (alternatives.isEmpty()) return imageUrl
            val payload = json.encodeToString(ListSerializer(CoverAlt.serializer()), alternatives.take(3))
            return imageUrl.substringBefore('#') + "#" + COVER_PREFIX + b64e.encodeToString(payload.toByteArray())
        }

        fun decodePageToken(token: String): PageToken? {
            val parts = token.split('.')
            if (parts.size < 2) return null
            val ref = runCatching { String(b64d.decode(parts[0])) }.getOrNull() ?: return null
            val index = parts[1].toIntOrNull() ?: return null
            return PageToken(ref, index, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }

        fun pageToken(url: String): PageToken? =
            url.substringAfter("#$PAGE_PREFIX", "").takeIf { it.isNotEmpty() }?.let { decodePageToken(it) }

        fun decodeCoverToken(token: String): List<CoverAlt> = runCatching {
            json.decodeFromString(ListSerializer(CoverAlt.serializer()), String(b64d.decode(token)))
        }.getOrDefault(emptyList())

        fun stripTag(url: String): String = url.substringBefore("#$PAGE_PREFIX").substringBefore("#$COVER_PREFIX")
    }
}
