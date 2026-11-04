package alexandria.core.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** Classified HTTP failure so fallback logic can tell "gone" from "try again later". */
class HttpError(val code: Int, val url: String, val retryAfterMillis: Long? = null) : IOException("HTTP $code for $url") {
    val kind: ErrorKind get() = when (code) {
        404, 410 -> ErrorKind.NOT_FOUND
        401, 403 -> ErrorKind.BLOCKED
        429 -> ErrorKind.RATE_LIMITED
        in 500..599 -> ErrorKind.SERVER
        else -> ErrorKind.OTHER
    }
}

/**
 * The service asked for a pause ([waitMillis] remaining) that the current operation cannot afford. Raised
 * *without* sending a request, so callers switch provider or serve cached data instead of hammering the service.
 */
class CoolingDownException(val service: String, val waitMillis: Long) :
    IOException("$service asked to wait ${(waitMillis + 999) / 1000}s before the next request")

enum class ErrorKind {
    NOT_FOUND, BLOCKED, RATE_LIMITED, SERVER, TIMEOUT, NETWORK, PARSE, OTHER,

    /** Waited locally (rate-limit token, cool-down) and never reached the provider: says nothing about its health. */
    QUEUED,
}

fun classify(t: Throwable): ErrorKind = when (t) {
    is HttpError -> t.kind
    is CoolingDownException -> ErrorKind.QUEUED
    is ProviderTimeoutException -> if (t.phase == ProviderTimeoutException.Phase.QUEUED) ErrorKind.QUEUED else ErrorKind.TIMEOUT
    is InterruptedIOException, is kotlinx.coroutines.TimeoutCancellationException -> ErrorKind.TIMEOUT
    is IOException -> ErrorKind.NETWORK
    is kotlinx.serialization.SerializationException, is IllegalStateException, is NullPointerException, is IndexOutOfBoundsException -> ErrorKind.PARSE
    else -> ErrorKind.OTHER
}

/** Suspending call that does not depend on host-specific helpers. The caller owns (and must close) the response. */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // If the caller is gone by now, nobody will close the response: do it here. (Plain resume on purpose: the
            // host app supplies kotlinx-coroutines, and the newer resume-with-cleanup overloads may not exist there.)
            if (!cont.isActive) {
                response.close()
                return
            }
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e) // ignored when already cancelled
        }
    })
}

/**
 * Executes [request] and consumes its response with [read] *inside* the call, so cancelling the coroutine
 * cancels the OkHttp call and interrupts a body that is still downloading (a blocking `body.string()` after
 * `awaitResponse` would keep reading). [timeoutMs] is OkHttp's whole-call timeout (DNS, connect, headers, body).
 */
suspend fun <T> OkHttpClient.exchange(request: Request, timeoutMs: Long? = null, read: (Response) -> T): T =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        if (timeoutMs != null && timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val value = try {
                    response.use(read)
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(value)
            }

            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
        })
    }

/** Parses `Retry-After` (RFC 9110 §10.2.3): delay-seconds or an HTTP-date. Returns milliseconds to wait, or null. */
fun parseRetryAfter(value: String?, now: Long = System.currentTimeMillis()): Long? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    v.toLongOrNull()?.let { return if (it < 0) null else it * 1000 }
    val at = HTTP_DATE_FORMATS.firstNotNullOfOrNull { f ->
        runCatching { ZonedDateTime.parse(v, f).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(v, f).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    } ?: return null
    return (at - now).coerceAtLeast(0)
}

private val HTTP_DATE_FORMATS = listOf(
    DateTimeFormatter.RFC_1123_DATE_TIME, // IMF-fixdate: Sun, 06 Nov 1994 08:49:37 GMT
    // Obsolete RFC 850. Two-digit years resolve into 1970–2069 (RFC 9110: a year more than 50 years ahead is in the past).
    java.time.format.DateTimeFormatterBuilder().appendPattern("EEEE, dd-MMM-")
        .appendValueReduced(java.time.temporal.ChronoField.YEAR, 2, 2, 1970).appendPattern(" HH:mm:ss zzz").toFormatter(Locale.US),
    DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US), // obsolete asctime (no zone: GMT)
)

/**
 * Process-wide request pacing per service: the cool-down a service requested (429/503 + Retry-After) and a cap
 * on concurrent requests, shared by every component and every series, not just within one update.
 */
object ServiceGate {
    private val until = ConcurrentHashMap<String, Long>()
    private val slots = ConcurrentHashMap<String, Semaphore>()

    /** Most requests one service may have in flight at once (across all series and features). */
    const val MAX_CONCURRENT = 4

    fun coolDown(service: String, untilAt: Long) {
        until.merge(service, untilAt) { a, b -> maxOf(a, b) }
    }

    fun remainingCoolDown(service: String, now: Long): Long = ((until[service] ?: return 0) - now).coerceAtLeast(0)

    fun isCoolingDown(service: String, now: Long = System.currentTimeMillis()) = remainingCoolDown(service, now) > 0

