package alexandria.core.reliability

import alexandria.core.Aggregator
import alexandria.core.ChapterUrl
import alexandria.core.Refresh
import alexandria.core.SeriesKey
import alexandria.core.cache.BoundedCache
import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.cache.KeyValueStore
import alexandria.core.cache.ReleaseMemo
import alexandria.core.cache.SingleFlight
import alexandria.core.cache.mergeReleases
import alexandria.core.config.AggregatorSettings
import alexandria.core.config.QualityMode
import alexandria.core.metadata.AniListClient
import alexandria.core.metadata.MangaBakaClient
import alexandria.core.metadata.MetadataResolver
import alexandria.core.model.ContentKind
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.HttpError
import alexandria.core.ranking.ProviderHealth
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A provider that carries "Example Series" with the given chapters (or not at all). */
private class Provider(
    override val id: String,
    var chapterNumbers: List<Int>,
    val carries: Boolean = true,
    var failChapters: Boolean = false,
    var chaptersDelayMs: Long = 0,
    override val specialties: Set<ContentKind>? = null,
) : SourceAdapter {
    override val name = id
    override val baseUrl = "https://$id.test"
    override val languages: Set<String>? = setOf("en")
    val searches = AtomicInteger()
    val chapterCalls = AtomicInteger()
    val pageCalls = AtomicInteger()

    override suspend fun search(query: String): List<SourceSeries> {
        searches.incrementAndGet()
        return if (carries) listOf(SourceSeries(id, "s-$id", "Example Series"), SourceSeries(id, "n-$id", "Example Series Novel", kind = ContentKind.NOVEL)) else emptyList()
    }

    /** Details name the work, as real providers' detail pages do (the anchor of a provider-only series needs it). */
    override suspend fun details(series: SourceSeries): SourceSeries =
        if (series.id == "s-$id") series.copy(title = "Example Series", detailed = true) else series

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        chapterCalls.incrementAndGet()
        if (chaptersDelayMs > 0) delay(chaptersDelayMs)
        if (failChapters) throw HttpError(503, "$baseUrl/chapters")
        return chapterNumbers.map { SourceChapter(id, series.id, "$id-c$it", "Chapter $it") }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
        pageCalls.incrementAndGet()
        return (0 until 8).map { ReaderPage(it, "https://img.$id.test/${chapter.id}/$it.jpg") }
    }

    override fun seriesWebUrl(seriesId: String) = "$baseUrl/$seriesId"
}

/** Counts writes so tests can see what reaches persistent storage. */
private class CountingStore : KeyValueStore {
    val inner = InMemoryStore()
    val puts = AtomicInteger()
    override fun get(key: String) = inner.get(key)
    override fun put(key: String, value: String) { puts.incrementAndGet(); inner.put(key, value) }
    override fun remove(key: String) = inner.remove(key)
    override fun keys() = inner.keys()
}

class AggregationReliabilityTest {
    private var now = 1_000_000L
    private val clock = { now }
    private val s = AggregatorSettings(mangabaka = false, anilist = false, qualityOptimization = false, requestTimeoutSeconds = 5)
    private val key = SeriesKey.Provider("alpha", "s-alpha")

    private fun aggregator(adapters: List<SourceAdapter>, store: KeyValueStore = InMemoryStore(), listStore: KeyValueStore = store): Aggregator {
        val cache = CacheManager(store, clock = clock)
        val client = OkHttpClient()
        val json = Json { ignoreUnknownKeys = true }
        val meta = MetadataResolver(MangaBakaClient(client, json, "t"), AniListClient(client, json, "t"), cache)
        return Aggregator(
            SourceRegistry(adapters, ProviderHealth(store, clock)), meta, cache, fakeImageClient(), clock = clock,
            listCache = CacheManager(listStore, clock = clock, maxPersistedEntries = 40), detachedDispatcher = Dispatchers.Unconfined,
        )
    }

    // ------------------------------------------------------------ chapter-list freshness

