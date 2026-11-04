package alexandria.core

import alexandria.core.cache.CacheManager
import alexandria.core.cache.InMemoryStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.metadata.AniListClient
import alexandria.core.metadata.MangaBakaClient
import alexandria.core.metadata.MetadataResolver
import alexandria.core.normalization.TitleNormalizer
import alexandria.core.quality.ImageProbe
import alexandria.core.ranking.ProviderHealth
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Replays a bulk migration (opt-in: LOA_REPLAY=<tsv of "source<TAB>title">, LOA_REPLAY_OUT=<result tsv>): searches
 * every title four at a time and opens the match, as the host does, while the next searches run.
 */
class MigrationReplay {
    @Test
    fun replay(): Unit = runBlocking(Dispatchers.IO) {
        val input = System.getenv("LOA_REPLAY")
        assumeTrue(!input.isNullOrBlank())
        val rows = File(input).readLines().filter { '\t' in it }.map { it.substringBefore('\t') to it.substringAfter('\t') }
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
        val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
        val store = InMemoryStore()
        val cache = CacheManager(store)
        val reg = SourceRegistry.defaults(AdapterContext(client, json), ProviderHealth(store))
        val meta = MetadataResolver(MangaBakaClient(client, json, "loa-replay"), AniListClient(client, json, "loa-replay"), cache)
        val agg = Aggregator(reg, meta, cache, client)
        val settings = AggregatorSettings()
        val gate = Semaphore(4)
        val t0 = System.currentTimeMillis()
        val out = rows.map { (src, title) ->
            async {
                gate.withPermit {
                    val s0 = System.currentTimeMillis()
                    val cards = runCatching { agg.searchPage(title, 1, settings).first }.getOrElse { emptyList() }
                    val ms = System.currentTimeMillis() - s0
                    val scored = cards.map { it to TitleNormalizer.similarity(title, it.title) }
                    val best = scored.maxByOrNull { it.second }
                    val near = scored.count { it.second >= 0.9 }
                    var chapters = -1
                    var px = ""
                    if (best != null && best.second >= 0.6) {
                        best.first.coverUrl?.let { u ->
                            val owner = reg.imageOwner(okhttp3.HttpUrl.Builder().scheme("https").host(java.net.URI(u).host).build().host)
                            px = runCatching { ImageProbe.probe(client, u, (owner?.imageHeaders(u) ?: emptyMap()).toHeaders()) }.getOrNull()?.let { "${it.width}x${it.height}" } ?: "fail"
                        }
                        chapters = runCatching { agg.update(best.first.key, null, true, true, settings).chapters?.size ?: 0 }.getOrElse { -2 }
                    }
                    val dups = scored.filter { it.second >= 0.9 }.joinToString(" || ") { "${it.first.key.value}=${it.first.title}" }
                    listOf(src, title, cards.size, ms, best?.first?.title ?: "", "%.2f".format(best?.second ?: 0.0), near, px, chapters, best?.first?.coverUrl ?: "", dups).joinToString("\t")
                }
            }
        }.awaitAll()
        File(System.getenv("LOA_REPLAY_OUT") ?: "replay.tsv").writeText(out.joinToString("\n") + "\n")
        println("REPLAY done ${rows.size} titles in ${(System.currentTimeMillis() - t0) / 1000}s")
    }
}