    internal fun slots(service: String): Semaphore = slots.getOrPut(service) { Semaphore(MAX_CONCURRENT) }

    /** For tests and "clear everything". */
    fun reset() {
        until.clear()
    }
}

/**
 * Retry rules. Randomised ("full jitter") backoff keeps many clients from retrying in lock-step; every wait has to
 * fit in the operation's remaining budget with time left for the attempt itself, otherwise the error is returned at
 * once and the caller falls back.
 */
object RetryPolicy {
    /** Longest server-requested pause slept inline when no budget says otherwise; longer pauses fail fast. */
    const val MAX_INLINE_WAIT_MS = 8_000L

    /** Time an attempt needs at minimum; a retry is only made with this much budget left after its wait. */
    const val MIN_ATTEMPT_MS = 1_500L

    /** 429 without Retry-After: pause the service this long so concurrent callers do not pile on. */
    const val DEFAULT_429_COOLDOWN_MS = 5_000L

    @Volatile internal var random: Random = Random.Default

    fun backoffMs(attempt: Int, baseMs: Long): Long {
        val cap = baseMs shl (attempt - 1).coerceIn(0, 5)
        return baseMs / 4 + random.nextLong(0, cap.coerceAtLeast(1))
    }
}

/** Service key a request is paced under: its rate limiter's key, else its host. */
private fun serviceKey(request: Request, limiter: RateLimiter?) = limiter?.key ?: "${request.url.host}:${request.url.port}"

/**
 * Waits for the service's cool-down, a rate-limit token and a concurrency slot. All of this is *local* waiting:
 * it is accounted to the [RequestBudget] as queue time, and a wait the budget cannot cover fails immediately with
 * [CoolingDownException] / [ProviderTimeoutException] (phase QUEUED) instead of sleeping into a timeout.
 */
private suspend fun <T> withClearance(service: String, limiter: RateLimiter?, budget: RequestBudget?, block: suspend () -> T): T {
    val started = netNow()
    fun affordable(now: Long) = budget?.let { (it.remainingMs(now) - RetryPolicy.MIN_ATTEMPT_MS).coerceAtLeast(0) }
    val slots = ServiceGate.slots(service)
    suspend fun coolDown() {
        val cd = ServiceGate.remainingCoolDown(service, netNow())
        if (cd > 0) {
            val max = affordable(netNow()) ?: RetryPolicy.MAX_INLINE_WAIT_MS
            if (cd > max) throw CoolingDownException(service, cd)
            delay(cd)
        }
    }
    // Order matters: the rate-limit token is taken only once a connection slot is held, right before sending.
    // Taking it first let tokens pile up while slow responses held every slot, then released them as a burst.
    val wait: suspend () -> Unit = {
        coolDown()
        slots.acquire()
        try {
            coolDown() // a 429 may have arrived while this request waited for its slot
            if (limiter != null) {
                val max = affordable(netNow())
                if (!limiter.acquire(max)) throw ProviderTimeoutException(budget?.owner ?: service, max ?: 0, ProviderTimeoutException.Phase.QUEUED)
            }
        } catch (e: Throwable) {
            slots.release()
            throw e
        }
    }
    if (budget != null) budget.queued { wait() } else wait()
    try {
        budget?.addQueueWait(netNow() - started)
        budget?.startedRequest()
        return block()
    } finally {
        slots.release()
    }
}

/**
 * Executes [request] and returns its response with the body already read into memory (so it was read under
 * cancellation and the call timeout), throwing [HttpError] for non-2xx responses and retrying transient failures a
 * bounded number of times. Honours and records the service's `Retry-After`; never retries before it allows.
 */
suspend fun OkHttpClient.fetch(request: Request, retries: Int = 2, limiter: RateLimiter? = null): Response {
    val service = serviceKey(request, limiter)
    var attempt = 0
    while (true) {
        val budget = kotlinx.coroutines.currentCoroutineContext()[RequestBudget]
        val outcome = try {
            withClearance(service, limiter, budget) {
                val transport = budget?.let { minOf(it.transportTimeoutMs, it.remainingMs(netNow()).coerceAtLeast(1)) }
                exchange(request, transport) { r ->
                    if (r.isSuccessful) {
                        Result.success(r.newBuilder().body(r.body.bytes().toResponseBody(r.body.contentType())).build())
                    } else {
                        Result.failure(HttpError(r.code, request.url.toString(), parseRetryAfter(r.header("Retry-After"))))
                    }
                }
            }
        } catch (e: IOException) {
            if (e is HttpError || e is CoolingDownException || e is InterruptedIOException) throw e // timeouts are not retried
            val wait = RetryPolicy.backoffMs(attempt + 1, 750)
            if (attempt < retries && affords(wait, budget)) {
                attempt++
                delay(wait)
                continue
            }
            throw e
        }
        outcome.getOrNull()?.let { return it }
        val error = outcome.exceptionOrNull() as HttpError
        val retryAfter = error.retryAfterMillis
        val transient = error.code == 429 || error.code in 500..599
        // The service says we are too fast: lower this service's request rate (it recovers gradually).
        if (error.code == 429) limiter?.slowDown()
        if (transient) {
            // Shared: every caller of this service waits, not just this one.
            val pause = retryAfter ?: if (error.code == 429) RetryPolicy.DEFAULT_429_COOLDOWN_MS else null
            if (pause != null) ServiceGate.coolDown(service, netNow() + pause)
        }
        if (attempt < retries && transient) {
            val pausedByServer = retryAfter != null || error.code == 429
            val wait = retryAfter ?: if (error.code == 429) RetryPolicy.DEFAULT_429_COOLDOWN_MS else RetryPolicy.backoffMs(attempt + 1, 2_000)
            val allowed = if (budget == null) wait <= RetryPolicy.MAX_INLINE_WAIT_MS else affords(wait, budget)
            if (allowed) {
                attempt++
                if (!pausedByServer) delay(wait) // a server-requested pause is waited out in withClearance
                continue
            }
        }
        throw error
    }
}

