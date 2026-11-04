package alexandria.core.reliability

import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.config.QualityMode
import alexandria.core.fallback.NoReadableReleaseException
import alexandria.core.fallback.ReaderResolver
import alexandria.core.ranking.ChapterQualityAnalyzer
import alexandria.core.ranking.ProviderHealth
import alexandria.core.ranking.SourceRanker
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A probe client that answers every image request with a 1000×1400 PNG header, without any network. */
internal fun fakeImageClient(): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
    val png = ByteArray(64).also {
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(it)
        it[16] = 0; it[17] = 0; it[18] = 0x03; it[19] = 0xE8.toByte() // width 1000
        it[20] = 0; it[21] = 0; it[22] = 0x05; it[23] = 0x78 // height 1400
    }
    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("OK")
        .body(png.toResponseBody("image/png".toMediaType())).build()
}).build()

/** Provider-owned timeouts are ordinary failures for fallback; the caller's cancellation is not. */
class FallbackTimeoutTest {
    private val fastest = AggregatorSettings(qualityOptimization = false, requestTimeoutSeconds = 2, sourceOrder = listOf("alpha", "beta", "gamma"))
    private val best = fastest.copy(qualityOptimization = true, qualityMode = QualityMode.BEST, requestTimeoutSeconds = 20)

    private fun resolverWith(vararg adapters: SourceAdapter): Pair<ReaderResolver, ProviderHealth> {
        val store = InMemoryStore()
        val health = ProviderHealth(store)
        val ranker = SourceRanker(health)
        val registry = SourceRegistry(adapters.toList(), health)
        return ReaderResolver(registry, ranker, ChapterQualityAnalyzer(ranker, health), CacheManager(store), fakeImageClient()) to health
    }

    @Test
    fun `cached choice that starts timing out falls back`() = runTest {
        val alpha = PagesAdapter("alpha")
        val (r, health) = resolverWith(alpha, PagesAdapter("beta"))
        val releases = listOf(release("alpha"), release("beta"))
        assertEquals("alpha", r.resolve("mb:1|en:c:1", releases, fastest).chosen.sourceId)
        alpha.delayMs = 60_000
        val res = r.resolve("mb:1|en:c:1", releases, fastest)
        assertEquals("beta", res.chosen.sourceId)
        assertEquals(1, health.get("alpha").timeouts, "the provider's own timeout counts against it")
    }

    @Test
    fun `parallel comparison survives a stalled candidate without waiting for it`() = runBlocking(Dispatchers.Default) {
        // Real time: image probes complete on OkHttp threads, which a virtual clock would race past.
        val (r, _) = resolverWith(PagesAdapter("alpha", delayMs = 15_000), PagesAdapter("beta"), PagesAdapter("gamma"))
        val started = System.nanoTime()
        val res = r.resolve("mb:1|en:c:1", listOf(release("alpha"), release("beta"), release("gamma")), best)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(res.chosen.sourceId in setOf("beta", "gamma"), res.chosen.sourceId)
        assertTrue(ms < 10_000, "comparison waited ${ms}ms for the stalled provider")
        assertTrue(res.notes.any { it.startsWith("beta=") } && res.notes.any { it.contains("1000px") }, "probes ran: ${res.notes}")
        assertTrue(res.fallbacks.any { it.sourceId == "alpha" }, "the stalled provider stays a fallback")
    }

    @Test
    fun `parallel comparison where one contender times out still compares the rest`() = runTest {
        val (r, _) = resolverWith(PagesAdapter("alpha", delayMs = 60_000), PagesAdapter("beta"), PagesAdapter("gamma"))
        val s = best.copy(requestTimeoutSeconds = 2)
        val res = r.resolve("mb:1|en:c:1", listOf(release("alpha"), release("beta"), release("gamma")), s)
        assertTrue(res.chosen.sourceId in setOf("beta", "gamma"))
    }

    @Test
    fun `an enclosing deadline still cancels resolution`() = runTest {
        val (r, health) = resolverWith(PagesAdapter("alpha", delayMs = 60_000), PagesAdapter("beta", delayMs = 60_000))
        val s = fastest.copy(requestTimeoutSeconds = 20)
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(500) { r.resolve("mb:1|en:c:1", listOf(release("alpha"), release("beta")), s) }
        }
        assertEquals(0, health.get("alpha").failures, "the caller's deadline is not the provider's failure")
    }

    @Test
    fun `leaving the reader cancels without recording failures or trying more sources`() = runTest {
        val alpha = PagesAdapter("alpha", delayMs = 60_000)
        val beta = PagesAdapter("beta")
        val (r, health) = resolverWith(alpha, beta)
        val job = launch { r.resolve("mb:1|en:c:1", listOf(release("alpha"), release("beta")), fastest.copy(requestTimeoutSeconds = 20)) }
        testScheduler.advanceTimeBy(1_000)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, beta.calls, "no fallback after cancellation")
        assertEquals(0, health.get("alpha").failures)
    }

    @Test
    fun `every provider timing out reports each attempt`() = runTest {
        val (r, _) = resolverWith(PagesAdapter("alpha", delayMs = 60_000), PagesAdapter("beta", delayMs = 60_000))
        val e = assertFailsWith<NoReadableReleaseException> { r.resolve("mb:1|en:c:1", listOf(release("alpha"), release("beta")), fastest) }
        assertEquals(listOf("alpha", "beta"), e.attempts.map { it.first })
    }
}
