package alexandria.core

import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.fallback.FallbackInterceptor
import alexandria.core.net.ErrorKind
import alexandria.core.net.HttpError
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetch
import alexandria.core.ranking.ProviderHealth
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheManagerTest {
    @Test
    fun `ttl expiry and persistence`() {
        var now = 1_000L
        val store = InMemoryStore()
        val cache = CacheManager(store, clock = { now })
        cache.put("a", String.serializer(), "hello")
        assertEquals("hello", cache.get("a", String.serializer(), 100))
        now += 500
        assertNull(cache.get("a", String.serializer(), 100))
        // A fresh manager reads the persisted value.
        assertEquals("hello", CacheManager(store, clock = { now }).get("a", String.serializer(), 10_000))
    }

    @Test
    fun `eviction bounds persisted entries`() {
        var now = 0L
        val cache = CacheManager(InMemoryStore(), maxPersistedEntries = 20, clock = { now++ })
        repeat(100) { cache.put("k$it", String.serializer(), "v") }
        assertTrue(cache.persistedCount() <= 20)
        assertEquals("v", cache.get("k99", String.serializer(), Long.MAX_VALUE))
    }

    @Test
    fun `clear by prefix`() {
        val cache = CacheManager(InMemoryStore())
        cache.put("meta:1", String.serializer(), "x")
        cache.put("state:1", String.serializer(), "y")
        cache.clear("meta:")
        assertNull(cache.get("meta:1", String.serializer(), Long.MAX_VALUE))
        assertEquals("y", cache.get("state:1", String.serializer(), Long.MAX_VALUE))
    }
}

class KeysAndSettingsTest {
    @Test
    fun `series keys round trip and reject junk`() {
        listOf("mb:3397", "al:105398", "p:mangapill:2/solo-leveling", "p:weebcentral:01J76XYCPSY3C4BNPBRY8JMCBE").forEach {
            assertEquals(it, SeriesKey.parse(it)!!.value)
        }
        listOf("mb:-1", "mb:abc", "x:1", "p:mangadex:", "p:mangadex:../../etc<script>").forEach { assertNull(SeriesKey.parse(it), it) }
        val (k, c) = ChapterUrl.parse("p:mangapill:2/slug|en:c:10.5")!!
        assertEquals("p:mangapill:2/slug", k.value)
        assertEquals("en:c:10.5", c)
    }

    @Test
    fun `manual links parse`() {
        val links = AggregatorSettings.parseManualLinks("mb:1 = mangadex:abc, weebcentral:-; al:5=mangapill:2/x\nbroken line")
        assertEquals(listOf("mangadex:abc", "weebcentral:-"), links["mb:1"])
        assertEquals(listOf("mangapill:2/x"), links["al:5"])
        assertEquals(2, links.size)
    }

    @Test
    fun `language matching`() {
        val s = AggregatorSettings(languages = listOf("en", "pt"))
        assertTrue(s.acceptsLanguage("EN"))
        assertTrue(s.acceptsLanguage("pt-br"))
        assertFalse(s.acceptsLanguage("es"))
        assertTrue(AggregatorSettings(languages = emptyList()).acceptsLanguage("es"))
    }
}

class NetworkTest {
    @Test
    fun `retries server errors and honours retry-after`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "1").build())
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            server.start()
            val r = OkHttpClient().fetch(Request.Builder().url(server.url("/")).build(), retries = 2)
            assertEquals("ok", r.body.string())
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `classifies not found without retrying`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(404).build())
            server.start()
            val e = assertFailsWith<HttpError> { OkHttpClient().fetch(Request.Builder().url(server.url("/")).build()) }
            assertEquals(ErrorKind.NOT_FOUND, e.kind)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `rate limiter spaces requests`() = runTest {
        var now = 0L
        val limiter = RateLimiter(2, 1_000) { now }
        limiter.acquire(); limiter.acquire()
        // Third permit needs time; advance the fake clock to let it through.
        now += 600
        limiter.acquire()
    }

    @Test
    fun `circuit breaker opens after consecutive failures and ignores 404`() {
        var now = 0L
        val h = ProviderHealth(InMemoryStore()) { now }
        repeat(10) { h.recordFailure("a", ErrorKind.NOT_FOUND) }
        assertFalse(h.isCircuitOpen("a"))
        repeat(4) { h.recordFailure("a", ErrorKind.TIMEOUT) }
        assertTrue(h.isCircuitOpen("a"))
        now += 11 * 60_000
        assertFalse(h.isCircuitOpen("a"))
        h.recordSuccess("a", 100)
        assertEquals(0, h.get("a").consecutiveFailures)
    }
}

class FallbackTokenTest {
    @Test
    fun `page and cover tokens round trip`() {
        val tagged = FallbackInterceptor.tagPage("https://cdn/x.jpg", "mb:1|en:c:10", 7, "weebcentral")
        assertEquals(FallbackInterceptor.PageToken("mb:1|en:c:10", 7, "weebcentral"), FallbackInterceptor.pageToken(tagged))
        assertEquals("https://cdn/x.jpg", FallbackInterceptor.stripTag(tagged))

        val cover = FallbackInterceptor.tagCover("https://a/c.jpg", listOf(FallbackInterceptor.CoverAlt("https://b/c.jpg", mapOf("Referer" to "https://b/"))))
        val alts = FallbackInterceptor.decodeCoverToken(cover.substringAfter("#" + FallbackInterceptor.COVER_PREFIX))
        assertEquals("https://b/c.jpg", alts.single().u)
        assertEquals(emptyList(), FallbackInterceptor.decodeCoverToken("%%%"))
    }

    @Test
    fun `cover fallback serves next candidate when primary fails`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>blocked").build())
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body("JPEGDATA").build())
            server.start()
            val url = FallbackInterceptor.tagCover(
                server.url("/primary.jpg").toString(),
                listOf(FallbackInterceptor.CoverAlt(server.url("/html.jpg").toString()), FallbackInterceptor.CoverAlt(server.url("/good.jpg").toString(), mapOf("Referer" to "https://ref/"))),
            )
            val client = OkHttpClient.Builder().addInterceptor(FallbackInterceptor({ null }, { AggregatorSettings() })).build()
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                assertEquals("JPEGDATA", r.body.string())
            }
            assertEquals("/primary.jpg", server.takeRequest().target)
            assertEquals("/html.jpg", server.takeRequest().target)
            val third = server.takeRequest()
            assertEquals("/good.jpg", third.target)
            assertEquals("https://ref/", third.headers["Referer"])
        }
    }

    @Test
    fun `cover fallback disabled propagates failure`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(404).build())
            server.start()
            val url = FallbackInterceptor.tagCover(server.url("/p.jpg").toString(), listOf(FallbackInterceptor.CoverAlt(server.url("/q.jpg").toString())))
            val client = OkHttpClient.Builder().addInterceptor(FallbackInterceptor({ null }, { AggregatorSettings(coverFallback = false) })).build()
            assertFailsWith<java.io.IOException> { client.newCall(Request.Builder().url(url).build()).execute() }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `untagged requests pass through untouched`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(500).build())
            server.start()
            val client = OkHttpClient.Builder().addInterceptor(FallbackInterceptor({ null }, { AggregatorSettings() })).build()
            client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { assertEquals(500, it.code) }
        }
    }
}
