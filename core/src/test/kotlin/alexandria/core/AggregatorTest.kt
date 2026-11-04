package alexandria.core

import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.fallback.NoReadableReleaseException
import alexandria.core.metadata.AniListClient
import alexandria.core.metadata.MangaBakaClient
import alexandria.core.metadata.MetadataResolver
import alexandria.core.model.ExternalIds
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.HttpError
import alexandria.core.ranking.ProviderHealth
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline end-to-end tests with fake providers; metadata services are disabled so no network is used. */
class AggregatorTest {

    private class FakeAdapter(
        override val id: String,
        private val title: String,
        private val chapters: List<Int>,
        var failPages: Boolean = false,
        var failSearch: Boolean = false,
        var slowSearchMs: Long = 0,
        private val ids: ExternalIds = ExternalIds(),
    ) : SourceAdapter {
        override val name = id.replaceFirstChar { it.uppercase() }
        override val baseUrl = "https://$id.test"
        override val languages: Set<String>? = setOf("en")
        val searches = AtomicInteger()
        val pageCalls = AtomicInteger()

        override suspend fun search(query: String): List<SourceSeries> {
            searches.incrementAndGet()
            if (slowSearchMs > 0) delay(slowSearchMs)
            if (failSearch) throw HttpError(503, "$baseUrl/search")
            return listOf(SourceSeries(id, "s-$id", title, ids = ids), SourceSeries(id, "novel-$id", "$title Novel"))
        }

        override suspend fun chapters(series: SourceSeries, languages: List<String>) =
            chapters.map { SourceChapter(id, series.id, "$id-c$it", "Chapter $it", language = "en") }

        override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
            pageCalls.incrementAndGet()
            if (failPages) throw HttpError(500, "$baseUrl/${chapter.id}")
            return (0 until 10).map { ReaderPage(it, "https://img.$id.test/${chapter.id}/$it.jpg") }
        }

