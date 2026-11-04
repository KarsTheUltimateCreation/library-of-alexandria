package alexandria.core

import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.fallback.FallbackInterceptor
import alexandria.core.metadata.AniListClient
import alexandria.core.metadata.MangaBakaClient
import alexandria.core.metadata.MetadataResolver
import alexandria.core.ranking.ProviderHealth
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live request/rate-limit benchmark: one fixed, realistic session (searches, opening series, two library updates, a
 * concurrent burst, reading) against the real services, counting every HTTP request per host, every 429, every
 * Retry-After and every user-visible failure. The same file runs unchanged against older versions, so their numbers
 * can be compared. Opt-in: `LOA_BENCH=1 LOA_BENCH_OUT=<file> ./gradlew :core:test --tests '*RateLimitBenchmark*'`.
 */
class RateLimitBenchmark {

    private class Host { val requests = AtomicInteger(); val r429 = AtomicInteger(); val retryAfter = AtomicInteger(); val other4xx5xx = AtomicInteger(); val failed = AtomicInteger() }

    private val hosts = ConcurrentHashMap<String, Host>()
    private val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val visible = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val counter = Interceptor { chain ->
        val host = chain.request().url.host
        val h = hosts.getOrPut(host) { Host() }
        h.requests.incrementAndGet()
        try {
            chain.proceed(chain.request()).also { r ->
                if (r.code == 429) h.r429.incrementAndGet() else if (r.code >= 400) h.other4xx5xx.incrementAndGet()
                if (r.header("Retry-After") != null) h.retryAfter.incrementAndGet()
            }
        } catch (e: java.io.IOException) {
            h.failed.incrementAndGet()
            throw e
        }
    }

    @Test
    fun `live session request and rate limit profile`(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue("set LOA_BENCH=1", System.getenv("LOA_BENCH") == "1")
        val settings = AggregatorSettings() // what a fresh install uses
        // Simulated wall clock: library updates happen hours apart in real use, which is when cached chapter lists
        // expire and every linked provider is asked again. Rate limiters and timeouts still use real time.
        val offset = java.util.concurrent.atomic.AtomicLong()
        val clock = { System.currentTimeMillis() + offset.get() }
        val store = InMemoryStore()
        val cache = CacheManager(store, clock = clock)
        val lists = CacheManager(InMemoryStore(), clock = clock)
        val health = ProviderHealth(store, clock)
        lateinit var agg: Aggregator
        val jar = object : okhttp3.CookieJar {
            private val all = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Cookie>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) { all += cookies }
            override fun loadForRequest(url: okhttp3.HttpUrl) = all.filter { it.matches(url) }
        }
        val client = OkHttpClient.Builder().cookieJar(jar)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .addInterceptor(FallbackInterceptor({ agg.reader }, { settings }))
            .addNetworkInterceptor(counter)
            .build()
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
        val ua = "LibraryOfAlexandria/bench (+https://github.com/KarsTheUltimateCreation/library-of-alexandria)"
        val registry = SourceRegistry.defaults(AdapterContext(client, json, ua), health)
        val log: (String) -> Unit = { logs += it }
        val meta = MetadataResolver(MangaBakaClient(client, json, ua), AniListClient(client, json, ua), cache, log = log)
        agg = Aggregator(registry, meta, cache, client, log = log, clock = clock, listCache = lists)

        val phases = linkedMapOf<String, Long>()
        suspend fun phase(name: String, block: suspend () -> Unit) {
            val t = System.currentTimeMillis()
            block()
            phases[name] = System.currentTimeMillis() - t
        }
        suspend fun <T> visibleCall(what: String, block: suspend () -> T): T? = try {
            block()
        } catch (e: Exception) {
            visible += "$what: ${e::class.simpleName}: ${e.message}"
            null
        }

