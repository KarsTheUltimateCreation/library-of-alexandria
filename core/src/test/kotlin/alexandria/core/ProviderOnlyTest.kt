package alexandria.core

import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.cache.KeyValueStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.merge.DetailsMerger
import alexandria.core.merge.LocalFilters
import alexandria.core.metadata.AniListClient
import alexandria.core.metadata.CatalogFilters
import alexandria.core.metadata.MangaBakaClient
import alexandria.core.metadata.MetadataResolver
import alexandria.core.model.ContentKind
import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.model.ExternalIds
import alexandria.core.model.PublicationStatus
import alexandria.core.model.ReaderPage
import alexandria.core.model.SeriesMetadata
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.HttpError
import alexandria.core.ranking.ProviderHealth
import alexandria.core.sources.ProviderRole
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A provider carrying any number of works; every call is counted. */
private class Site(
    override val id: String,
    val works: MutableList<Work>,
    override val role: ProviderRole = ProviderRole.SERIES,
    override val adult: Boolean = false,
) : SourceAdapter {
    data class Work(
        val id: String,
        val title: String,
        val authors: List<String> = emptyList(),
        val year: Int? = null,
        val chapters: List<Int> = (1..10).toList(),
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val status: PublicationStatus = PublicationStatus.UNKNOWN,
        val kind: ContentKind = ContentKind.UNKNOWN,
        val alt: List<String> = emptyList(),
    )

    override val name = id.replaceFirstChar { it.uppercase() }
    override val baseUrl = "https://$id.test"
    override val languages: Set<String>? = setOf("en")
    val searches = AtomicInteger()
    val details = AtomicInteger()
    val chapterCalls = AtomicInteger()
    var failChapters = false
    var failSearch = false

    private fun Work.hit() = SourceSeries(this@Site.id, id, title, coverUrl = "https://img.${this@Site.id}.test/$id.jpg", kind = kind, status = status)
    private fun Work.full() = hit().copy(altTitles = alt, authors = authors, year = year, description = description, genres = genres, detailed = true)

    override suspend fun search(query: String): List<SourceSeries> {
        searches.incrementAndGet()
        if (failSearch) throw HttpError(503, "$baseUrl/search")
        val words = query.lowercase().split(' ')
        return works.filter { w -> words.all { it in w.title.lowercase() } }.map { it.hit() }
    }

    override suspend fun details(series: SourceSeries): SourceSeries {
        details.incrementAndGet()
        return works.firstOrNull { it.id == series.id }?.full() ?: series
    }

    override suspend fun chapters(series: SourceSeries, languages: List<String>): List<SourceChapter> {
        chapterCalls.incrementAndGet()
        if (failChapters) throw HttpError(503, "$baseUrl/chapters")
        val w = works.first { it.id == series.id }
        return w.chapters.map { SourceChapter(id, w.id, "$id-${w.id}-c$it", "Chapter $it") }
    }

    override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean) = (0 until 5).map { ReaderPage(it, "https://img.$id.test/${chapter.id}/$it.jpg") }
    override suspend fun popular(page: Int) = works.map { it.hit() }
    override suspend fun latest(page: Int) = works.map { it.hit() }
    override fun seriesWebUrl(seriesId: String) = "$baseUrl/$seriesId"
}

class ProviderOnlyTest {
    private var now = 10_000_000L
    private val clock = { now }

    /** Every request that leaves the engine: metadata clients and the image/cover probe client. */
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

    /** Provider-only defaults (what a fresh install uses), with deterministic fast settings. */
    private val s = AggregatorSettings(requestTimeoutSeconds = 5)

    /** Simulated rate-limit queue per provider (ms). */
    private val busy = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun aggregator(sites: List<SourceAdapter>, store: KeyValueStore = InMemoryStore()): Aggregator {
        val cache = CacheManager(store, clock = clock)
        val json = Json { ignoreUnknownKeys = true }
        val meta = MetadataResolver(MangaBakaClient(tripwire, json, "t"), AniListClient(tripwire, json, "t"), cache)
        return Aggregator(
            SourceRegistry(sites, ProviderHealth(store, clock)), meta, cache, tripwire, clock = clock,
            listCache = CacheManager(store, clock = clock), detachedDispatcher = Dispatchers.Unconfined,
            backlogProbe = { busy[it] ?: 0L },
        )
    }

