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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Hits the real services. Opt-in: `LOA_LIVE=1 ./gradlew :core:test --tests '*LiveIntegrationTest*'`.
 * Results depend on third-party sites and may change over time.
 */
class LiveIntegrationTest {
    private fun live() = assumeTrue("set LOA_LIVE=1 to run live tests", System.getenv("LOA_LIVE") == "1")

    private val settings = AggregatorSettings(languages = listOf("en"), requestTimeoutSeconds = 30)
    private val logs = mutableListOf<String>()

    private fun aggregator(): Aggregator {
        val store = InMemoryStore()
        val cache = CacheManager(store)
        val health = ProviderHealth(store)
        lateinit var agg: Aggregator
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(FallbackInterceptor({ agg.reader }, { settings }))
            .build()
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
        val ua = "LibraryOfAlexandria/1.0-test (+https://github.com/KarsTheUltimateCreation/library-of-alexandria)"
        val ctx = AdapterContext(client, json, ua)
        val registry = SourceRegistry.defaults(ctx, health)
        val log: (String) -> Unit = { synchronized(logs) { logs += it }; println("  [log] $it") }
        val meta = MetadataResolver(MangaBakaClient(client, json, ua), AniListClient(client, json, ua), cache, log = log)
        agg = Aggregator(registry, meta, cache, client, log = log)
        return agg
    }

    @Test
    fun `adapters search and list chapters`() = runBlocking {
        live()
        val agg = aggregator()
        // mgeko carries mostly manhwa/manhua (no One Piece).
        val queries = mapOf("mgeko" to "Omniscient Reader's Viewpoint")
        for (adapter in agg.registry.all) {
            val query = queries[adapter.id] ?: "One Piece"
            val hits = adapter.search(query)
            println("${adapter.id}: ${hits.size} hits -> ${hits.take(3).map { it.title + " [" + it.id + "]" }}")
            assertTrue(hits.isNotEmpty(), "${adapter.id} search returned nothing")
            val target = hits.firstOrNull { it.title.equals(query, true) } ?: hits.first { it.title.contains(query.substringBefore('\''), true) }
            val detailed = adapter.details(target)
            println("  details: alt=${detailed.altTitles.take(4)} ids=${detailed.ids} kind=${detailed.kind} status=${detailed.status}")
            val chapters = adapter.chapters(detailed, listOf("en"))
            println("  chapters: ${chapters.size}, first=${chapters.lastOrNull()?.label}, last=${chapters.firstOrNull()?.label}")
            if (adapter.id != "mangadex") assertTrue(chapters.size > 100, "${adapter.id} chapter list too small")
            // Some listed chapters are broken upstream (MangaDex lists chapters whose at-home lookup 404s),
            // so try a few; the aggregator itself handles this through fallback.
            val pool = if (chapters.size > 1) chapters.filter { !it.externalOnly } else {
                val latest = adapter.latest(1).first()
                adapter.chapters(adapter.details(latest), listOf("en")).also { println("  (using latest: ${latest.title}, ${it.size} ch)") }
            }
            val pages = pool.take(4).firstNotNullOfOrNull { c ->
                runCatching { adapter.pages(c, dataSaver = false) }.onFailure { println("  pages failed for '${c.label}': ${it.message}") }
                    .getOrNull()?.takeIf { it.isNotEmpty() }?.also { println("  pages for '${c.label}': ${it.size} e.g. ${it.first().imageUrl}") }
            }
            assertTrue(!pages.isNullOrEmpty(), "${adapter.id} returned no pages for 4 chapters")
        }
    }

