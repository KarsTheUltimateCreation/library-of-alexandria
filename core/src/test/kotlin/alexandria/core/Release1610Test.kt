package alexandria.core

import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.cache.KeyValueStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.config.SourcePolicy
import alexandria.core.fallback.FallbackInterceptor
import alexandria.core.merge.SearchClusterer
import alexandria.core.metadata.AniListClient
import alexandria.core.metadata.MangaBakaClient
import alexandria.core.metadata.MetadataResolver
import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.model.ExternalIds
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.ranking.ProviderHealth
import alexandria.core.reconciliation.Release
import alexandria.core.sources.ProviderRole
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.SourceRegistry
import alexandria.core.sources.VolumeGroupingAdapter
import alexandria.core.sources.VolumeSeries
import alexandria.core.sources.adapters.Galleries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 1.6.10: one result per work across saved identities, bridges and pages; stable numbered-part ids; related works
 * kept apart; earlier search results and bounded cover work; the 18+ switch enforced everywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Release1610Test {

    /** A provider whose every call is counted. */
    private class Site(
        override val id: String,
        var hits: List<SourceSeries> = emptyList(),
        val searchDelayMs: Long = 0,
        val coverDelayMs: Long = 0,
        override val adult: Boolean = false,
        override val role: ProviderRole = ProviderRole.SERIES,
        override val imageHosts: Set<String> = setOf("img.$id.test"),
        val coverOrigin: CoverOrigin = CoverOrigin.PROVIDER_ORIGINAL,
    ) : SourceAdapter {
        override val name = id
        override val baseUrl = "https://$id.test"
        override val languages: Set<String>? = setOf("en")
        val searches = AtomicInteger()
        val details = AtomicInteger()
        val chapterCalls = AtomicInteger()
        val pageCalls = AtomicInteger()
        val coverCalls = AtomicInteger()
        val lazyCalls = AtomicInteger()
        @Volatile var coverCancelled = false
        fun calls() = searches.get() + details.get() + chapterCalls.get() + pageCalls.get() + coverCalls.get() + lazyCalls.get()

        override suspend fun search(query: String): List<SourceSeries> {
            searches.incrementAndGet()
            delay(searchDelayMs)
            return hits
        }
        override suspend fun details(series: SourceSeries): SourceSeries {
            details.incrementAndGet()
            return (hits.firstOrNull { it.id == series.id } ?: series).copy(detailed = true)
        }
        override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
            chapterCalls.incrementAndGet()
            return (1..3).map { SourceChapter(id, series.id, "$id-${series.id}-$it", "Chapter $it") }
        }
        override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
            pageCalls.incrementAndGet()
            return (0 until 3).map { ReaderPage(it, "https://img.$id.test/${chapter.id}/$it.jpg") }
        }
        override suspend fun covers(series: SourceSeries): List<CoverCandidate> {
            coverCalls.incrementAndGet()
            try {
                delay(coverDelayMs)
            } catch (e: CancellationException) {
                coverCancelled = true
                throw e
            }
            return listOf(CoverCandidate("https://img.$id.test/hq-${series.id}.jpg", id, coverOrigin.name))
        }
        override suspend fun resolveImage(pageUrl: String): ReaderPage {
            lazyCalls.incrementAndGet()
            return ReaderPage(0, pageUrl)
        }
        override fun seriesWebUrl(seriesId: String) = "$baseUrl/$seriesId"
    }

    private fun hit(src: String, id: String, title: String, vararg alt: String, ids: ExternalIds = ExternalIds(), authors: List<String> = listOf("Jane Doe")) =
        SourceSeries(src, id, title, alt.toList(), coverUrl = "https://img.$src.test/$id.jpg", ids = ids, authors = authors, detailed = true)

    /** Every request leaving the engine (metadata services, cover probes). */
    private val requests = CopyOnWriteArrayList<String>()
    private val tripwire = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests += chain.request().url.toString()
        val png = ByteArray(64).also {
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(it)
            it[18] = 0x03; it[19] = 0xE8.toByte(); it[22] = 0x05; it[23] = 0x78
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(png.toResponseBody("image/png".toMediaType())).build()
    }).build()

    private fun metadataRequests() = requests.filter { u -> listOf("mangabaka", "anilist").any { it in u } }

    private val busy = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun aggregator(sites: List<SourceAdapter>, store: KeyValueStore = InMemoryStore(), detached: CoroutineDispatcher = Dispatchers.Unconfined): Aggregator {
        val cache = CacheManager(store)
        val json = Json { ignoreUnknownKeys = true }
        val meta = MetadataResolver(MangaBakaClient(tripwire, json, "t"), AniListClient(tripwire, json, "t"), cache)
        return Aggregator(SourceRegistry(sites, ProviderHealth(store)), meta, cache, tripwire, detachedDispatcher = detached, backlogProbe = { busy[it] ?: 0L })
    }

    private fun TestScope.virtual(sites: List<SourceAdapter>, store: KeyValueStore = InMemoryStore()) = aggregator(sites, store, StandardTestDispatcher(testScheduler))

    private val s = AggregatorSettings(highQualityCovers = false, qualityOptimization = false, requestTimeoutSeconds = 20)

    private suspend fun Aggregator.save(h: SourceSeries, settings: AggregatorSettings) {
        val key = SeriesKey.Provider(h.sourceId, h.id)
        update(key, SeriesState(key.value, bindings = mapOf(h.sourceId to Binding(h, 1.0, "entry source"))), false, false, settings.copy(enabledSources = setOf(h.sourceId)))
    }

    // ------------------------------------------------------------ one result per work

    @Test
    fun `capitalisation variants saved under separate identities show as one result, stably`() = runTest {
        val store = InMemoryStore()
        val a = hit("alpha", "a1", "Attack on Titan", ids = ExternalIds(anilist = 16498), authors = listOf("Hajime Isayama"))
        val b = hit("beta", "b1", "ATTACK ON TITAN", ids = ExternalIds(anilist = 16498), authors = listOf("Hajime Isayama"))
        val alpha = Site("alpha", listOf(a))
        val beta = Site("beta", listOf(b))
        val agg = aggregator(listOf(alpha, beta), store)
        agg.save(a, s)
        agg.save(b, s)

        val cards = agg.search("Attack on Titan", s)
        assertEquals(1, cards.size, cards.map { "${it.title} <${it.key}>" }.toString())
        val shown = cards.single().key.value
        assertEquals("p:alpha:a1", shown, "deterministic: neither is a library entry, the fixed provider order decides")

        // A restart (fresh engine, same store): the same single identity.
        val again = aggregator(listOf(Site("alpha", listOf(a)), Site("beta", listOf(b))), store).search("Attack on Titan", s)
        assertEquals(listOf(shown), again.map { it.key.value })

        // The other identity still opens as itself (old URLs, library entries, chapter links keep working).
        val old = agg.update(SeriesKey.Provider("beta", "b1"), null, false, true, s)
        assertEquals("b1", old.state.bindings.getValue("beta").series.id)
        assertTrue(old.chapters!!.all { it.url.startsWith("p:beta:b1|") })

        // Opening the shown identity takes over the other's link as a hint and verifies it before trusting it.
        val merged = agg.update(SeriesKey.Provider("alpha", "a1"), null, true, true, s)
        val link = merged.state.bindings["beta"]!!
        assertFalse(link.unverified, "verified against details")
        assertEquals("b1", link.series.id)
        assertEquals(setOf("alpha", "beta"), merged.chapters!!.flatMap { c -> c.releases.map { it.sourceId } }.toSet())
    }

    @Test
    fun `the identity of a library entry is the one shown`() = runTest {
        val a = hit("alpha", "a1", "Attack on Titan", ids = ExternalIds(anilist = 16498))
        val b = hit("beta", "b1", "ATTACK ON TITAN", ids = ExternalIds(anilist = 16498))
        val agg = aggregator(listOf(Site("alpha", listOf(a)), Site("beta", listOf(b))))
        agg.save(a, s)
        agg.save(b, s)
        // The host's library update of the beta entry (chapters only, with the entry's memo).
        val key = SeriesKey.Provider("beta", "b1")
        val memo = agg.update(key, null, false, true, s).state
        agg.update(key, memo, false, true, s)
        assertEquals(listOf("p:beta:b1"), agg.search("Attack on Titan", s).map { it.key.value })
    }

    @Test
    fun `saved identities with conflicting ids or only a shared title stay apart`() = runTest {
        val a = hit("alpha", "a1", "Monster", ids = ExternalIds(anilist = 1))
        val b = hit("beta", "b1", "MONSTER", ids = ExternalIds(anilist = 2))
        val agg = aggregator(listOf(Site("alpha", listOf(a)), Site("beta", listOf(b))))
        agg.save(a, s)
        agg.save(b, s)
        assertEquals(2, agg.search("Monster", s).size, "different AniList ids are different works")

        // Same title, nothing else in common: never enough to merge two saved identities.
        val c = hit("alpha", "c1", "Kingdom", authors = emptyList())
        val d = hit("beta", "d1", "KINGDOM", authors = emptyList())
        val agg2 = aggregator(listOf(Site("alpha", listOf(c)), Site("beta", listOf(d))))
        agg2.save(c, s)
        agg2.save(d, s)
        assertEquals(2, agg2.search("Kingdom", s).size)
    }

    private fun cluster(hits: List<SourceSeries>, order: List<String> = listOf("alpha", "beta", "gamma")) =
        SearchClusterer.cluster(hits, { null }, { _, _ -> false }, { order.indexOf(it) }, { true })

    @Test
    fun `a third result bridging two groups joins them`() {
        val a = hit("alpha", "a1", "Sunrise", ids = ExternalIds(anilist = 1))
        val b = hit("beta", "b1", "Daybreak", ids = ExternalIds(mal = 2))
        val c = hit("gamma", "c1", "Sunrise", "Daybreak", ids = ExternalIds(anilist = 1, mal = 2))
        assertEquals(listOf(setOf("alpha", "beta", "gamma")), cluster(listOf(a, b, c)).map { g -> g.members.map { it.sourceId }.toSet() })

        // A bridge that contradicts one side (another MAL id) does not join it.
        val conflicting = c.copy(ids = ExternalIds(anilist = 1, mal = 3))
        assertEquals(2, cluster(listOf(a, b, conflicting)).size)
        // Two groups holding entries of one site are two entries there, not one work.
        val twin = hit("alpha", "a2", "Daybreak", ids = ExternalIds(mal = 2))
        assertEquals(2, cluster(listOf(a, twin, c)).size)
    }

    @Test
    fun `an edition never groups with the main work through its plain alias`() {
        val plain = hit("alpha", "a1", "Example Story")
        val book = hit("beta", "b1", "Example Story (Book Version)", "Example Story")
        val colored = hit("gamma", "c1", "Example Story (Colored)", "Example Story")
        assertEquals(3, cluster(listOf(plain, book, colored)).size)
        // Capitalisation alone never separates copies.
        assertEquals(1, cluster(listOf(plain, hit("beta", "b2", "EXAMPLE STORY"))).size)
    }

    @Test
    fun `page two never shows a work again and a repeated page one keeps every card`() = runTest {
        val alpha = Site("alpha", listOf(hit("alpha", "a1", "Example Story"), hit("alpha", "a2", "Example Story Origins")))
        val beta = Site("beta", listOf(hit("beta", "b1", "Example Story"), hit("beta", "x1", "Example Story Extra"), hit("beta", "x2", "Example Story Beyond")))
        val empty = listOf("gamma", "delta", "epsilon", "zeta").map { Site(it) }
        busy["alpha"] = 4_000 // a library update is using it: left for page 2
        val agg = aggregator(listOf(alpha, beta) + empty)

        val (first, more) = agg.searchPage("Example Story", 1, s)
        assertTrue(more)
        assertEquals(listOf("p:beta:b1", "p:beta:x1", "p:beta:x2"), first.map { it.key.value })
        busy.clear()
        val (second, more2) = agg.searchPage("Example Story", 2, s)
        assertFalse(more2)
        assertEquals(listOf("p:alpha:a2"), second.map { it.key.value }, "alpha's copy of Example Story joins the card already shown")

        val (repeat, more3) = agg.searchPage("Example Story", 1, s)
        assertEquals(listOf("p:beta:b1", "p:beta:x1", "p:beta:x2", "p:alpha:a2"), repeat.map { it.key.value })
        assertFalse(more3)
        // The card shown first gained alpha as a hint (opening it links alpha without searching again).
        val opened = agg.update(SeriesKey.Provider("beta", "b1"), null, true, true, s)
        assertTrue("alpha" in opened.state.bindings, opened.state.bindings.keys.toString())
        assertEquals(1, alpha.searches.get())
    }

    @Test
    fun `copies differing only in case show the normal-case title`() = runTest {
        val atsu = Site("atsumaru", listOf(hit("atsumaru", "d6", "ATTACK ON TITAN")))
        val weeb = Site("weebcentral", listOf(hit("weebcentral", "w1", "Attack on Titan")))
        val cards = aggregator(listOf(atsu, weeb)).search("attack on titan", s)
        assertEquals(listOf("Attack on Titan"), cards.map { it.title })
    }

    @Test
    fun `identical searches at the same time share one session`() = runTest {
        val alpha = Site("alpha", listOf(hit("alpha", "a1", "Example Story")), searchDelayMs = 300)
        val agg = virtual(listOf(alpha, Site("beta")))
        val results = (1..3).map { async { agg.search("Example Story", s) } }.awaitAll()
        assertEquals(1, results.map { r -> r.map { it.key.value } }.distinct().size)
        assertEquals(1, alpha.searches.get())
    }

    // ------------------------------------------------------------ numbered parts

    @Test
    fun `a numbered-part series keeps its id when new parts name it differently, across restarts`() = runTest {
        val parts12 = (1..2).map { n -> hit("hitomi", "p$n", "Bright Dawn $n", "Akatsuki $n") }
        val part3 = hit("hitomi", "p3", "Akatsuki 3")
        val inner = Site("hitomi", parts12, role = ProviderRole.GALLERY, adult = true)
        val store = InMemoryStore()
        val grouping = VolumeGroupingAdapter(inner)
        aggregator(listOf(grouping), store) // attaches the persisted identities
        assertEquals("vol~bright-dawn", grouping.search("bright dawn").single().id)
        inner.hits = parts12 + part3
        assertEquals("vol~bright-dawn", grouping.search("bright dawn").single().id, "part 3 alone names it Akatsuki")

        // After a restart, a search that only finds the Akatsuki-named parts still yields the first id.
        val inner2 = Site("hitomi", (1..3).map { n -> hit("hitomi", "p$n", "Akatsuki $n") }, role = ProviderRole.GALLERY, adult = true)
        val grouping2 = VolumeGroupingAdapter(inner2)
        aggregator(listOf(grouping2), store)
        assertEquals("vol~bright-dawn", grouping2.search("akatsuki").single().id)

        // Without any history the id is still derived from the title, as before.
        assertEquals("vol~akatsuki", VolumeSeries.group(inner2.hits) { true }.second.single().series.id)
    }

    // ------------------------------------------------------------ related works

    @Test
    fun `a parody's reference to its original series is searchable but never its identity`() = runTest {
        val raw = "Yamada/Tayama (Smoking Behind the Supermarket with You) [English]"
        assertEquals(listOf("Yamada/Tayama"), Galleries.titles(raw))
        assertEquals(listOf(raw, "Smoking Behind the Supermarket with You"), Galleries.references(listOf(raw), listOf("Smoking Behind the Supermarket with You")))

        val series = hit("alpha", "s1", "Smoking Behind the Supermarket with You", authors = listOf("Jinushi"))
        val gallery = SourceSeries("gal", "g1", "Yamada/Tayama", related = Galleries.references(listOf(raw)), coverUrl = "https://img.gal.test/g1.jpg")
        assertFalse(SearchClusterer.sameTitle(series, gallery))
        val agg = aggregator(listOf(Site("alpha", listOf(series)), Site("gal", listOf(gallery), role = ProviderRole.GALLERY, adult = true)))
        val (first, more) = agg.searchPage("Smoking Behind the Supermarket with You", 1, s)
        assertEquals(listOf("p:alpha:s1"), first.map { it.key.value }, "an exact match answers the first page")
        assertTrue(more)
        val (second, _) = agg.searchPage("Smoking Behind the Supermarket with You", 2, s)
        assertEquals(listOf("p:gal:g1"), second.map { it.key.value }, "the related gallery is its own result")
    }

    // ------------------------------------------------------------ loading time

    @Test
    fun `an exact match is shown without waiting for slow providers, which join on page two`() = runTest {
        val alpha = Site("alpha", listOf(hit("alpha", "a1", "Example Story")))
        val slow = listOf("beta", "gamma", "delta", "epsilon", "zeta").map { id ->
            Site(id, listOf(hit(id, "${id}1", "Example Story"), hit(id, "${id}x", "Example Story ${id.uppercase()} Side")), searchDelayMs = 4_000)
        }
        val agg = virtual(listOf(alpha) + slow)
        val start = currentTime
        val (first, more) = agg.searchPage("Example Story", 1, s)
        val elapsed = currentTime - start
        assertTrue(elapsed <= 2_000, "first page after $elapsed ms")
        assertEquals(listOf("p:alpha:a1"), first.map { it.key.value })
        assertTrue(more, "the providers not waited for are still to come")

        val (second, _) = agg.searchPage("Example Story", 2, s)
        assertTrue(second.none { it.title == "Example Story" }, "the late copies join the card already shown: " + second.map { it.title })
        assertEquals(5, second.size, second.map { it.title }.toString())
        assertEquals(1, slow.sumOf { it.searches.get() } / slow.size, "page two reused the searches already running")
    }

    @Test
    fun `a fast first page still links the sources a wider search finds, extras included`() = runTest {
        val general = (1..5).map { Site("g$it", if (it == 1) listOf(hit("g1", "x1", "Example Story")) else emptyList()) }
        // Not on the first tier (the sixth general site); carries the work with more entries (extras, side stories).
        fun extras(base: Site) = object : SourceAdapter by base {
            override suspend fun chapters(series: SourceSeries, languages: List<String>) =
                (1..3).map { SourceChapter(id, series.id, "$id-$it", "Chapter $it") } + SourceChapter(id, series.id, "$id-extra", "Chapter 3.5 Extra")
        }
        val wide = extras(Site("wide", listOf(hit("wide", "w1", "Example Story")), searchDelayMs = 2_500))
        // An adult-flagged series site (Hiperdex-like) answering late: not waited for, linked on the next refresh.
        val late = Site("late", listOf(hit("late", "l1", "Example Story")), searchDelayMs = 5_000, adult = true)
        val agg = virtual(general + wide + late)
        val start = currentTime
        val card = agg.search("Example Story", s).single()
        assertTrue(currentTime - start <= 2_000, "page after ${currentTime - start} ms")
        // Opened at once: waits (bounded) for the background search instead of linking fewer sources.
        val opened = agg.update(card.key, null, true, true, s)
        assertTrue("wide" in opened.state.bindings && !opened.state.bindings.getValue("wide").unverified, opened.state.bindings.keys.toString())
        assertEquals(4, opened.chapters!!.size, opened.chapters!!.map { it.name }.toString())
        assertFalse("late" in opened.state.bindings, "the open did not wait for it")

        advanceTimeBy(10_000)
        runCurrent()
        val refreshed = agg.update(card.key, opened.state, false, true, s) // the next library update, with the host's memo
        assertTrue(refreshed.state.bindings["late"]?.unverified == false, refreshed.state.bindings.keys.toString())
    }

    @Test
    fun `a search that finds nothing confident still widens and rescues`() = runTest {
        val general = (1..5).map { Site("g$it", listOf(hit("g$it", "x$it", "Unrelated Thing $it"))) }
        val wide = Site("wide", listOf(hit("wide", "w1", "Rare Title")), adult = true) // not on the first tier
        val agg = virtual(general + wide)
        assertEquals(listOf("p:wide:w1"), agg.search("Rare Title", s).map { it.key.value })

        val rescue = Site("late", listOf(hit("late", "l1", "Very Rare Title")), searchDelayMs = 9_000)
        val agg2 = virtual(listOf(Site("e1"), rescue))
        assertEquals(listOf("p:late:l1"), agg2.search("Very Rare Title", s).map { it.key.value }, "nothing at all: every provider gets more time")
    }

    @Test
    fun `a slow optional cover does not hold up the update and is used on the next refresh`() = runTest {
        val alpha = Site("alpha", listOf(hit("alpha", "a1", "Example Story")), coverDelayMs = 7_000)
        val agg = virtual(listOf(alpha))
        val key = SeriesKey.Provider("alpha", "a1")
        val state = SeriesState(key.value, bindings = mapOf("alpha" to Binding(alpha.hits.single(), 1.0, "entry source")))
        val start = currentTime
        val first = agg.update(key, state, true, true, s)
        assertTrue(currentTime - start <= 2_600, "update took ${currentTime - start} ms")
        assertEquals(3, first.chapters!!.size)
        assertEquals("https://img.alpha.test/a1.jpg", first.details.coverUrl, "the cover already known")

        advanceTimeBy(10_000)
        runCurrent()
        val start2 = currentTime
        val second = agg.update(key, null, true, false, s)
        assertTrue(currentTime - start2 < 100)
        assertEquals("https://img.alpha.test/hq-a1.jpg", second.details.coverUrl, "the finished comparison, cached")
        assertEquals(1, alpha.coverCalls.get())
        assertTrue(agg.lastTimings().keys.containsAll(listOf("update.linking", "update.chapters", "update.cover")), agg.lastTimings().toString())
    }

    @Test
    fun `leaving the series cancels its cover work`() = runTest {
        val alpha = Site("alpha", listOf(hit("alpha", "a1", "Example Story")), coverDelayMs = 7_000)
        val agg = virtual(listOf(alpha))
        val key = SeriesKey.Provider("alpha", "a1")
        val job = launch { agg.update(key, SeriesState(key.value, bindings = mapOf("alpha" to Binding(alpha.hits.single(), 1.0, "entry source"))), true, true, s) }
        advanceTimeBy(1_000)
        job.cancel()
        advanceUntilIdle()
        assertTrue(alpha.coverCancelled)
    }

    // ------------------------------------------------------------ the 18+ switch

    private val explicit = listOf("hentai20", "hentai2read", "nhentai", "hitomi", "ehentai", "pururin")

    @Test
    fun `the switch covers exactly six providers`() {
        assertEquals(explicit.toSet(), SourcePolicy.EXPLICIT_SOURCES)
        listOf("hiperdex", "omegascans", "manhwaread", "loa_mangaball", "weebcentral").forEach { assertFalse(it in SourcePolicy.EXPLICIT_SOURCES, it) }
    }

    @Test
    fun `switching 18+ off, on and off keeps every saved source choice`() {
        val all = listOf("alpha", "hentai20", "hiperdex", "nhentai", "pururin")
        // Never configured: all, minus the six while off.
        assertEquals(setOf("alpha", "hiperdex"), SourcePolicy.effective(SourcePolicy.selected(null, null, all), all, SourcePolicy.hidden(false)))
        assertEquals(emptySet(), SourcePolicy.effective(SourcePolicy.selected(null, null, all), all, SourcePolicy.hidden(true)), "empty = all")

        // The user unticked nhentai while 18+ was on.
        var stored = setOf("alpha", "hentai20", "hiperdex", "pururin")
        val known = all.toSet()
        fun effective(adult: Boolean) = SourcePolicy.effective(SourcePolicy.selected(stored, known, all), all, SourcePolicy.hidden(adult))
        assertEquals(setOf("alpha", "hiperdex"), effective(false))
        // Off: the list shows alpha and hiperdex only; the user unticks hiperdex.
        val listed = all.filter { it !in SourcePolicy.EXPLICIT_SOURCES }
        stored = SourcePolicy.saveEdit(setOf("alpha"), SourcePolicy.selected(stored, known, all), all, listed)
        assertEquals(setOf("alpha", "hentai20", "pururin"), stored, "the hidden choices are kept as they were")
        assertEquals(setOf("alpha", "hentai20", "pururin"), effective(true))
        assertEquals(setOf("alpha"), effective(false))
        // Everything unticked stays "none", whatever the switch.
        stored = SourcePolicy.saveEdit(emptySet(), SourcePolicy.selected(setOf("alpha"), known, all), all, listed)
        assertEquals(setOf(SourcePolicy.NONE), effective(false))

        assertNull(SourcePolicy.preferred("hentai20", SourcePolicy.hidden(false)))
        assertEquals("hentai20", SourcePolicy.preferred("hentai20", SourcePolicy.hidden(true)), "the choice itself is kept")
        assertEquals("hiperdex", SourcePolicy.preferred("hiperdex", SourcePolicy.hidden(false)))
        assertNull(SourcePolicy.preferred("auto", emptySet()))
    }

    private fun policySites(): Map<String, Site> {
        val q = "Obscure Query"
        val sites = listOf(
            Site("alpha"), // the general provider finds nothing: every widening path runs
            Site("loa_mangaball", listOf(hit("loa_mangaball", "m1", "$q Mixed"))),
            Site("hiperdex", listOf(hit("hiperdex", "h1", q)), adult = true),
            Site("omegascans", listOf(hit("omegascans", "o1", q)), adult = true),
            Site("manhwaread", listOf(hit("manhwaread", "r1", q)), adult = true),
        ) + explicit.map { id ->
            val gallery = id in setOf("nhentai", "hitomi", "ehentai", "pururin")
            Site(id, listOf(hit(id, "${id}1", q)), adult = true, role = if (gallery) ProviderRole.GALLERY else ProviderRole.SERIES)
        }
        return sites.associateBy { it.id }
    }

    @Test
    fun `with 18+ off the six providers are never contacted, the others still are`() = runTest {
        val sites = policySites()
        val all = sites.keys.toList()
        val off = s.copy(
            enabledSources = SourcePolicy.effective(emptySet(), all, SourcePolicy.hidden(false)),
            sourceFallback = true,
            // A manual link and a preferred source naming hidden providers.
            manualLinks = mapOf("p:hiperdex:h1" to listOf("hentai20:hentai201", "nhentai:nhentai1")),
        )
        val agg = aggregator(sites.values.toList())
        val q = "Obscure Query"
        agg.searchPage(q, 1, off)
        agg.searchPage(q, 2, off)
        agg.search("Nothing Anywhere", off) // rescue path

        // A series saved while 18+ was on: links, spares and hints on the hidden providers.
        val key = SeriesKey.Provider("hiperdex", "h1")
        val bind = { id: String -> Binding(sites.getValue(id).hits.single(), 0.95, "earlier") }
        val saved = SeriesState(
            key.value,
            bindings = mapOf("hiperdex" to bind("hiperdex"), "hentai2read" to bind("hentai2read"), "ehentai" to bind("ehentai").copy(hint = true)),
            spares = mapOf("pururin" to bind("pururin")),
        )
        val update = agg.update(key, saved, true, true, off.copy(deepDiscovery = true))
        assertTrue(update.chapters!!.isNotEmpty())
        assertTrue(update.state.bindings.containsKey("hentai2read"), "the saved link itself is kept for when 18+ is on again")
        assertTrue(update.state.bindings["hentai20"]?.manual == true, "the manual link is kept, unused")

        // Reading: releases of hidden providers in the host's memo, a prepared fallback, lazy pages.
        val chapter = update.chapters!!.first()
        val memo = explicit.map { Release(it, "${it}1", "$it-c1", "Chapter 1", "en") }
        val res = agg.pages(chapter.url, memo + chapter.releases, off)
        assertFalse(res.chosen.sourceId in explicit)
        assertTrue(res.fallbacks.none { it.sourceId in explicit })
        explicit.forEach { assertNull(agg.reader.alternatePages(chapter.url, Release(it, "${it}1", "$it-c1", "Chapter 1", "en"), off)) }
        assertFailsWith<IllegalStateException> { agg.resolveImage(FallbackInterceptor.tagPage("https://img.ehentai.test/v/1", chapter.url, 0, "ehentai"), off) }
        assertFalse(agg.allowsImage("https://img.hentai20.test/c.jpg", "hentai20", off))
        assertFalse(agg.allowsImage("https://img.hitomi.test/c.jpg", null, off), "by image host when the provider is not named")
        assertTrue(agg.allowsImage("https://img.hiperdex.test/c.jpg", null, off))

        explicit.forEach { assertEquals(0, sites.getValue(it).calls(), "$it was contacted") }
        listOf("hiperdex", "omegascans", "manhwaread", "loa_mangaball").forEach { assertTrue(sites.getValue(it).searches.get() > 0, "$it stays available") }
        assertTrue(metadataRequests().isEmpty(), metadataRequests().toString())

        // Switched on: the same search reaches them.
        val on = off.copy(enabledSources = SourcePolicy.effective(emptySet(), all, SourcePolicy.hidden(true)), manualLinks = emptyMap())
        agg.searchPage(q, 1, on)
        agg.searchPage(q, 2, on)
        assertTrue(explicit.any { sites.getValue(it).searches.get() > 0 })
    }

    @Test
    // Real time: cover probes run on OkHttp's threads, which a virtual clock would not wait for.
    fun `a cover cached while a provider was enabled is not shown once it is disabled`() = runBlocking {
        val alpha = Site("alpha", listOf(hit("alpha", "a1", "Example Story")), coverOrigin = CoverOrigin.PROVIDER_THUMBNAIL)
        val h20 = Site("hentai20", listOf(hit("hentai20", "h1", "Example Story")), adult = true)
        val store = InMemoryStore()
        val agg = aggregator(listOf(alpha, h20), store)
        val key = SeriesKey.Provider("alpha", "a1")
        val state = SeriesState(key.value, bindings = mapOf("hentai20" to Binding(h20.hits.single(), 0.95, "earlier"), "alpha" to Binding(alpha.hits.single(), 1.0, "entry source")))
        val on = agg.update(key, state, true, false, s.copy(highQualityCovers = true))
        assertTrue(on.details.coverUrl!!.contains("hentai20"), on.details.coverUrl)

        val off = s.copy(highQualityCovers = true, enabledSources = setOf("alpha"))
        val after = agg.update(key, null, true, false, off)
        assertFalse(after.details.coverUrl!!.contains("hentai20"), after.details.coverUrl)
        assertTrue(FallbackInterceptor.decodeCoverToken(after.details.coverUrl!!.substringAfter("#loa-c=", "")).none { "hentai20" in it.u || it.p == "hentai20" })
        // Search cards: the best cover remembered from the earlier opening is not used either.
        assertTrue(agg.search("Example Story", off).none { it.coverUrl.orEmpty().contains("hentai20") })

        // Entries written by 1.6.9: a v3 ranking and a plain best-cover URL of the hidden provider.
        val cache = CacheManager(store)
        val v3 = "cover:v3:${key.value}:false:AUTO:true:false"
        cache.put(v3, ListSerializer(CoverCandidate.serializer()), listOf(CoverCandidate("https://img.hentai20.test/cached.jpg", "hentai20", CoverOrigin.PROVIDER_THUMBNAIL.name)))
        cache.put("cover:best:${key.value}", String.serializer(), "https://img.hentai20.test/cached.jpg")
        // A current-format ranking that predates the switch, under the alpha-only key.
        val v4 = "cover:v4:${key.value}:false:AUTO:true:false:" + Integer.toHexString(listOf("alpha").hashCode())
        cache.put(v4, ListSerializer(CoverCandidate.serializer()), listOf(CoverCandidate("https://img.hentai20.test/cached.jpg", "hentai20", CoverOrigin.PROVIDER_THUMBNAIL.name)))
        val fresh = aggregator(listOf(Site("alpha", alpha.hits), Site("hentai20", h20.hits, adult = true)), store)
        val legacy = fresh.update(key, null, true, false, s.copy(enabledSources = setOf("alpha")))
        assertFalse(legacy.details.coverUrl.orEmpty().contains("hentai20"), legacy.details.coverUrl)
        assertTrue(fresh.search("Example Story", s.copy(enabledSources = setOf("alpha"))).none { it.coverUrl.orEmpty().contains("hentai20") })
    }

    @Test
    fun `prepared image fallbacks follow the current policy`() {
        MockWebServer().use { server ->
            server.start()
            class Paged(override val id: String) : SourceAdapter {
                val reads = AtomicInteger()
                override val name = id
                override val baseUrl = "https://$id.test"
                override val languages: Set<String>? = setOf("en")
                override suspend fun search(query: String) = emptyList<SourceSeries>()
                override suspend fun chapters(series: SourceSeries, languages: List<String>) = emptyList<SourceChapter>()
                override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean): List<ReaderPage> {
                    reads.incrementAndGet()
                    return (0 until 3).map { ReaderPage(it, server.url("/$id/$it.jpg").toString()) }
                }
                override fun seriesWebUrl(seriesId: String) = baseUrl
            }
            val alpha = Paged("alpha")
            val h20 = Paged("hentai20")
            val agg = aggregator(listOf(alpha, h20))
            var current = s.copy(sourceOrder = listOf("hentai20", "alpha"))
            val ref = "p:alpha:a1|en:c:1"
            val releases = listOf(Release("hentai20", "h1", "h-c1", "Chapter 1", "en"), Release("alpha", "a1", "a-c1", "Chapter 1", "en"))
            val res = runBlocking { agg.reader.resolve(ref, releases, current) }
            assertEquals("hentai20", res.chosen.sourceId)
            val altAlpha = runBlocking { agg.reader.alternatePages(ref, releases[1], current) }
            assertEquals(3, altAlpha?.size)
            // Cached alternative of the provider about to be hidden.
            val cachedH20 = runBlocking { agg.reader.alternatePages(ref, releases[0], current) }
            assertEquals(3, cachedH20?.size)
            val h20Reads = h20.reads.get()

            current = current.copy(enabledSources = setOf("alpha")) // 18+ switched off while reading
            assertNull(runBlocking { agg.reader.alternatePages(ref, releases[0], current) }, "not even from the cache")
            assertEquals(h20Reads, h20.reads.get())

            val client = OkHttpClient.Builder().addInterceptor(FallbackInterceptor({ agg.reader }, { current }) { url, p -> agg.allowsImage(url, p, current) }).build()
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body("ALPHA-1").build())
            val page = FallbackInterceptor.tagPage(res.pages[1].imageUrl, ref, 1, "hentai20")
            assertEquals("ALPHA-1", client.newCall(Request.Builder().url(page).build()).execute().use { it.body.string() })
            assertEquals("/alpha/1.jpg", server.takeRequest().target, "the hidden provider's page was not requested")
            assertEquals(1, server.requestCount)

            // Cover fallback: an alternative of the hidden provider is skipped.
            server.enqueue(MockResponse.Builder().code(500).build())
            server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body("ALPHA-COVER").build())
            val cover = FallbackInterceptor.tagCover(
                server.url("/alpha/cover-broken.jpg").toString(),
                listOf(FallbackInterceptor.CoverAlt(server.url("/hentai20/cover.jpg").toString(), p = "hentai20"), FallbackInterceptor.CoverAlt(server.url("/alpha/cover.jpg").toString(), p = "alpha")),
            )
            assertEquals("ALPHA-COVER", client.newCall(Request.Builder().url(cover).build()).execute().use { it.body.string() })
            assertEquals("/alpha/cover-broken.jpg", server.takeRequest().target)
            assertEquals("/alpha/cover.jpg", server.takeRequest().target)
        }
    }

    @Test
    // Real time: cover and page probes run on OkHttp's threads.
    fun `provider-only search, pages, covers and identities make no metadata request`() = runBlocking {
        val a = hit("alpha", "a1", "Attack on Titan", ids = ExternalIds(anilist = 16498))
        val b = hit("beta", "b1", "ATTACK ON TITAN", ids = ExternalIds(anilist = 16498))
        val agg = aggregator(listOf(Site("alpha", listOf(a)), Site("beta", listOf(b))))
        val hq = s.copy(highQualityCovers = true, qualityOptimization = true)
        agg.save(a, hq)
        agg.save(b, hq)
        agg.searchPage("Attack on Titan", 1, hq)
        agg.searchPage("Attack on Titan", 2, hq)
        val u = agg.update(SeriesKey.Provider("alpha", "a1"), null, true, true, hq)
        agg.pages(u.chapters!!.first().url, null, hq)
        assertNotEquals(0, requests.size, "covers and pages were probed through the probe client")
        assertTrue(metadataRequests().isEmpty(), metadataRequests().toString())
    }
}