    private fun work(id: String, title: String = "Example Series", chapters: List<Int> = (1..10).toList(), author: String? = "Jane Doe", year: Int? = 2019, description: String? = null) =
        Site.Work(id, title, listOfNotNull(author), year, chapters, description)

    // ------------------------------------------------------------ zero metadata requests

    @Test
    fun `default settings are provider-only`() {
        assertFalse(AggregatorSettings().externalMetadata)
        assertFalse(AggregatorSettings().mangabaka)
        assertFalse(AggregatorSettings().anilist)
    }

    @Test
    fun `provider-only workflows make no metadata service request`() = runTest {
        val a = Site("alpha", mutableListOf(work("x", description = "A long enough description of the example series for everyone to read.")))
        val b = Site("beta", mutableListOf(work("y", chapters = (5..15).toList())))
        val agg = aggregator(listOf(a, b))
        val settings = s.copy(qualityOptimization = true) // probes covers and pages: the probe client must not hit metadata either
        val cards = agg.search("example", settings)
        agg.searchPage("example", 2, settings)
        agg.popular(1, settings)
        agg.latest(1, settings)
        agg.browse("", CatalogFilters(statuses = listOf("releasing")), 1, settings)
        agg.browse("example", CatalogFilters(genres = listOf("action")), 1, settings)
        val key = cards.single().key
        val opened = agg.update(key, null, fetchDetails = true, fetchChapters = true, settings = settings)
        agg.update(key, opened.state, fetchDetails = false, fetchChapters = true, settings = settings) // library update
        now += CacheManager.DAY
        agg.update(key, opened.state, fetchDetails = false, fetchChapters = true, settings = settings)
        agg.pages(opened.chapters!!.first().url, null, settings)

        // A library entry from 1.6.x keyed by MangaBaka, whose stored record carries MangaBaka artwork.
        val legacyKey = SeriesKey.MangaBaka(3397)
        val legacy = SeriesState(
            legacyKey.value,
            SeriesMetadata("mangabaka", ExternalIds(mangabaka = 3397), "Example Series", description = "From MangaBaka: " + "a substantial stored synopsis. ".repeat(4),
                covers = listOf(CoverCandidate("https://images.mangabaka.dev/raw/3397.jpg", "mangabaka", CoverOrigin.METADATA_ORIGINAL.name, 1500, 2250))),
            bindings = mapOf("alpha" to Binding(SourceSeries("alpha", "x", "Example Series", authors = listOf("Jane Doe"), detailed = true), 1.0, "id")),
        )
        val legacyUpdate = agg.update(legacyKey, legacy, fetchDetails = true, fetchChapters = true, settings = settings, titleHint = "Example Series")
        agg.update(SeriesKey.AniList(105398), null, fetchDetails = true, fetchChapters = true, settings = settings, titleHint = "Example Series")
        agg.search("mb:3397", settings)

        assertEquals(emptyList(), metadataRequests(), "requests: $requests")
        assertFalse(legacyUpdate.details.coverUrl.orEmpty().contains("mangabaka"), "no MangaBaka artwork: ${legacyUpdate.details.coverUrl}")
        assertTrue(legacyUpdate.details.description!!.contains("From MangaBaka:"), "stored information is kept")
        assertTrue(legacyUpdate.details.description!!.contains("MangaBaka (CC BY-NC-SA 4.0)"), "and attributed")
        assertEquals("x", legacyUpdate.state.bindings["alpha"]?.series?.id, "the legacy entry keeps its stored link")
        assertEquals(15, legacyUpdate.chapters!!.size, "and fills from a source found without any metadata lookup")
        assertNull(agg.webUrl(legacyKey, settings)?.takeIf { "mangabaka" in it }, "web link points at a provider")
    }

    // ------------------------------------------------------------ identity

