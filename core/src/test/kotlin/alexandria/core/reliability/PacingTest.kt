package alexandria.core.reliability

import alexandria.core.cache.InMemoryStore
import alexandria.core.net.CoolingDownException
import alexandria.core.net.ErrorKind
import alexandria.core.net.HttpError
import alexandria.core.net.NetClock
import alexandria.core.net.ProviderTimeoutException
import alexandria.core.net.RateLimiter
import alexandria.core.net.RetryPolicy
import alexandria.core.net.classify
import alexandria.core.net.fetch
import alexandria.core.net.fetchText
import alexandria.core.net.parseRetryAfter
import alexandria.core.net.withProviderTimeout
import alexandria.core.ranking.ProviderHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacingTest {
    private val client = OkHttpClient()

    private fun req(server: MockWebServer, path: String = "/") = Request.Builder().url(server.url(path)).build()

    @Test
    fun `retry-after accepts seconds and every http-date form`() {
        val now = 784_111_717_000L // Sun, 06 Nov 1994 08:48:37 GMT
        assertEquals(120_000, parseRetryAfter("120", now))
        assertEquals(60_000, parseRetryAfter("Sun, 06 Nov 1994 08:49:37 GMT", now)) // IMF-fixdate
        assertEquals(60_000, parseRetryAfter("Sunday, 06-Nov-94 08:49:37 GMT", now)) // RFC 850
        assertEquals(60_000, parseRetryAfter("Sun Nov  6 08:49:37 1994", now)) // asctime
        assertEquals(0, parseRetryAfter("Sun, 06 Nov 1994 08:00:00 GMT", now)) // in the past: no wait
        assertNull(parseRetryAfter("-5", now))
        assertNull(parseRetryAfter("soon", now))
        assertNull(parseRetryAfter(null, now))
    }

    @Test
    fun `a long cool-down is shared and fails fast without sending requests`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "120").build())
            server.enqueue(MockResponse.Builder().code(200).body("too early").build())
            server.start()
            val limiter = RateLimiter(10, 1_000, key = "pacing-shared-${server.port}")
            assertFailsWith<HttpError> { client.fetch(req(server), limiter = limiter) }
            // A different caller of the same service (another series, another feature) is held back too.
            val e = assertFailsWith<CoolingDownException> { client.fetch(req(server, "/other"), limiter = limiter) }
            assertTrue(e.waitMillis > 100_000)
            assertEquals(ErrorKind.QUEUED, classify(e), "a local fast-fail is not a provider health failure")
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `an affordable retry-after is waited out, not shortened`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "3").build())
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            server.start()
            val text = withContext(NetClock { testScheduler.currentTime }) { client.fetchText(req(server)) }
            assertEquals("ok", text)
            assertTrue(currentTime >= 3_000, "retried after ${currentTime}ms, before Retry-After allowed")
        }
    }

    @Test
    fun `retries never outrun the operation budget`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "10").build())
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            server.start()
            // MockWebServer answers in real time, so the budget runs on the real clock too.
            withContext(Dispatchers.Default) {
                assertFailsWith<HttpError> { withProviderTimeout("svc", 3_000) { client.fetchText(req(server)) } }
            }
            assertEquals(1, server.requestCount, "a 10 s pause does not fit a 3 s budget: fail and fall back instead")
        }
    }

    @Test
    fun `server errors without retry-after back off with jitter inside the budget`() = runTest {
        RetryPolicy.random = Random(42)
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(502).build())
                server.enqueue(MockResponse.Builder().code(200).body("ok").build())
                server.start()
                val text = withContext(Dispatchers.Default) { withProviderTimeout("svc", 20_000) { client.fetchText(req(server)) } }
                assertEquals("ok", text)
                assertEquals(2, server.requestCount)
            }
            val delays = (1..50).map { RetryPolicy.backoffMs(2, 1_000) }
            assertTrue(delays.all { it in 250..2_250 } && delays.toSet().size > 10, "jittered, bounded: $delays")
        } finally {
            RetryPolicy.random = Random.Default
        }
    }

    @Test
    fun `time spent queueing locally is not a provider failure and sends nothing`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            server.start()
            val limiter = RateLimiter(1, 10_000, key = "pacing-queue-${server.port}") { testScheduler.currentTime }
            limiter.acquire() // the only token is taken by another series
            val health = ProviderHealth(InMemoryStore()) { testScheduler.currentTime }
            val e = withContext(NetClock { testScheduler.currentTime }) {
                assertFailsWith<ProviderTimeoutException> { health.call("weebcentral", 2_000) { client.fetchText(req(server), limiter = limiter) } }
            }
            assertEquals(ProviderTimeoutException.Phase.QUEUED, e.phase)
            assertEquals(0, server.requestCount)
            assertEquals(0, health.get("weebcentral").failures)
            assertTrue(currentTime < 2_000, "gave up at once instead of sleeping into the deadline (${currentTime}ms)")
        }
    }

    @Test
    fun `a stalled body is abandoned at the transport timeout`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("x".repeat(4_096)).throttleBody(64, 1, TimeUnit.SECONDS).build())
            server.start()
            val started = System.nanoTime()
            runBlocking(Dispatchers.Default) {
                assertFailsWith<InterruptedIOException> { withProviderTimeout("slowbody", 10_000, transportTimeoutMs = 400) { client.fetchText(req(server)) } }
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 3_000, "body read took ${ms}ms")
        }
    }

    @Test
    fun `cancellation interrupts a body that is still downloading`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("x".repeat(4_096)).throttleBody(64, 1, TimeUnit.SECONDS).build())
            server.start()
            val started = System.nanoTime()
            runBlocking(Dispatchers.Default) {
                assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> { withTimeout(300) { client.fetchText(req(server)) } }
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 2_000, "cancelled read returned after ${ms}ms")
        }
    }

    @Test
    fun `a 429 halves the service's request rate once per burst and the rate recovers`() {
        var now = 0L
        val l = RateLimiter(10, 1_000, key = "adaptive-test", clock = { now })
        l.slowDown()
        l.slowDown() // the same burst: one signal
        assertEquals(0.5, l.factor)
        now += 3_000
        l.slowDown()
        assertEquals(0.25, l.factor)
        repeat(10) { now += 3_000; l.slowDown() }
        assertEquals(RateLimiter.MIN_FACTOR, l.factor, "never below the floor")
        // Quiet for long enough: each acquire after a recovery period raises the rate by half again.
        runBlocking {
            repeat(12) { now += RateLimiter.RECOVERY_MS + 1; l.acquire(0) }
        }
        assertEquals(1.0, l.factor)
    }

    @Test
    fun `a site stricter than our rate gets far fewer 429s once the rate adapts`() = runBlocking {
        MockWebServer().use { server ->
            // Server accepts 3 requests per second; we are configured for 20.
            val hits = java.util.concurrent.ConcurrentLinkedQueue<Long>()
            var r429 = 0
            server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse = synchronized(hits) {
                    val t = System.currentTimeMillis()
                    hits.removeIf { t - it > 1_000 }
                    if (hits.size >= 3) { r429++; MockResponse.Builder().code(429).addHeader("Retry-After", "1").build() }
                    else { hits += t; MockResponse.Builder().code(200).body("ok").build() }
                }
            }
            server.start()
            val limiter = RateLimiter(20, 1_000, key = "strict-adaptive-${System.nanoTime()}")
            val client = OkHttpClient()
            var ok = 0
            withContext(Dispatchers.IO) {
                repeat(60) {
                    runCatching { client.fetchText(Request.Builder().url(server.url("/")).build(), retries = 2, limiter = limiter) }.onSuccess { ok++ }
                }
            }
            assertTrue(limiter.factor < 0.5, "slowed down: ${limiter.factor}")
            assertTrue(r429 < 15, "429s stay rare after adapting: $r429 of ${server.requestCount} (ok $ok)")
        }
    }
}
