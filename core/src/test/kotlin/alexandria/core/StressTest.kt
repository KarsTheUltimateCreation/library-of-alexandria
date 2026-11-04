package alexandria.core

import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.metadata.AniListClient
import alexandria.core.metadata.MangaBakaClient
import alexandria.core.metadata.MetadataResolver
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.net.RateLimiter
import alexandria.core.net.fetchText
import alexandria.core.ranking.ProviderHealth
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * Stress test of the real network stack (fetch, rate limiters, service cool-downs, retries, timeouts, single-flight,
 * caches) against six simulated sites that enforce their own rate limits server-side and misbehave in different
 * ways. Opt-in: `LOA_STRESS=1 [LOA_STRESS_OUT=<file>] ./gradlew :core:test --tests '*StressTest*'`.
 */
class StressTest {

    /** Behaviour of one simulated site. Server-side limits are a token bucket: [serverRate] per second, burst [burst]. */
    private class Behaviour(val serverRate: Double, val burst: Double, val errorRate: Double = 0.0, val slowRate: Double = 0.0, @Volatile var down: Boolean = false)

    private class SiteStats { val requests = AtomicInteger(); val r429 = AtomicInteger(); val r5xx = AtomicInteger(); val slow = AtomicInteger() }

    private val behaviours = linkedMapOf(
        "steady" to Behaviour(serverRate = 12.0, burst = 12.0),
        // Allows less than our limiter sends: provokes 429 + Retry-After, which must pause every caller.
        "strict" to Behaviour(serverRate = 4.0, burst = 4.0),
        "flaky" to Behaviour(serverRate = 12.0, burst = 12.0, errorRate = 0.10),
        "slow" to Behaviour(serverRate = 12.0, burst = 12.0, slowRate = 0.25),
        "outage" to Behaviour(serverRate = 12.0, burst = 12.0),
        "normal" to Behaviour(serverRate = 12.0, burst = 12.0),
    )
    private val stats = behaviours.keys.associateWith { SiteStats() }
    private val buckets = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val rnd = Random(42)

    private fun allow(site: String): Boolean = synchronized(buckets) {
        val b = behaviours.getValue(site)
        val now = System.nanoTime()
        val (tokens, last) = buckets[site] ?: (b.burst to now)
        val refill = minOf(b.burst, tokens + (now - last) / 1e9 * b.serverRate)
        if (refill >= 1.0) { buckets[site] = (refill - 1.0) to now; true } else { buckets[site] = refill to now; false }
    }

    private val works = 120

    /** Which works a site carries (75%), and how many chapters (with a gap on "flaky"). */
    private fun carries(siteIndex: Int, n: Int) = (n + siteIndex) % 4 != 0
    private fun chapterNumbers(site: String, n: Int): List<Int> {
        val total = 20 + n % 40
        return (1..total).filter { site != "flaky" || it % 7 != 0 }
    }