    @Test
    fun `copies corroborated by author or year become one series with every chapter`() = runTest {
        val a = Site("alpha", mutableListOf(work("x", chapters = (1..10).toList())))
        val b = Site("beta", mutableListOf(work("y", chapters = (8..20).toList(), year = null)))
        val c = Site("gamma", mutableListOf(work("z", chapters = listOf(11), author = null)))
        val agg = aggregator(listOf(a, b, c))
        val cards = agg.search("example", s)
        assertEquals(1, cards.size, "$cards")
        val u = agg.update(cards[0].key, null, true, true, s.copy(deepDiscovery = true))
        assertEquals(setOf("alpha", "beta", "gamma"), u.state.bindings.keys, "${u.state.bindings.mapValues { it.value.reason }}")
        assertEquals((1..20).map { "Chapter $it" }.reversed(), u.chapters!!.map { it.name })
    }

    @Test
    fun `same title with nothing else in common is not merged`() = runTest {
        val a = Site("alpha", mutableListOf(work("x", author = null, year = null)))
        val b = Site("beta", mutableListOf(work("y", author = null, year = null)))
        val agg = aggregator(listOf(a, b))
        val card = agg.search("example", s).single() // grouped for display only
        val u = agg.update(card.key, null, true, true, s)
        assertEquals(setOf("alpha"), u.state.bindings.keys, "a title-only hint is not trusted")
        assertEquals("y", u.state.rejected["beta"])
        // The next search shows the rejected copy as its own result.
        assertEquals(2, agg.search("example", s).size)
        assertEquals(10, u.chapters!!.size)
    }

    @Test
    fun `different authors keep same-title works apart, sequels and novels stay separate`() = runTest {
        val a = Site("alpha", mutableListOf(work("x", title = "Monster", author = "Naoki Urasawa", year = 1994), work("n", title = "Monster Novel")))
        val b = Site("beta", mutableListOf(work("y", title = "Monster", author = "Kim Jae-han", year = 1995), work("s", title = "Monster Season 2")))
        val agg = aggregator(listOf(a, b))
        // Search hits carry no authors, so the two "Monster" are grouped for display...
        val first = agg.search("monster", s).filter { it.title == "Monster" }
        assertEquals(1, first.size)
        assertTrue(agg.search("monster", s).any { it.title == "Monster Season 2" })
        // ...but opening it checks the details and keeps the other work out.
        val u = agg.update(first[0].key, null, true, true, s)
        assertEquals(setOf("alpha"), u.state.bindings.keys)
        assertEquals(2, agg.search("monster", s).count { it.title == "Monster" })
    }

    @Test
    fun `ambiguous duplicates on one provider are not linked automatically`() = runTest {
        val a = Site("alpha", mutableListOf(work("x")))
        val b = Site("beta", mutableListOf(work("y1"), work("y2")))
        val agg = aggregator(listOf(a, b))
        val u = agg.update(SeriesKey.Provider("alpha", "x"), null, true, true, s.copy(deepDiscovery = true))
        assertEquals(setOf("alpha"), u.state.bindings.keys)
    }

    @Test
    fun `manual link and unlink, and removing them restores automatic matching`() = runTest {
        val a = Site("alpha", mutableListOf(work("x")))
        val b = Site("beta", mutableListOf(work("y"), work("other", title = "Something Else", author = "Other")))
        val agg = aggregator(listOf(a, b))
        val key = SeriesKey.Provider("alpha", "x")
        val linked = agg.update(key, null, true, true, s.copy(manualLinks = mapOf(key.value to listOf("beta:other"))))
        assertEquals("other", linked.state.bindings["beta"]!!.series.id)
        assertTrue(linked.state.bindings["beta"]!!.manual)
        val unlinked = agg.update(key, linked.state, true, true, s.copy(manualLinks = mapOf(key.value to listOf("beta:-"))))
        assertNull(unlinked.state.bindings["beta"])
        now += 8 * CacheManager.DAY
        val auto = agg.update(key, unlinked.state, true, true, s)
        assertEquals("y", auto.state.bindings["beta"]?.series?.id, "the unlink was not made permanent: ${auto.state.bindings}")
    }

    // ------------------------------------------------------------ identities stay stable

