package alexandria.core.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Classified HTTP failure so fallback logic can tell "gone" from "try again later". */
class HttpError(val code: Int, val url: String, val retryAfterSeconds: Long? = null) : IOException("HTTP $code for $url") {
    val kind: ErrorKind get() = when (code) {
        404, 410 -> ErrorKind.NOT_FOUND
        401, 403 -> ErrorKind.BLOCKED
        429 -> ErrorKind.RATE_LIMITED
        in 500..599 -> ErrorKind.SERVER
        else -> ErrorKind.OTHER
    }
}

enum class ErrorKind { NOT_FOUND, BLOCKED, RATE_LIMITED, SERVER, TIMEOUT, NETWORK, PARSE, OTHER }

fun classify(t: Throwable): ErrorKind = when (t) {
    is HttpError -> t.kind
    is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> ErrorKind.TIMEOUT
    is IOException -> ErrorKind.NETWORK
    is kotlinx.serialization.SerializationException, is IllegalStateException, is NullPointerException, is IndexOutOfBoundsException -> ErrorKind.PARSE
    else -> ErrorKind.OTHER
}

/** Suspending call that does not depend on host-specific helpers, so the core stays portable and testable. */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Executes [request], throwing [HttpError] for non-2xx responses, retrying transient failures a bounded number of times. */
suspend fun OkHttpClient.fetch(request: Request, retries: Int = 2, limiter: RateLimiter? = null): Response {
    var attempt = 0
    while (true) {
        limiter?.acquire()
        try {
            val response = newCall(request).awaitResponse()
            if (response.isSuccessful) return response
            val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
            val code = response.code
            response.close()
            val error = HttpError(code, request.url.toString(), retryAfter)
            if (attempt < retries && (code == 429 || code in 500..599)) {
                attempt++
                delay(((retryAfter ?: (attempt * 2L)).coerceIn(1, 15)) * 1000)
                continue
            }
            throw error
        } catch (e: HttpError) {
            throw e
        } catch (e: IOException) {
            if (attempt < retries && e !is java.io.InterruptedIOException) {
                attempt++
                delay(attempt * 750L)
                continue
            }
            throw e
        }
    }
}

suspend fun OkHttpClient.fetchText(request: Request, retries: Int = 2, limiter: RateLimiter? = null): String =
    fetch(request, retries, limiter).use { it.body.string() }

/**
 * Simple suspending token bucket. [permits] per [periodMillis], refilled continuously.
 * Used to stay inside documented API limits (MangaBaka search 30/min, AniList 30/min degraded, ...).
 */
class RateLimiter(private val permits: Int, private val periodMillis: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var tokens = permits.toDouble()
    private var last = clock()

    suspend fun acquire() {
        while (true) {
            val wait = mutex.withLock {
                val now = clock()
                tokens = minOf(permits.toDouble(), tokens + (now - last) * permits / periodMillis.toDouble())
                last = now
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    ((1.0 - tokens) * periodMillis / permits).toLong().coerceAtLeast(10)
                }
            }
            if (wait == 0L) return
            delay(wait)
        }
    }

    companion object {
        private val shared = ConcurrentHashMap<String, RateLimiter>()

        /** Process-wide limiter per key so every component shares one budget per service. */
        fun shared(key: String, permits: Int, periodMillis: Long): RateLimiter =
            shared.getOrPut(key) { RateLimiter(permits, periodMillis) }
    }
}
