package alexandria.core.reliability

import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.fallback.FallbackInterceptor
import alexandria.core.fallback.ReaderResolver
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.ranking.ChapterQualityAnalyzer
import alexandria.core.ranking.ProviderHealth
import alexandria.core.ranking.SourceRanker
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Image-level recovery when a page fails after its response headers arrived. */
class ImageRecoveryTest {
    private class ServerPages(override val id: String, private val server: MockWebServer) : SourceAdapter {
        override val name = id
        override val baseUrl = "https://$id.test"
        override val languages: Set<String>? = setOf("en")
        override suspend fun search(query: String) = emptyList<SourceSeries>()
        override suspend fun chapters(series: SourceSeries, languages: List<String>) = emptyList<SourceChapter>()
        override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean) =
            (0 until 3).map { ReaderPage(it, server.url("/$id/$it.jpg").toString()) }
        override fun seriesWebUrl(seriesId: String) = baseUrl
    }

    private fun setup(server: MockWebServer, fallback: Boolean = true): Pair<OkHttpClient, String> {
        val store = InMemoryStore()
        val health = ProviderHealth(store)
        val ranker = SourceRanker(health)
        val registry = SourceRegistry(listOf(ServerPages("alpha", server), ServerPages("beta", server)), health)
        val resolver = ReaderResolver(registry, ranker, ChapterQualityAnalyzer(ranker, health), CacheManager(store), OkHttpClient())
        val s = AggregatorSettings(qualityOptimization = false, sourceOrder = listOf("alpha", "beta"), sourceFallback = fallback)
        val ref = "mb:1|en:c:1"
        val res = runBlocking { resolver.resolve(ref, listOf(release("alpha"), release("beta")), s) }
        assertEquals("alpha", res.chosen.sourceId)
        val client = OkHttpClient.Builder().addInterceptor(FallbackInterceptor({ resolver }, { s })).build()
        return client to FallbackInterceptor.tagPage(res.pages[1].imageUrl, ref, 1, "alpha")
    }

    @Test
    fun `a page whose body is cut off is served from the next release`() {
        MockWebServer().use { server ->
            server.start()
            val (client, url) = setup(server)
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body("y".repeat(128 * 1024)).onResponseBody(SocketEffect.ShutdownConnection).build())
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body("BETA-PAGE-1").build())
            val body = client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }
            assertEquals("BETA-PAGE-1", body)
            assertEquals("/alpha/1.jpg", server.takeRequest().target)
            assertEquals("/beta/1.jpg", server.takeRequest().target)
        }
    }

    @Test
    fun `an html page served as an image is not handed to the reader`() {
        MockWebServer().use { server ->
            server.start()
            val (client, url) = setup(server)
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/octet-stream").body("<!DOCTYPE html><html>challenge</html>").build())
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/webp").body("BETA").build())
            assertEquals("BETA", client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() })
        }
    }

    @Test
    fun `without fallback the body is streamed untouched`() {
        MockWebServer().use { server ->
            server.start()
            val (client, url) = setup(server, fallback = false)
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body("z".repeat(64 * 1024)).onResponseBody(SocketEffect.ShutdownConnection).build())
            assertFailsWith<java.io.IOException> { client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() } }
            assertEquals(1, server.requestCount)
        }
    }
}
