package alexandria.core

import alexandria.core.ranking.ProviderHealth
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Live check of the providers added in 1.6.8 (opt-in: LOA_LIVE=1). */
class LiveNewProvidersTest {
    @Test
    fun `new providers search, list chapters, return pages and serve images`(): Unit = runBlocking {
        assumeTrue(System.getenv("LOA_LIVE") == "1")
        val client = OkHttpClient()
        val reg = SourceRegistry.defaults(AdapterContext(client, Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }), ProviderHealth(null))
        val q = mapOf("loa_mangafire" to "Solo Leveling", "loa_mangataro" to "Solo Leveling", "loa_mangaball" to "Solo Leveling", "loa_flamecomics" to "Omniscient Reader")
        val failures = mutableListOf<String>()
        for ((sid, query) in q) {
            val a = reg[sid]!!
            try {
                val hits = a.search(query)
                val hit = hits.firstOrNull { it.title.contains(query, true) } ?: hits.first()
                val d = a.details(hit)
                val ch = a.chapters(d, listOf("en"))
                val c = ch.maxByOrNull { it.number?.toDoubleOrNull() ?: 0.0 }!!
                val pages = a.pages(c, false)
                val p = pages.first()
                val code = client.newCall(Request.Builder().url(p.imageUrl).apply { p.headers.forEach { (k, v) -> header(k, v) } }.build()).execute().use { "${it.code} ${it.header("Content-Type")} ${it.body.bytes().size}B" }
                val coverCode = d.coverUrl?.let { u -> client.newCall(Request.Builder().url(u).apply { a.imageHeaders(u).forEach { (k, v) -> header(k, v) } }.build()).execute().use { "${it.code} ${it.body.bytes().size}B" } }
                kotlin.test.assertTrue(code.startsWith("200 image/"), "$sid image: $code")
                println("OK $sid: ${hits.size} hits; '${d.title}' authors=${d.authors.take(2)} year=${d.year} ids=${d.ids} alt=${d.altTitles.size}; ${ch.size} chapters, latest='${c.label}' -> ${pages.size} pages, image $code; cover $coverCode")
            } catch (e: Exception) {
                println("FAIL $sid: ${e::class.simpleName}: ${e.message}")
                failures += sid
            }
        }
            kotlin.test.assertTrue(failures.isEmpty(), "failed: $failures")
    }
}