        val queries = listOf(
            "solo leveling", "one piece", "berserk", "omniscient reader", "chainsaw man", "the beginning after the end",
            "frieren", "blue lock", "jujutsu kaisen", "vinland saga", "tower of god", "kagurabachi", "dandadan", "nano machine",
        )
        val opened = mutableListOf<SeriesKey>()
        phase("search") {
            for (q in queries) {
                val cards = visibleCall("search $q") { agg.search(q, settings) }.orEmpty()
                if (cards.isEmpty()) visible += "search $q: no results"
                cards.firstOrNull()?.let { opened += it.key }
            }
        }
        val states = ConcurrentHashMap<String, SeriesState>()
        val chapters = ConcurrentHashMap<String, List<ChapterEntry>>()
        phase("open") {
            for (k in opened) {
                visibleCall("open ${k.value}") { agg.update(k, null, fetchDetails = true, fetchChapters = true, settings = settings) }?.let { u ->
                    states[k.value] = u.state
                    u.chapters?.let { chapters[k.value] = it }
                    if (u.chapters.isNullOrEmpty()) visible += "open ${k.value} (${u.details.title}): no chapters"
                }
            }
        }
        suspend fun libraryUpdate(tag: String) {
            val gate = Semaphore(4)
            opened.map { k ->
                async {
                    gate.withPermit {
                        visibleCall("$tag ${k.value}") { agg.update(k, states[k.value], fetchDetails = false, fetchChapters = true, settings = settings) }?.let { u ->
                            states[k.value] = u.state
                            if (u.chapters.isNullOrEmpty()) visible += "$tag ${k.value}: no chapters"
                        }
                    }
                }
            }.awaitAll()
        }
        phase("library update right after opening") { libraryUpdate("update0") }
        for (i in 1..3) {
            offset.addAndGet(8 * 3_600_000L)
            phase("library update +${i * 8}h") { libraryUpdate("update$i") }
        }
        offset.addAndGet(3_600_000L)
        phase("same searches again 1h later") { for (q in queries.take(6)) visibleCall("search again $q") { agg.search(q, settings) } }
        offset.addAndGet(8 * 3_600_000L)
        phase("burst: 3 searches during a library update") {
            listOf(
                async { libraryUpdate("burst-update") },
                async { visibleCall("burst search") { agg.search("vagabond", settings) } },
                async { visibleCall("burst search") { agg.search("monster", settings) } },
                async { visibleCall("burst search") { agg.search("kingdom", settings) } },
            ).awaitAll()
        }
        phase("read") {
            for (k in opened.take(4)) {
                val list = chapters[k.value].orEmpty()
                for (c in list.take(2)) {
                    val res = visibleCall("pages ${c.url}") { agg.pages(c.url, null, settings) } ?: continue
                    for (p in res.pages.take(3)) {
                        val url = if (p.lazy) visibleCall("image url") { agg.resolveImage(p.imageUrl, settings) } ?: continue else p.imageUrl
                        val src = FallbackInterceptor.pageToken(url)?.sourceId
                        val headers = src?.let { registry[it] }?.imageHeaders(FallbackInterceptor.stripTag(url)).orEmpty()
                        val req = Request.Builder().url(url).apply { headers.forEach { (a, b) -> header(a, b) } }.build()
                        visibleCall("image") {
                            withContext(Dispatchers.IO) { client.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body.bytes().size } }
                        }
                    }
                }
            }
        }

        val metaHosts = setOf("api.mangabaka.org", "mangabaka.org", "graphql.anilist.co", "anilist.co")
        fun isMeta(h: String) = h in metaHosts || h.endsWith(".mangabaka.org") || h.endsWith(".mangabaka.dev") || h.endsWith(".anilist.co")
        val total = hosts.values.sumOf { it.requests.get() }
        val out = buildString {
            appendLine("phase timings (ms): $phases")
            appendLine("total requests: $total")
            appendLine("metadata-service requests: ${hosts.filterKeys(::isMeta).values.sumOf { it.requests.get() }}")
            appendLine("HTTP 429 responses: ${hosts.values.sumOf { it.r429.get() }}")
            appendLine("responses with Retry-After: ${hosts.values.sumOf { it.retryAfter.get() }}")
            appendLine("other 4xx/5xx: ${hosts.values.sumOf { it.other4xx5xx.get() }}; transport failures: ${hosts.values.sumOf { it.failed.get() }}")
            appendLine("local cool-down / queue refusals logged: ${logs.count { "asked to wait" in it || "local request slot" in it }}")
            appendLine("user-visible failures: ${visible.size}")
            visible.forEach { appendLine("  - $it") }
            appendLine("per host (requests / 429 / other errors / transport failures / Retry-After):")
            hosts.entries.sortedByDescending { it.value.requests.get() }.forEach { (h, s) ->
                appendLine("  %-34s %5d %4d %4d %4d %4d%s".format(h, s.requests.get(), s.r429.get(), s.other4xx5xx.get(), s.failed.get(), s.retryAfter.get(), if (isMeta(h)) "  [metadata]" else ""))
            }
            appendLine("log lines mentioning 429 / wait: ")
            logs.filter { "429" in it || "asked to wait" in it || "local request slot" in it }.take(40).forEach { appendLine("  $it") }
        }
        println(out)
        System.getenv("LOA_BENCH_OUT")?.takeIf { it.isNotBlank() }?.let { File(it).writeText(out) }
        Unit
    }
}