    @Test
    fun `confirmed links persist and search keeps showing the library entry whatever the provider order`() = runTest {
        val store = InMemoryStore()
        val a = Site("alpha", mutableListOf(work("x")))
        val b = Site("beta", mutableListOf(work("y")))
        val key = aggregator(listOf(a, b), store).let { agg ->
            val k = agg.search("example", s).single().key
            agg.update(k, null, true, true, s)
            k
        }
        // Restart, a new provider first in the list, and the user reordered the others.
        val c = Site("gamma", mutableListOf(work("z")))
        val agg2 = aggregator(listOf(c, b, a), store)
        val reordered = s.copy(sourceOrder = listOf("gamma", "beta", "alpha"))
        val cards = agg2.search("example", reordered)
        assertEquals(listOf(key), cards.map { it.key })
        // The provider the key names is removed: the entry still reads through its other link.
        val agg3 = aggregator(listOf(b, c), store)
        val u = agg3.update(key, null, false, true, s)
        assertEquals(10, u.chapters!!.size)
        assertTrue(u.chapters!!.all { it.url.startsWith(key.value + "|") }, "chapter URLs keep the series identity")
    }

    @Test
    fun `a library entry recovers its links from the host's chapter records without searching`() = runTest {
        val a = Site("alpha", mutableListOf(work("x")))
        val b = Site("beta", mutableListOf(work("y")))
        val agg = aggregator(listOf(a, b))
        val key = SeriesKey.MangaBaka(42)
        val u = agg.update(key, null, false, true, s, titleHint = "Example Series", recovered = mapOf("alpha" to "x", "beta" to "y"))
        assertEquals(setOf("alpha", "beta"), u.state.bindings.keys)
        assertEquals(10, u.chapters!!.size)
        assertEquals(0, a.searches.get() + b.searches.get())
        assertEquals(emptyList(), metadataRequests())
    }

    @Test
    fun `a library entry known only by title is relinked when two sources corroborate each other`() = runTest {
        val a = Site("alpha", mutableListOf(work("x")))
        val b = Site("beta", mutableListOf(work("y", year = null)))
        val c = Site("gamma", mutableListOf(work("z", author = "Someone Else", year = 2001)))
        val agg = aggregator(listOf(a, b, c))
        val u = agg.update(SeriesKey.MangaBaka(7), null, true, true, s, titleHint = "Example Series")
        assertEquals(setOf("alpha", "beta"), u.state.bindings.keys, "${u.state.bindings}")
        assertEquals("Example Series", u.details.title)
        // A single source with the title is not enough.
        val lone = aggregator(listOf(Site("alpha", mutableListOf(work("x")))))
        assertTrue(lone.update(SeriesKey.MangaBaka(8), null, true, true, s, titleHint = "Example Series").state.bindings.isEmpty())
        assertEquals(emptyList(), metadataRequests())
    }

    // ------------------------------------------------------------ combined information

    @Test
    fun `details are combined field by field with provenance and never weakened`() {
        val short = SourceSeries("alpha", "x", "Example", description = "Short.", status = PublicationStatus.UNKNOWN, genres = listOf("Action"))
        val rich = SourceSeries("beta", "y", "Example", description = "A".repeat(120), authors = listOf("Jane Doe"), status = PublicationStatus.ONGOING, genres = listOf("action", "Drama"))
        val empty = SourceSeries("gamma", "z", "Example", description = "  ", authors = emptyList())
        val m = DetailsMerger.merge(null, listOf(short, empty, rich))!!
        assertEquals("A".repeat(120), m.metadata.description)
        assertEquals("beta", m.provenance["description"])
        assertEquals(listOf("Jane Doe"), m.metadata.authors)
        assertEquals(PublicationStatus.ONGOING, m.metadata.status)
        assertEquals(listOf("Action", "Drama"), m.metadata.genres)
        assertEquals("Example", m.metadata.title)
        assertNull(m.metadata.year, "unknown stays unknown")
        // An external record wins where it knows something, providers fill its gaps.
        val base = SeriesMetadata("mangabaka", title = "Example", description = "B".repeat(100))
        val withBase = DetailsMerger.merge(base, listOf(rich))!!
        assertEquals("mangabaka", withBase.provenance["description"])
        assertEquals("beta", withBase.provenance["authors"])
    }

