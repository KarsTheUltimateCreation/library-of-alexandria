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
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.buffer
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
 * Failures *after* the response headers (a body that stalls or is cut off) happen while the host reads the body,
 * outside this interceptor. When an alternative exists, the image is therefore read here first, up to
 * [MAX_BUFFER_BYTES], and only a complete image is handed on; a body that fails mid-way is replaced by the
 * alternative instead of reaching the reader as a broken page. Larger images are streamed as before (no recovery
 * possible: their first bytes have already been handed out).
 *
 * Limits: a page can only be substituted when the alternative release has the same number of pages; hosts
 * that load images with their own client (not verified for Tachimanga) simply ignore the fragment, and so does
 * this recovery. Alternatives are fetched with `runBlocking` on the calling OkHttp thread, so under extreme
 * saturation of the host's dispatcher a recovery waits for a free slot (bounded by the provider timeouts).
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
        val s = settings()
        val parsed = decodePageToken(token)
        val resolver = reader()
        val resolution = parsed?.let { resolver?.recentResolution(it.chapterRef) }
        val recoverable = s.sourceFallback && resolver != null && resolution != null && resolution.fallbacks.isNotEmpty()
        val primaryFailure = attempt(chain, request, buffer = recoverable)
        if (primaryFailure.response != null) return primaryFailure.response
        if (!recoverable || parsed == null) throw primaryFailure.error!!
        val (chapterRef, index) = parsed.chapterRef to parsed.index

        for (alt in resolution.fallbacks.take(MAX_ALTERNATIVES)) {
            val pages = runCatching {
                runBlocking { gate.withPermit { resolver.alternatePages(chapterRef, alt, s) } }
            }.getOrNull() ?: continue
            if (pages.size != resolution.pages.size) continue // cannot map page indices safely
            val listed = pages.getOrNull(index) ?: continue
            val page = runCatching { runBlocking { resolver.image(alt.sourceId, listed, s) } }.getOrNull() ?: continue
            val url = page.imageUrl.toHttpUrlOrNull() ?: continue
            val altRequest = request.newBuilder().url(url).apply {
                page.headers.forEach { (k, v) -> header(k, v) }
            }.build()
            val result = attempt(chain, altRequest, buffer = false)
            if (result.response != null) return result.response
        }
        throw primaryFailure.error!!
    }

    private fun withCoverFallback(chain: Interceptor.Chain, request: Request, token: String): Response {
        val alternatives = if (settings().coverFallback) decodeCoverToken(token) else emptyList()
        val primary = attempt(chain, request, buffer = alternatives.isNotEmpty())
        if (primary.response != null) return primary.response
        if (alternatives.isEmpty()) throw primary.error!!
        for ((i, alt) in alternatives.withIndex()) {
            val url = alt.u.toHttpUrlOrNull() ?: continue
            if (url.scheme != "https" && url.scheme != "http") continue
            val altRequest = Request.Builder().url(url).apply {
                request.header("User-Agent")?.let { header("User-Agent", it) }
                alt.h.forEach { (k, v) -> header(k, v) }
            }.build()
            val result = attempt(chain, altRequest, buffer = i < alternatives.lastIndex)
            if (result.response != null) return result.response
        }
        throw primary.error!!
    }

    private class Attempt(val response: Response?, val error: IOException?)

    /**
     * Requests an image. With [buffer], the body is read here (bounded) so a failure while downloading it can still
     * be answered with an alternative; an HTML page served under an image or octet-stream type is rejected too.
     */
    private fun attempt(chain: Interceptor.Chain, request: Request, buffer: Boolean): Attempt = try {
        val response = chain.proceed(request)
        val type = response.header("Content-Type")?.lowercase()
        val looksLikeImage = type == null || type.startsWith("image/") || type.contains("octet-stream")
        when {
            !response.isSuccessful || !looksLikeImage -> {
                response.close()
                Attempt(null, IOException("Image request failed: HTTP ${response.code} ${type ?: ""} ${request.url.host}"))
            }
            buffer -> buffered(response, request)
            else -> Attempt(response, null)
        }
    } catch (e: IOException) {
        Attempt(null, e)
    }

    private fun buffered(response: Response, request: Request): Attempt {
        val body = response.body
        val declared = body.contentLength()
        if (declared > MAX_BUFFER_BYTES) return Attempt(response, null) // too large to hold: stream it
        val source = body.source()
        val buf = Buffer()
        try {
            while (buf.size <= MAX_BUFFER_BYTES) {
                if (source.read(buf, 64 * 1024) == -1L) break
            }
        } catch (e: IOException) {
            response.close()
            return Attempt(null, IOException("Image download failed after ${buf.size} bytes from ${request.url.host}: ${e.message}", e))
        }
        if (looksLikeMarkup(buf)) {
            response.close()
            return Attempt(null, IOException("Image request returned a web page from ${request.url.host}"))
        }
        if (buf.size > MAX_BUFFER_BYTES) {
            // Unknown length and larger than the bound: hand on what was read followed by the rest of the stream.
            val joined = okio.Buffer().also { it.writeAll(buf) }
            val rest = object : okio.ForwardingSource(source) {
                override fun read(sink: Buffer, byteCount: Long): Long =
                    if (joined.size > 0) joined.read(sink, byteCount) else super.read(sink, byteCount)
            }
            return Attempt(response.newBuilder().body(rest.buffer().asResponseBody(body.contentType(), -1)).build(), null)
        }
        response.close()
        return Attempt(response.newBuilder().body(buf.asResponseBody(body.contentType(), buf.size)).build(), null)
    }

    /** An HTML/XML document where an image was expected (error pages, challenge pages). */
    private fun looksLikeMarkup(buf: Buffer): Boolean {
        val n = minOf(buf.size, 64L)
        val head = buf.snapshot(n.toInt()).utf8().trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<?xml") && "svg" !in head
    }

    data class PageToken(val chapterRef: String, val index: Int, val sourceId: String?)

    @Serializable
    data class CoverAlt(val u: String, val h: Map<String, String> = emptyMap())

    companion object {
        const val PAGE_PREFIX = "loa-p="
        const val COVER_PREFIX = "loa-c="
        private const val MAX_ALTERNATIVES = 2

        /** Largest image read completely before being handed on (so a failure can still be recovered). */
        const val MAX_BUFFER_BYTES = 12L * 1024 * 1024
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