    @Test
    fun `routine rebuilds reuse fresh chapter lists, explicit refreshes fetch again`() = runTest {
        val alpha = Provider("alpha", (1..10).toList())
        val beta = Provider("beta", (1..10).toList())
        val agg = aggregator(listOf(alpha, beta))
        agg.update(key, null, fetchDetails = false, fetchChapters = true, s)
        assertEquals(1, alpha.chapterCalls.get())
        agg.update(key, null, false, true, s, Refresh.ROUTINE)
        agg.update(key, null, false, true, s, Refresh.EXPLICIT) // the host asking twice in a row
        assertEquals(1, alpha.chapterCalls.get(), "duplicate calls within a minute share one fetch")
        now += 2 * 60_000
        agg.update(key, null, false, true, s, Refresh.EXPLICIT)
        assertEquals(2, alpha.chapterCalls.get(), "a real refresh fetches again")
        now += 10 * 60_000
        agg.update(key, null, false, true, s, Refresh.ROUTINE)
        assertEquals(2, alpha.chapterCalls.get(), "routine work reuses a list up to 30 minutes old")
        now += 31 * 60_000
        agg.update(key, null, false, true, s, Refresh.ROUTINE)
        assertEquals(3, alpha.chapterCalls.get())
    }

    @Test
    fun `concurrent updates of one series share one chapter fetch per provider`() = runTest {
        val alpha = Provider("alpha", (1..10).toList(), chaptersDelayMs = 500)
        val beta = Provider("beta", (1..10).toList(), chaptersDelayMs = 500)
        val agg = aggregator(listOf(alpha, beta))
        agg.update(key, null, false, false, s) // link first
        val results = listOf(
            async { agg.update(key, null, fetchDetails = true, fetchChapters = true, s) },
            async { agg.update(key, null, fetchDetails = false, fetchChapters = true, s) },
            async { agg.update(key, null, fetchDetails = false, fetchChapters = true, s) },
        ).awaitAll()
        assertTrue(results.all { it.chapters?.size == 10 })
        assertEquals(1, alpha.chapterCalls.get())
        assertEquals(1, beta.chapterCalls.get())
    }

    @Test
    fun `last known lists survive a restart and keep an outage from shrinking the list`() = runTest {
        val store = InMemoryStore()
        val alpha = Provider("alpha", (1..10).toList())
        val beta = Provider("beta", (5..20).toList())
        aggregator(listOf(alpha, beta), store).update(key, null, false, true, s)

        // "Restart": new instances over the same storage; beta is now down.
        beta.failChapters = true
        now += 60 * 60_000
        val after = aggregator(listOf(alpha, beta), store).update(key, null, false, true, s)
        assertEquals(20, after.chapters!!.size, "beta's last known chapters are kept")
        assertTrue(after.unavailableSources.isEmpty())
    }

    @Test
    fun `a failing source with nothing cached is reported so the host keeps its chapters`() = runTest {
        val alpha = Provider("alpha", (1..10).toList())
        val beta = Provider("beta", (5..20).toList(), failChapters = true)
        val update = aggregator(listOf(alpha, beta)).update(key, null, false, true, s)
        assertEquals(setOf("beta"), update.unavailableSources)
    }

    @Test
    fun `pages after a restart are resolved from stored lists without refetching chapters`() = runTest {
        val store = InMemoryStore()
        val alpha = Provider("alpha", (1..10).toList())
        val beta = Provider("beta", (1..10).toList())
        val first = aggregator(listOf(alpha, beta), store).update(key, null, false, true, s)
        val url = first.chapters!!.first { it.name == "Chapter 3" }.url
        val calls = alpha.chapterCalls.get() + beta.chapterCalls.get()

        val restarted = aggregator(listOf(alpha, beta), store)
        val res = restarted.pages(url, memoReleases = null, s)
        assertEquals(8, res.pages.size)
        assertEquals(calls, alpha.chapterCalls.get() + beta.chapterCalls.get())
        assertEquals(1, res.fallbacks.size, "both releases known offline")
    }

    @Test
    fun `unchanged chapter lists are not rewritten to storage`() = runTest {
        val listStore = CountingStore()
        val alpha = Provider("alpha", (1..10).toList())
        val beta = Provider("beta", (1..10).toList())
        val agg = aggregator(listOf(alpha, beta), listStore = listStore)
        agg.update(key, null, false, true, s)
        val writes = listStore.puts.get()
        now += 5 * 60_000
        agg.update(key, null, false, true, s, Refresh.EXPLICIT)
        assertEquals(2, alpha.chapterCalls.get())
        assertEquals(writes, listStore.puts.get())
        alpha.chapterNumbers = (1..11).toList()
        now += 5 * 60_000
        agg.update(key, null, false, true, s, Refresh.EXPLICIT)
        assertEquals(writes + 1, listStore.puts.get(), "a changed list is written")
    }