        override fun seriesWebUrl(seriesId: String) = "$baseUrl/$seriesId"
    }

    private val noMeta = AggregatorSettings(mangabaka = false, anilist = false, qualityOptimization = false, requestTimeoutSeconds = 2)

    private fun aggregator(vararg adapters: SourceAdapter): Aggregator {
        val store = InMemoryStore()
        val cache = CacheManager(store)
        val client = OkHttpClient()
        val json = Json { ignoreUnknownKeys = true }
        val meta = MetadataResolver(MangaBakaClient(client, json, "t"), AniListClient(client, json, "t"), cache)
        // Detached search work runs unconfined so it shares the test's virtual clock (on Dispatchers.IO it raced the
        // virtual 6 s search deadline and made these tests flaky under load).
        return Aggregator(SourceRegistry(adapters.toList(), ProviderHealth(store)), meta, cache, client, detachedDispatcher = Dispatchers.Unconfined)
    }

    @Test
    fun `search groups identical titles and excludes other editions`() = runTest {
        val a = FakeAdapter("alpha", "Example Series", (1..50).toList())
        val b = FakeAdapter("beta", "Example Series", (45..75).toList())
        val cards = aggregator(a, b).search("example", noMeta)
        val main = cards.filter { it.title == "Example Series" }
        assertEquals(1, main.size, "$cards")
        assertTrue(cards.any { it.title == "Example Series Novel" }) // a separate entry, never merged
    }

    @Test
    fun `one entry aggregates chapters from several providers`() = runTest {
        val a = FakeAdapter("alpha", "Example Series", (1..50).toList())
        val b = FakeAdapter("beta", "Example Series", (45..75).toList())
        val c = FakeAdapter("gamma", "Example Series", (70..100).toList())
        val agg = aggregator(a, b, c)
        val key = agg.search("example", noMeta).first { it.title == "Example Series" }.key
        val update = agg.update(key, null, fetchDetails = true, fetchChapters = true, noMeta)
        val chapters = update.chapters!!
        assertEquals(100, chapters.size)
        assertEquals("Chapter 100", chapters.first().name) // newest first for the host
        assertEquals(setOf("alpha", "beta", "gamma"), update.state.bindings.keys)
        assertTrue(chapters.all { it.url.startsWith(key.value + "|en:c:") })
        // Stable identities across refreshes.
        assertEquals(chapters.map { it.url }, agg.update(key, update.state, false, true, noMeta).chapters!!.map { it.url })
    }

    @Test
    fun `missing chapter recovery can be turned off independently`() = runTest {
        val a = FakeAdapter("alpha", "Example Series", listOf(1, 2, 3, 5, 6))
        val b = FakeAdapter("beta", "Example Series", listOf(4, 5, 6))
        val agg = aggregator(a, b)
        val key = agg.search("example", noMeta).first { it.title == "Example Series" }.key
        assertEquals(6, agg.update(key, null, false, true, noMeta).chapters!!.size)
        val off = agg.update(key, null, false, true, noMeta.copy(missingChapterRecovery = false)).chapters!!
        assertEquals(listOf("Chapter 6", "Chapter 5", "Chapter 3", "Chapter 2", "Chapter 1"), off.map { it.name })
        // Chapter 5 still keeps beta as an alternative for fallback.
        assertEquals(2, off.first { it.name == "Chapter 5" }.releases.size)
    }

    @Test
    fun `failing provider falls back to the next one`() = runTest {
        val a = FakeAdapter("alpha", "Example Series", (1..5).toList(), failPages = true)
        val b = FakeAdapter("beta", "Example Series", (1..5).toList())
        val agg = aggregator(a, b)
        val s = noMeta.copy(sourceOrder = listOf("alpha", "beta"))
        val key = agg.search("example", s).first { it.title == "Example Series" }.key
        val ch = agg.update(key, null, false, true, s).chapters!!.first()
        val res = agg.pages(ch.url, ch.releases, s)
        assertEquals("beta", res.chosen.sourceId)
        assertEquals(10, res.pages.size)
        assertTrue(res.pages.all { it.imageUrl.contains("#loa-p=") })
    }

    @Test
    fun `fallback disabled surfaces the failure`() = runTest {
        val a = FakeAdapter("alpha", "Example Series", (1..5).toList(), failPages = true)
        val b = FakeAdapter("beta", "Example Series", (1..5).toList())
        val agg = aggregator(a, b)
        val s = noMeta.copy(sourceOrder = listOf("alpha", "beta"), sourceFallback = false)
        val key = agg.search("example", s).first { it.title == "Example Series" }.key
        val ch = agg.update(key, null, false, true, s).chapters!!.first()
        assertFailsWith<NoReadableReleaseException> { agg.pages(ch.url, ch.releases, s) }
        assertEquals(0, b.pageCalls.get())
    }

    @Test
    fun `disabled providers are not searched and not used for fallback`() = runTest {
        val a = FakeAdapter("alpha", "Example Series", (1..5).toList(), failPages = true)
        val b = FakeAdapter("beta", "Example Series", (1..8).toList())
        val agg = aggregator(a, b)
        val all = noMeta.copy(sourceOrder = listOf("alpha", "beta"))
        val key = agg.search("example", all).first { it.title == "Example Series" }.key
        val chapters = agg.update(key, null, false, true, all).chapters!!
        val onlyAlpha = all.copy(enabledSources = setOf("alpha"))
        val before = b.searches.get()
        agg.search("example", onlyAlpha)
        assertEquals(before, b.searches.get())
        val ch = chapters.first { it.name == "Chapter 1" }
        assertFailsWith<NoReadableReleaseException> { agg.pages(ch.url, ch.releases, onlyAlpha) }
        assertEquals(0, b.pageCalls.get())
        // With beta disabled the list only shows what alpha has.
        assertEquals(5, agg.update(key, null, false, true, onlyAlpha).chapters!!.size)
    }

    @Test
    fun `unavailable or timing out provider does not break search`() = runTest {
        val a = FakeAdapter("alpha", "Example Series", (1..5).toList(), failSearch = true)
        val b = FakeAdapter("beta", "Example Series", (1..5).toList(), slowSearchMs = 10_000)
        val c = FakeAdapter("gamma", "Example Series", (1..5).toList())
        val cards = aggregator(a, b, c).search("example", noMeta)
        assertTrue(cards.any { it.key.value == "p:gamma:s-gamma" }, "$cards")
    }

    @Test
    fun `memo-less host rebuilds releases from persisted links`() = runTest {
        val a = FakeAdapter("alpha", "Example Series", (1..5).toList())
        val agg = aggregator(a)
        val key = agg.search("example", noMeta).first { it.title == "Example Series" }.key
        val ch = agg.update(key, null, false, true, noMeta).chapters!!.first()
        val fresh = Aggregator(agg.registry, agg.metadata, agg.cache, OkHttpClient(), detachedDispatcher = Dispatchers.Unconfined)
        assertEquals(10, fresh.pages(ch.url, null, noMeta).pages.size)
    }

    @Test
    fun `deduplication off lists every release`() = runTest {
        val a = FakeAdapter("alpha", "Example Series", (1..3).toList())
        val b = FakeAdapter("beta", "Example Series", (1..3).toList())
        val agg = aggregator(a, b)
        val key = agg.search("example", noMeta).first { it.title == "Example Series" }.key
        val s = noMeta.copy(deduplicate = false)
        val chapters = agg.update(key, null, false, true, s).chapters!!
        assertEquals(6, chapters.size)
        assertEquals(chapters.size, chapters.map { it.url }.toSet().size)
        assertTrue(chapters.all { it.releases.size == 2 })
    }

    @Test
    fun `title-only search links are verified with details before use`() = runTest {
        // beta's "Example Series" turns out (from its details page) to be a different work with other ids.
        val a = FakeAdapter("alpha", "Example Series", (1..3).toList(), ids = ExternalIds(anilist = 1))
        val b = object : SourceAdapter by FakeAdapter("beta", "Example Series", (1..9).toList()) {
            override suspend fun details(series: SourceSeries) = series.copy(ids = ExternalIds(anilist = 999), detailed = true)
        }
        val agg = aggregator(a, b)
        val key = agg.search("example", noMeta).first { it.title == "Example Series" }.key
        val update = agg.update(key, null, fetchDetails = true, fetchChapters = true, noMeta)
        assertFalse("beta" in update.state.bindings, "${update.state.bindings.keys}")
        assertEquals(3, update.chapters!!.size)
    }

    @Test
    fun `manual link overrides and unlinks providers`() = runTest {
        val a = FakeAdapter("alpha", "Example Series", (1..3).toList())
        val b = FakeAdapter("beta", "Example Series", (1..9).toList())
        val agg = aggregator(a, b)
        val key = agg.search("example", noMeta).first { it.title == "Example Series" }.key
        val s = noMeta.copy(manualLinks = mapOf(key.value to listOf("beta:-")))
        val update = agg.update(key, null, true, true, s)
        assertFalse("beta" in update.state.bindings)
        assertEquals(3, update.chapters!!.size)
    }
}