    @Test
    fun `local filters keep only results known to match`() {
        fun card(id: String, kind: ContentKind, status: PublicationStatus, genres: List<String>) =
            SeriesCard(SeriesKey.Provider("a", id), id, null, SourceSeries("a", id, id, kind = kind, status = status, genres = genres))
        val cards = listOf(
            card("b", ContentKind.MANHWA, PublicationStatus.ONGOING, listOf("Action")),
            card("a", ContentKind.MANGA, PublicationStatus.COMPLETED, listOf("Romance")),
            card("c", ContentKind.UNKNOWN, PublicationStatus.UNKNOWN, emptyList()),
        )
        assertEquals(listOf("b"), LocalFilters.apply(cards, CatalogFilters(types = listOf("manhwa"))).map { it.title })
        assertEquals(listOf("a"), LocalFilters.apply(cards, CatalogFilters(statuses = listOf("completed"))).map { it.title })
        assertEquals(listOf("b", "c"), LocalFilters.apply(cards, CatalogFilters(excludedGenres = listOf("romance"))).map { it.title })
        assertEquals(listOf("a", "b", "c"), LocalFilters.apply(cards, CatalogFilters(sortBy = "name_asc")).map { it.title })
    }

    // ------------------------------------------------------------ request control

    @Test
    fun `search asks the main providers first and the rest only when needed or on the next page`() = runTest {
        val main = (1..6).map { Site("s$it", mutableListOf(work("x$it", title = "Common Title $it"))) }
        val gallery = Site("gal", mutableListOf(work("g", title = "Common Title Doujin")), role = ProviderRole.GALLERY)
        val agg = aggregator(main + gallery)
        val (cards, more) = agg.searchPage("common title", 1, s)
        assertEquals(5, main.count { it.searches.get() == 1 }, "five main providers on page 1")
        assertEquals(0, gallery.searches.get())
        assertTrue(more)
        val (page2, more2) = agg.searchPage("common title", 2, s)
        assertEquals(1, gallery.searches.get())
        assertTrue(main.all { it.searches.get() == 1 }, "nobody is asked twice")
        assertFalse(more2)
        assertTrue(page2.none { c -> c.key in cards.map { it.key } })
        // Little found on the main providers: everyone is asked on page 1.
        val rare = aggregator(main.map { Site(it.id, mutableListOf()) } + Site("gal", mutableListOf(work("g", title = "Rare Doujin")), role = ProviderRole.GALLERY))
        assertEquals(1, rare.search("rare doujin", s).size)
    }

    @Test
    fun `search falls back to its last results when every provider fails`() = runTest {
        val a = Site("alpha", mutableListOf(work("x")))
        val agg = aggregator(listOf(a))
        assertEquals(1, agg.search("example", s).size)
        now += CacheManager.HOUR
        a.failSearch = true
        assertEquals(1, agg.search("example", s).size)
    }

    @Test
    fun `provider searches and details are shared between search and linking`() = runTest {
        val a = Site("alpha", mutableListOf(work("x")))
        val b = Site("beta", mutableListOf(work("y")))
        val agg = aggregator(listOf(a, b))
        val key = agg.search("Example Series", s).single().key
        agg.update(key, null, true, true, s)
        agg.update(SeriesKey.Provider("beta", "y"), null, true, true, s)
        assertEquals(1, a.searches.get(), "the same query is not sent twice within minutes")
        assertEquals(1, b.searches.get())
        assertEquals(1, b.details.get(), "details fetched once for verification and reused")
    }

