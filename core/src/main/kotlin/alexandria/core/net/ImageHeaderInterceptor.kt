package alexandria.core.net

import alexandria.core.sources.SourceRegistry
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds the owning provider's image headers (Referer, User-Agent) to image requests the host app builds with the
 * source's generic headers, mainly browse-grid and library covers. Without them several CDNs refuse the request:
 * MangaPill's answers 403 without its Referer, Hiperdex's likewise, and MangaDex's returns an HTML page to
 * browser user agents. Requests to hosts no provider claims pass through untouched, and so does every request
 * that already carries its own Referer (the providers' own requests always do): only requests without one, or
 * with the source's generic [defaultReferer], are adjusted.
 */
class ImageHeaderInterceptor(
    private val registry: () -> SourceRegistry?,
    private val defaultReferer: String? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") return chain.proceed(request)
        val referer = request.header("Referer")
        if (referer != null && referer != defaultReferer) return chain.proceed(request)
        val owner = runCatching { registry()?.imageOwner(request.url.host) }.getOrNull() ?: return chain.proceed(request)
        val headers = owner.imageHeaders(request.url.toString())
        if (headers.isEmpty()) return chain.proceed(request)
        val builder = request.newBuilder()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return chain.proceed(builder.build())
    }
}