    @Test
    fun `end to end unified series`() = runBlocking {
        live()
        val agg = aggregator()
        val cards = agg.search("Solo Leveling", settings)
        println("search: " + cards.take(8).joinToString { "${it.title} <${it.key}>" })
        val card = cards.first { it.title.equals("Solo Leveling", true) }
        assertTrue(card.key is SeriesKey.MangaBaka, "expected MangaBaka identity, got ${card.key}")

        val update = agg.update(card.key, null, fetchDetails = true, fetchChapters = true, settings)
        println("bindings: " + update.state.bindings.map { (k, v) -> "$k=${v.series.title} (${"%.2f".format(v.confidence)}: ${v.reason})" })
        println("cover: ${update.details.coverUrl?.substringBefore('#')}")
        println("chapters: ${update.chapters?.size}; newest=${update.chapters?.firstOrNull()?.name} oldest=${update.chapters?.lastOrNull()?.name}")
        println("description tail:\n" + update.details.description?.substringAfter("— Library of Alexandria —"))
        assertTrue((update.chapters?.size ?: 0) >= 150)
        assertTrue(update.state.bindings.keys.containsAll(listOf("weebcentral")), "weebcentral should be linked")
        assertTrue(update.state.bindings.values.none { it.series.title.contains("novel", true) || it.series.title.contains("ragnarok", true) })

        val chapter = update.chapters!!.first { it.name.startsWith("Chapter 100") }
        val res = agg.pages(chapter.url, chapter.releases, settings)
        println("chapter 100 -> ${res.chosen.sourceId}, ${res.pages.size} pages, notes=${res.notes}, fallbacks=${res.fallbacks.map { it.sourceId }}")
        assertTrue(res.pages.size > 5)

        // Image download through the client exercises the interceptor path.
        val client = OkHttpClient.Builder().addInterceptor(FallbackInterceptor({ agg.reader }, { settings })).build()
        val p = res.pages.first()
        client.newCall(Request.Builder().url(p.imageUrl).apply { p.headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use {
            println("first image: HTTP ${it.code} ${it.header("Content-Type")} ${it.body.contentLength()} bytes")
            assertTrue(it.isSuccessful)
        }

        // Memo-less hosts: resolution must still work from persisted bindings alone.
        val res2 = agg.pages(chapter.url, null, settings)
        assertTrue(res2.pages.isNotEmpty())
    }

    @Test
    fun `per chapter quality comparison across providers`() = runBlocking {
        live()
        val agg = aggregator()
        val card = agg.search("Berserk", settings).first { it.title.equals("Berserk", true) }
        val update = agg.update(card.key, null, fetchDetails = true, fetchChapters = true, settings)
        println("bindings: " + update.state.bindings.map { (k, v) -> "$k=${v.series.title}" })
        val multi = update.chapters!!.filter { c -> c.releases.map { it.sourceId }.distinct().size >= 2 }
        println("chapters: ${update.chapters!!.size}, with 2+ providers: ${multi.size}")
        println("report: " + update.report?.summaryLines())
        assertTrue(multi.isNotEmpty(), "expected overlapping providers for Berserk")
        val target = multi[multi.size / 2]
        for (mode in alexandria.core.config.QualityMode.entries) {
            val s = settings.copy(qualityMode = mode)
            agg.cache.clear("choice:")
            val res = agg.pages(target.url, target.releases, s)
            println("${target.name} [$mode] -> ${res.chosen.sourceId} (${res.pages.size} pages) ${res.notes}")
            assertTrue(res.pages.isNotEmpty())
        }
    }

    @Test
    fun `provider-only entry and anilist fallback`() = runBlocking {
        live()
        val agg = aggregator()
        val noBaka = settings.copy(mangabaka = false)
        val cards = agg.search("Omniscient Reader", noBaka)
        println("anilist-only search: " + cards.take(5).joinToString { "${it.title} <${it.key}>" })
        assertTrue(cards.any { it.key is SeriesKey.AniList || it.key is SeriesKey.Provider })
        val noMeta = settings.copy(mangabaka = false, anilist = false)
        val provOnly = agg.search("Omniscient Reader", noMeta)
        println("provider-only search: " + provOnly.take(5).joinToString { "${it.title} <${it.key}>" })
        assertTrue(provOnly.all { it.key is SeriesKey.Provider })
    }
}