    private val png = Buffer().write(ByteArray(64).also {
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(it)
        it[18] = 0x03; it[19] = 0xE8.toByte(); it[22] = 0x05; it[23] = 0x78
    })

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val segs = request.url.pathSegments
            val site = segs.getOrNull(0) ?: return MockResponse.Builder().code(404).build()
            val b = behaviours[site] ?: return MockResponse.Builder().code(404).build()
            val st = stats.getValue(site)
            st.requests.incrementAndGet()
            if (segs.getOrNull(1) == "img") return MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(png.clone()).build()
            if (b.down) { st.r5xx.incrementAndGet(); return MockResponse.Builder().code(503).build() }
            if (!allow(site)) { st.r429.incrementAndGet(); return MockResponse.Builder().code(429).addHeader("Retry-After", "1").build() }
            if (rnd.nextDouble() < b.errorRate) { st.r5xx.incrementAndGet(); return MockResponse.Builder().code(500).build() }
            val builder = MockResponse.Builder().code(200)
            if (rnd.nextDouble() < b.slowRate) { st.slow.incrementAndGet(); builder.headersDelay(4, TimeUnit.SECONDS) }
            val si = behaviours.keys.indexOf(site)
            val body = when (segs.getOrNull(1)) {
                "search" -> {
                    val q = request.url.queryParameter("q").orEmpty().lowercase()
                    (1..works).filter { carries(si, it) && "series number $it".contains(q) }.take(20).joinToString("\n") { "w$it|Series Number $it" }
                }
                "details" -> segs[2].removePrefix("w").toInt().let { "w$it|Series Number $it|Author $it|${2000 + it % 20}" }
                "chapters" -> chapterNumbers(site, segs[2].removePrefix("w").toInt()).joinToString("\n") { "c$it|Chapter $it" }
                "pages" -> (0 until 6).joinToString("\n") { "/$site/img/${segs[2]}/$it.png" }
                else -> return MockResponse.Builder().code(404).build()
            }
            return builder.body(body).build()
        }
    }

    private inner class Site(override val id: String, private val server: MockWebServer, private val client: OkHttpClient) : SourceAdapter {
        override val name = id
        override val baseUrl = server.url("/$id").toString()
        override val languages: Set<String>? = setOf("en")
        private val limiter = RateLimiter.shared("stress-$id", 10, 1_000) // we send at most 10/s per site

        private suspend fun get(path: String) = client.fetchText(Request.Builder().url(server.url("/$id/$path")).build(), limiter = limiter)

        override suspend fun search(query: String) = get("search?q=${java.net.URLEncoder.encode(query, "UTF-8")}").lines().filter { it.isNotBlank() }.map {
            val (wid, title) = it.split('|')
            SourceSeries(id, wid, title, coverUrl = server.url("/$id/img/cover-$wid.png").toString())
        }

        override suspend fun details(series: SourceSeries): SourceSeries {
            val (_, title, author, year) = get("details/${series.id}").split('|')
            return series.copy(title = title, authors = listOf(author), year = year.toInt(), detailed = true)
        }

        override suspend fun chapters(series: SourceSeries, languages: List<String>) = get("chapters/${series.id}").lines().filter { it.isNotBlank() }.map {
            val (cid, label) = it.split('|')
            SourceChapter(id, series.id, "${series.id}-$cid", label)
        }

        override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean) =
            get("pages/${chapter.id}").lines().filter { it.isNotBlank() }.mapIndexed { i, p -> ReaderPage(i, server.url(p).toString()) }

        override fun seriesWebUrl(seriesId: String) = "$baseUrl/$seriesId"
    }

    @Test
    fun `simulated sites under heavy concurrent load`(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue("set LOA_STRESS=1", System.getenv("LOA_STRESS") == "1")
        val server = MockWebServer()
        server.dispatcher = dispatcher
        server.start()
        val offset = AtomicLong()
        val clock = { System.currentTimeMillis() + offset.get() }
        val client = OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 128 }).build()
        val store = InMemoryStore()
        val cache = CacheManager(store, clock = clock, groupLimits = mapOf("state:" to 400, "idx:" to 2000, "cimg:" to 600))
        val lists = CacheManager(InMemoryStore(), clock = clock, maxPersistedEntries = 48, maxPersistedChars = 150_000)
        val json = Json { ignoreUnknownKeys = true }
        val meta = MetadataResolver(MangaBakaClient(client, json, "t"), AniListClient(client, json, "t"), cache)
        val sites = behaviours.keys.map { Site(it, server, client) }
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val agg = Aggregator(SourceRegistry(sites, ProviderHealth(store, clock)), meta, cache, client, log = { logs += it }, clock = clock, listCache = lists)
        val settings = AggregatorSettings()

        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        val shrinks = java.util.Collections.synchronizedList(mutableListOf<String>())
        val overCap = java.util.Collections.synchronizedList(mutableListOf<String>())
        val counts = ConcurrentHashMap<String, Int>()
        val states = ConcurrentHashMap<String, SeriesState>()
        val keys = java.util.Collections.synchronizedList(mutableListOf<SeriesKey>())
        val phases = linkedMapOf<String, String>()

        suspend fun <T> guard(what: String, block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { errors += "$what: ${e::class.simpleName}: ${e.message}"; null }
        fun snapshot() = stats.mapValues { (_, s) -> s.requests.get() }
        suspend fun phase(name: String, block: suspend () -> Unit) {
            val before = snapshot()
            val t = System.currentTimeMillis()
            block()
            val after = snapshot()
            phases[name] = "${System.currentTimeMillis() - t} ms, requests " + after.mapValues { it.value - before.getValue(it.key) }
        }
        fun record(k: SeriesKey, u: SeriesUpdate) {
            states[k.value] = u.state
            val n = u.chapters?.size ?: return
            val prev = counts[k.value]
            if (prev != null && n < prev) shrinks += "${k.value}: $prev -> $n"
            counts[k.value] = n
            val linked = u.state.bindings.count { !it.value.unverified }
            if (linked > 5) overCap += "${k.value}: $linked linked"
        }
        suspend fun libraryUpdate(tag: String, parallel: Int = 8) {
            val gate = Semaphore(parallel)
            keys.toList().map { k -> async { gate.withPermit { guard("$tag ${k.value}") { agg.update(k, states[k.value], false, true, settings, titleHint = null) }?.let { record(k, it) } } } }.awaitAll()
        }

        withTimeout(20 * 60_000L) {
            // 1. Forty different searches at once (a user typing fast across several tabs is far less).
            phase("40 concurrent searches") {
                (1..40).map { n -> async { guard("search $n") { agg.search("Series Number $n", settings) } } }.awaitAll()
                    .forEach { cards -> cards?.firstOrNull { c -> c.title.matches(Regex("Series Number \\d+")) }?.let { c -> if (c.key !in keys) keys += c.key } }
            }
            // 2. Open every series of the library (details + chapters), eight at a time.
            phase("open 120 series (8 parallel)") {
                val gate = Semaphore(8)
                val extra = (41..works).map { n -> async { guard("search $n") { agg.search("Series Number $n", settings) }?.firstOrNull { it.title == "Series Number $n" }?.key } }.awaitAll().filterNotNull()
                extra.forEach { if (it !in keys) keys += it }
                keys.toList().map { k -> async { gate.withPermit { guard("open ${k.value}") { agg.update(k, null, true, true, settings) }?.let { record(k, it) } } } }.awaitAll()
            }
            // 3. Library update + 20 searches + 30 chapter openings at the same time.
            offset.addAndGet(8 * 3_600_000L)
            phase("library update + 20 searches + 30 chapter reads concurrently") {
                listOf(
                    async { libraryUpdate("update1") },
                    async { (1..20).map { n -> async { guard("search again") { agg.search("Series Number ${n * 3}", settings) } } }.awaitAll() },
                    async {
                        keys.take(30).map { k -> async {
                            agg.update(k, states[k.value], false, true, settings).chapters?.firstOrNull()?.let { c -> guard("pages ${c.url}") { agg.pages(c.url, c.releases, settings) } }
                        } }.awaitAll()
                    },
                ).awaitAll()
            }
            // 4. One site goes down completely during a library update: nothing may shrink.
            behaviours.getValue("outage").down = true
            offset.addAndGet(8 * 3_600_000L)
            phase("library update with one site down") { libraryUpdate("update2") }
            // 5. Cancel 40 updates mid-flight, then run them again: everything must still complete.
            offset.addAndGet(8 * 3_600_000L)
            phase("cancel 40 updates mid-flight, then rerun") {
                val job = launch { libraryUpdate("cancelled", parallel = 16) }
                delay(1_500)
                job.cancel()
                job.join()
                libraryUpdate("after-cancel")
            }
            // 6. Back online, double-speed library updates twice in a row (duplicate work must be merged).
            behaviours.getValue("outage").down = false
            offset.addAndGet(8 * 3_600_000L)
            phase("two library updates at once") { listOf(async { libraryUpdate("dup-a") }, async { libraryUpdate("dup-b") }).awaitAll() }
        }
        server.close()

        val out = buildString {
            appendLine("series in library: ${keys.size}")
            phases.forEach { (k, v) -> appendLine("phase $k: $v") }
            appendLine("server-side per site (requests / 429 / 5xx / slow):")
            stats.forEach { (k, s) -> appendLine("  %-7s %6d %5d %5d %5d".format(k, s.requests.get(), s.r429.get(), s.r5xx.get(), s.slow.get())) }
            appendLine("errors surfaced to callers: ${errors.size}")
            errors.groupBy { it.substringAfter(": ").take(90) }.entries.sortedByDescending { it.value.size }.take(8).forEach { appendLine("  ${it.value.size}× ${it.key}") }
            appendLine("chapter lists that shrank: ${shrinks.size} ${shrinks.take(5)}")
            appendLine("series over the 5-source cap: ${overCap.size} ${overCap.take(5)}")
            val linked = states.values.map { s -> s.bindings.count { !it.value.unverified } }
            appendLine("linked sources per series: min ${linked.minOrNull()} max ${linked.maxOrNull()} avg ${"%.2f".format(linked.average())}")
            appendLine("series with no chapters: ${keys.count { (counts[it.value] ?: 0) == 0 }}")
            appendLine("local cool-down / queue refusals: ${logs.count { "asked to wait" in it || "local request slot" in it }}")
        }
        println(out)
        System.getenv("LOA_STRESS_OUT")?.takeIf { it.isNotBlank() }?.let { File(it).writeText(out) }
        assertTrue(shrinks.isEmpty(), "chapter lists shrank: $shrinks")
        assertTrue(overCap.isEmpty(), "over cap: $overCap")
    }
}