    // ------------------------------------------------------------ release selection

    @Test
    fun `a truncated memo is merged with the complete canonical release list`() = runTest {
        val providers = listOf("alpha", "beta", "gamma", "delta").map { Provider(it, (1..5).toList()) }
        val agg = aggregator(providers)
        val update = agg.update(key, null, false, true, s.copy(deepDiscovery = true))
        val ch = update.chapters!!.first { it.name == "Chapter 2" }
        assertEquals(4, ch.releases.size)
        val memo = listOf(ReleaseMemo.decode(ReleaseMemo.encode(ch.releases.last()))!!)
        val res = agg.pages(ch.url, memo, s)
        assertEquals(4, 1 + res.fallbacks.size, "all four releases remain available for fallback")
    }

    @Test
    fun `memo keeps confidence and old memos decode as doubtful`() {
        val r = release("alpha", confidence = 0.55)
        assertEquals(0.55, ReleaseMemo.decode(ReleaseMemo.encode(r))!!.confidence)
        val legacy = ReleaseMemo.decode(listOf("alpha", "s-alpha", "c1").joinToString(ReleaseMemo.SEP))!!
        assertTrue(legacy.confidence < 0.8, "1.6.5 memos lack confidence: never trusted to win a comparison")
        assertEquals(null, ReleaseMemo.decode("garbage"))
        val merged = mergeReleases(listOf(release("alpha"), release("beta")), listOf(release("beta"), release("gamma")))
        assertEquals(listOf("alpha", "beta", "gamma"), merged.map { it.sourceId })
    }

    @Test
    fun `cached choices follow reading quality and preferred source changes`() {
        val base = AggregatorSettings()
        val fps = listOf(
            base, base.copy(qualityMode = QualityMode.DATA_SAVER), base.copy(qualityOptimization = false),
            base.copy(preferredSource = "beta"), base.copy(enabledSources = setOf("alpha")), base.copy(sourceOrder = listOf("beta")),
        ).map { it.selectionFingerprint() }
        assertEquals(fps.size, fps.toSet().size, "every selection input changes the cache identity")
        assertEquals(base.selectionFingerprint(), base.copy(highQualityCovers = false).selectionFingerprint(), "unrelated settings do not")
    }

    // ------------------------------------------------------------ progressive discovery

    @Test
    fun `opening a series searches only the most promising providers when coverage is good`() = runTest {
        val all = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta").map { Provider(it, (1..10).toList()) }
        aggregator(all).update(key, null, fetchDetails = true, fetchChapters = true, s)
        val searched = all.drop(1).count { it.searches.get() > 0 }
        assertEquals(3, searched, all.map { "${it.id}=${it.searches.get()}" }.toString())
    }

    @Test
    fun `missing chapters trigger a wider search that fills the gap`() = runTest {
        val alpha = Provider("alpha", (1..10).toList() + (15..20).toList())
        val partial = listOf("beta", "gamma", "delta").map { Provider(it, (1..10).toList() + (15..20).toList()) }
        val complete = Provider("omega", (1..20).toList())
        val agg = aggregator(listOf(alpha) + partial + complete)
        val update = agg.update(key, null, fetchDetails = true, fetchChapters = true, s)
        assertEquals(20, update.chapters!!.size, update.chapters!!.map { it.name }.toString())
        assertTrue("omega" in update.state.bindings)
    }

    @Test
    fun `a failing linked source triggers a wider search`() = runTest {
        val alpha = Provider("alpha", (1..10).toList())
        val broken = listOf("beta", "gamma", "delta").map { Provider(it, (1..10).toList(), failChapters = true) }
        val healthy = Provider("omega", (1..10).toList())
        val update = aggregator(listOf(alpha) + broken + healthy).update(key, null, true, true, s)
        assertTrue("omega" in update.state.bindings, update.state.bindings.keys.toString())
    }

    @Test
    fun `thorough discovery searches every provider at once`() = runTest {
        val all = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta").map { Provider(it, (1..10).toList()) }
        aggregator(all).update(key, null, true, true, s.copy(deepDiscovery = true))
        assertTrue(all.drop(1).all { it.searches.get() > 0 })
    }