    @Test
    fun `library updates refresh the leading sources every time and the others a few times a day`() = runTest {
        val sites = listOf(
            Site("alpha", mutableListOf(work("x", chapters = (1..30).toList()))),
            Site("beta", mutableListOf(work("y", chapters = (1..29).toList()))),
            Site("gamma", mutableListOf(work("z", chapters = (1..12).toList()))),
            Site("delta", mutableListOf(work("w", chapters = (1..5).toList()))),
        )
        val agg = aggregator(sites)
        val key = SeriesKey.Provider("alpha", "x")
        var state = agg.update(key, null, true, true, s.copy(deepDiscovery = true)).state
        assertEquals(4, state.bindings.size)
        sites.forEach { it.chapterCalls.set(0) }
        now += 2 * CacheManager.HOUR
        state = agg.update(key, state, fetchDetails = false, fetchChapters = true, settings = s).state
        assertEquals(listOf(1, 1, 0, 0), sites.map { it.chapterCalls.get() }, "leaders only")
        now += 6 * CacheManager.HOUR
        agg.update(key, state, fetchDetails = false, fetchChapters = true, settings = s)
        assertEquals(listOf(2, 2, 1, 1), sites.map { it.chapterCalls.get() }, "everyone after six hours")
        now += 2 * CacheManager.MINUTE
        agg.update(key, state, fetchDetails = true, fetchChapters = true, settings = s) // the user pulls to refresh
        assertEquals(listOf(3, 3, 2, 2), sites.map { it.chapterCalls.get() }, "an explicit refresh asks everyone")
    }

    @Test
    fun `simultaneous library updates share one discovery budget per provider`() = runTest {
        val titles = (1..20).map { "Series Number $it" }
        val alpha = Site("alpha", titles.mapIndexed { i, t -> work("a$i", title = t) }.toMutableList())
        val beta = Site("beta", titles.mapIndexed { i, t -> work("b$i", title = t) }.toMutableList())
        val agg = aggregator(listOf(alpha, beta))
        val states = titles.indices.map { i ->
            SeriesState(
                "p:alpha:a$i",
                bindings = mapOf("alpha" to Binding(SourceSeries("alpha", "a$i", titles[i], authors = listOf("Jane Doe"), year = 2019, detailed = true), 1.0, "entry source")),
            )
        }
        states.map { st -> async { agg.update(SeriesKey.parse(st.key)!!, st, fetchDetails = false, fetchChapters = true, settings = s) } }.awaitAll()
        assertTrue(beta.searches.get() <= 6, "background discovery is budgeted: ${beta.searches.get()} searches")
        // Opening a series is not held back by the budget.
        val opened = agg.update(SeriesKey.Provider("alpha", "a19"), states[19], fetchDetails = true, fetchChapters = true, settings = s)
        assertNotNull(opened.state.bindings["beta"])
    }

    @Test
    fun `an outage keeps known chapters and the series identity`() = runTest {
        val a = Site("alpha", mutableListOf(work("x", chapters = (1..10).toList())))
        val b = Site("beta", mutableListOf(work("y", chapters = (1..20).toList())))
        val agg = aggregator(listOf(a, b))
        val key = SeriesKey.Provider("alpha", "x")
        val first = agg.update(key, null, true, true, s)
        assertEquals(20, first.chapters!!.size)
        b.failChapters = true
        now += CacheManager.DAY
        val during = agg.update(key, first.state, true, true, s)
        assertEquals(20, during.chapters!!.size, "beta's last known chapters are kept")
        assertEquals(first.chapters!!.map { it.url }, during.chapters!!.map { it.url })
    }

    @Test
    fun `optional work avoids a provider whose request queue is already long`() = runTest {
        val a = Site("alpha", mutableListOf(work("x", chapters = (1..10).toList())))
        val b = Site("beta", mutableListOf(work("y", chapters = (1..12).toList())))
        val agg = aggregator(listOf(a, b))
        val key = SeriesKey.Provider("alpha", "x")
        val first = agg.update(key, null, true, true, s)
        assertEquals(2, first.state.bindings.size)
        busy["beta"] = 30_000L
        now += 8 * CacheManager.HOUR
        b.chapterCalls.set(0)
        val bg = agg.update(key, first.state, fetchDetails = false, fetchChapters = true, settings = s)
        assertEquals(0, b.chapterCalls.get(), "a library update uses beta's stored list instead of queueing")
        assertEquals(12, bg.chapters!!.size)
        b.searches.set(0)
        agg.search("something else", s)
        assertEquals(0, b.searches.get(), "search routes around the busy provider on page 1")
        // Explicit refreshes still ask it (the user is waiting for exactly that).
        agg.update(key, first.state, fetchDetails = true, fetchChapters = true, settings = s)
        assertEquals(1, b.chapterCalls.get())
    }
}
