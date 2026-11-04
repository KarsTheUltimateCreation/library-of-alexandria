package alexandria.core.reliability

import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.fallback.FallbackInterceptor
import alexandria.core.fallback.ReaderResolver
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.fetch
import alexandria.core.ranking.ChapterQualityAnalyzer
import alexandria.core.ranking.ProviderHealth
import alexandria.core.ranking.SourceRanker
import alexandria.core.reconciliation.Release
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared fakes for the reliability tests. */
internal class PagesAdapter(
    override val id: String,
    var delayMs: Long = 0,
    var fail: Boolean = false,
    var pageCount: Int = 10,
) : SourceAdapter {
    override val name = id
    override val baseUrl = "https://$id.test"
    override val languages: Set<String>? = setOf("en")
    var calls = 0
    override suspend fun search(query: String) = listOf(SourceSeries(id, "s-$id", "Example"))
    override suspend fun chapters(series: SourceSeries, languages: List<String>) = emptyList<SourceChapter>()
    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        calls++
        if (delayMs > 0) delay(delayMs)
        if (fail) throw java.io.IOException("$id down")
        return (0 until pageCount).map { ReaderPage(it, "https://img.$id.test/${chapter.id}/$it.jpg") }
    }
    override fun seriesWebUrl(seriesId: String) = "$baseUrl/$seriesId"
}

internal fun release(sid: String, confidence: Double = 1.0) = Release(sid, "s-$sid", "$sid-c1", "Chapter 1", "en", confidence = confidence)

internal fun resolver(vararg adapters: SourceAdapter, store: InMemoryStore = InMemoryStore()): ReaderResolver {
    val health = ProviderHealth(store)
    val registry = SourceRegistry(adapters.toList(), health)
    val ranker = SourceRanker(health)
    return ReaderResolver(registry, ranker, ChapterQualityAnalyzer(ranker, health), CacheManager(store), OkHttpClient())
}

/**
 * Reproductions of the defects reported in the 1.6.5 review. Each asserts the *correct* behaviour, so on the
 * unmodified 1.6.5 code these fail; they stay as regression tests.
 */
class BaselineReproTest {
    private val fastest = AggregatorSettings(qualityOptimization = false, requestTimeoutSeconds = 1, sourceOrder = listOf("alpha", "beta", "gamma"))

    @Test
    fun `provider timeout falls back to the next release`() = runTest {
        val alpha = PagesAdapter("alpha", delayMs = 5_000)
        val beta = PagesAdapter("beta")
        val res = resolver(alpha, beta).resolve("mb:1|en:c:1", listOf(release("alpha"), release("beta")), fastest)
        assertEquals("beta", res.chosen.sourceId)
    }

    @Test
    fun `cached choice respects a changed preferred source`() = runTest {
        val r = resolver(PagesAdapter("alpha"), PagesAdapter("beta"))
        val releases = listOf(release("alpha"), release("beta"))
        assertEquals("alpha", r.resolve("mb:1|en:c:1", releases, fastest).chosen.sourceId)
        val res = r.resolve("mb:1|en:c:1", releases, fastest.copy(preferredSource = "beta"))
        assertEquals("beta", res.chosen.sourceId, "notes=${res.notes}")
    }

    @Test
    fun `retry-after longer than the budget is not retried early`() = runTest {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "120").build()) }
            server.start()
            runCatching { OkHttpClient().fetch(Request.Builder().url(server.url("/")).build()) }
            assertEquals(1, server.requestCount, "a 120 s Retry-After must not be answered with another request seconds later")
        }
    }

    @Test
    fun `cover body failure after headers falls back to the next candidate`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body("x".repeat(64 * 1024))
                    .onResponseBody(SocketEffect.ShutdownConnection).build(),
            )
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body("GOOD").build())
            server.start()
            val url = FallbackInterceptor.tagCover(server.url("/a.png").toString(), listOf(FallbackInterceptor.CoverAlt(server.url("/b.jpg").toString())))
            val client = OkHttpClient.Builder().addInterceptor(FallbackInterceptor({ null }, { AggregatorSettings() })).build()
            val body = runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() } }
            assertTrue(body.getOrNull() == "GOOD", "got $body after ${server.requestCount} request(s)")
        }
    }
}