    @Test
    fun `providers without the series are not searched again for a week and editions stay apart`() = runTest {
        val alpha = Provider("alpha", (1..5).toList() + (9..10).toList())
        val others = listOf("beta", "gamma", "delta", "epsilon").map { Provider(it, emptyList(), carries = false) }
        val agg = aggregator(listOf(alpha) + others)
        val first = agg.update(key, null, true, true, s)
        assertEquals(setOf("alpha"), first.state.bindings.keys, "the Novel edition is never linked")
        val counts = others.map { it.searches.get() }
        agg.update(key, first.state, true, true, s)
        assertEquals(counts, others.map { it.searches.get() })
        now += 8 * 24 * 60 * 60_000L
        agg.update(key, first.state, true, true, s)
        assertTrue(others.any { it.searches.get() > counts[others.indexOf(it)] })
    }

    @Test
    fun `specialty sites are asked later for other content types`() = runTest {
        val alpha = Provider("alpha", (1..10).toList())
        val manhwaSites = listOf("m1", "m2", "m3").map { Provider(it, (1..10).toList(), specialties = setOf(ContentKind.MANHWA)) }
        val general = listOf("g1", "g2", "g3").map { Provider(it, (1..10).toList()) }
        val agg = aggregator(listOf(alpha) + manhwaSites + general)
        // A Japanese manga (the provider anchor says so).
        val mangaKey = SeriesKey.Provider("alpha", "s-alpha")
        val known = alexandria.core.SeriesState(
            mangaKey.value, alexandria.core.model.SeriesMetadata("alpha", title = "Example Series", kind = ContentKind.MANGA),
            bindings = mapOf("alpha" to alexandria.core.Binding(SourceSeries("alpha", "s-alpha", "Example Series", detailed = true), 1.0, "entry source")),
        )
        agg.update(mangaKey, known, false, true, s)
        assertTrue(general.all { it.searches.get() > 0 }, "general sites first")
        assertTrue(manhwaSites.all { it.searches.get() == 0 })
    }

    // ------------------------------------------------------------ utilities

    @Test
    fun `single flight shares a result and survives a cancelled leader`() = runTest {
        val flight = SingleFlight<String, Int>()
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val leader = launch { flight.run("k") { calls.incrementAndGet(); gate.await(); 1 } }
        testScheduler.runCurrent()
        val follower = async { flight.run("k") { calls.incrementAndGet(); 2 } }
        testScheduler.runCurrent()
        leader.cancel()
        assertEquals(2, follower.await(), "the follower does the work itself")
        val a = async { flight.run("x") { delay(100); calls.incrementAndGet() } }
        val b = async { flight.run("x") { delay(100); calls.incrementAndGet() } }
        assertEquals(a.await(), b.await())
        assertEquals(3, calls.get())
    }

    @Test
    fun `bounded caches evict and expire`() {
        var t = 0L
        val c = BoundedCache<Int, String>(3, ttlMillis = 100) { t }
        (1..5).forEach { c[it] = "v$it" }
        assertEquals(3, c.size)
        assertEquals(null, c[1])
        t = 200
        assertEquals(null, c[5])
    }

    @Test
    fun `cache namespaces keep provider links from being evicted by cover churn`() {
        val store = InMemoryStore()
        var t = 0L
        val cache = CacheManager(store, maxPersistedEntries = 10, clock = { t++ }, groupLimits = mapOf("state:" to 50))
        repeat(20) { cache.put("state:mb:$it", String.serializer(), "links") }
        repeat(200) { cache.put("cover:$it", String.serializer(), "url") }
        val fresh = CacheManager(store, maxPersistedEntries = 10, groupLimits = mapOf("state:" to 50))
        assertTrue((0 until 20).all { fresh.get("state:mb:$it", String.serializer(), Long.MAX_VALUE) == "links" })
        assertTrue(store.keys().count { it.contains("cover:") } <= 10)
    }

    @Test
    fun `chapter url identities stay stable`() = runTest {
        val alpha = Provider("alpha", (1..5).toList())
        val beta = Provider("beta", (1..5).toList())
        val agg = aggregator(listOf(alpha, beta))
        val urls = agg.update(key, null, false, true, s).chapters!!.map { it.url }
        assertEquals((5 downTo 1).map { ChapterUrl.build(key, "en:c:$it") }, urls)
        assertNotNull(agg.update(key, null, false, true, s.copy(deepDiscovery = true)).chapters)
        assertFalse(urls.any { it.contains("beta") })
    }
}