private suspend fun affords(waitMs: Long, budget: RequestBudget?): Boolean =
    budget == null || budget.remainingMs(netNow()) - waitMs >= RetryPolicy.MIN_ATTEMPT_MS

suspend fun OkHttpClient.fetchText(request: Request, retries: Int = 2, limiter: RateLimiter? = null): String =
    fetch(request, retries, limiter).use { it.body.string() }

/**
 * Simple suspending token bucket. [permits] per [periodMillis], refilled continuously.
 * Used to stay inside documented API limits (MangaBaka search 30/min, AniList 30/min degraded, ...).
 */
class RateLimiter(
    private val permits: Int,
    private val periodMillis: Long,
    val key: String = "limiter-" + Integer.toHexString(System.identityHashCode(Any())),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var tokens = permits.toDouble()
    private var last = clock()
    private val waiters = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Share of the configured rate currently allowed (adaptive): halved by each 429 burst ([slowDown]), raised by
     * half again after every [RECOVERY_MS] without one, back to the full rate. A site stricter than the configured
     * rate therefore converges to what it accepts instead of answering 429 to a steady stream of requests.
     */
    @Volatile var factor = 1.0
        private set
    @Volatile private var slowedAt = Long.MIN_VALUE / 2

    private val rate: Double get() = permits * factor // tokens per period
    private val capacity: Double get() = maxOf(1.0, rate)

    fun slowDown() {
        val now = clock()
        synchronized(this) {
            // Several requests in flight answer 429 together: that is one signal, not several.
            if (now - slowedAt < SLOWDOWN_GRACE_MS) return
            factor = maxOf(MIN_FACTOR, factor / 2)
            slowedAt = now
        }
    }

    /**
     * Roughly how long a new request would wait for a token right now (callers already queued × token interval).
     * Lets optional work go elsewhere instead of joining a queue that would outlast its deadline.
     */
    fun backlogMs(): Long = (waiters.get() * periodMillis / rate).toLong()

    suspend fun acquire() {
        acquire(null)
    }

    /**
     * Takes a token, waiting at most [maxWaitMs] (null = as long as needed). Returns false without taking one when
     * the wait would be longer, so the caller can give up instead of sleeping past its deadline.
     */
    suspend fun acquire(maxWaitMs: Long?): Boolean {
        var waited = 0L
        while (true) {
            val wait = mutex.withLock {
                val now = clock()
                if (factor < 1.0 && now - slowedAt > RECOVERY_MS) synchronized(this) {
                    factor = minOf(1.0, factor * 1.5)
                    slowedAt = now - SLOWDOWN_GRACE_MS // recovering is not a fresh slow-down
                }
                tokens = minOf(capacity, tokens + (now - last) * rate / periodMillis.toDouble())
                last = now
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    0L
                } else {
                    ((1.0 - tokens) * periodMillis / rate).toLong().coerceAtLeast(10)
                }
            }
            if (wait == 0L) return true
            if (maxWaitMs != null && waited + wait > maxWaitMs) return false
            waiters.incrementAndGet()
            try { delay(wait) } finally { waiters.decrementAndGet() }
            waited += wait
        }
    }

    companion object {
        /** Lowest share of the configured rate a run of 429s can push a service to. */
        const val MIN_FACTOR = 0.1
        const val SLOWDOWN_GRACE_MS = 2_000L
        const val RECOVERY_MS = 30_000L

        private val shared = ConcurrentHashMap<String, RateLimiter>()

        /** Process-wide limiter per key so every component shares one budget per service. */
        fun shared(key: String, permits: Int, periodMillis: Long): RateLimiter =
            shared.getOrPut(key) { RateLimiter(permits, periodMillis, key = key) }

        /** Current backlog of the shared limiter [key] (0 when there is none). Providers' limiters use their id. */
        fun backlogMs(key: String): Long = shared[key]?.backlogMs() ?: 0L
    }
}
