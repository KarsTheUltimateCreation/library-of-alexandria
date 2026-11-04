package alexandria.core

import alexandria.core.cache.InMemoryStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.model.SeriesMetadata
import alexandria.core.net.ErrorKind
import alexandria.core.ranking.ProviderHealth
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.ProviderRole
import alexandria.core.sources.SourceRegistry
import alexandria.core.sources.adapters.Galleries
import alexandria.core.sources.adapters.Hentai20Adapter
import alexandria.core.sources.adapters.HitomiAdapter
import alexandria.core.sources.adapters.MangaDexAdapter
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Speed, provider-selection and new-provider behaviour introduced in 1.6.5. */
class Release165Test {
    private val ctx = AdapterContext(OkHttpClient())

    @Test
    fun `gallery names are reduced to the work's title`() {
        assertEquals(
            "Debauchery of a Mature Honeypot Princess Ch 2",
            Galleries.cleanTitle("(C93) [Aroma Gaeru (Numahana)] Jukumitsuki Intouden 2 | Debauchery of a Mature Honeypot Princess Ch 2 (Naruto) [English] {darknight} [Digital]"),
        )
        assertEquals("Sakura haruno", Galleries.cleanTitle("[Ero-enzo] Sakura haruno"))
        assertEquals("Affair Hidden in the Leaves #1-2", Galleries.cleanTitle("[RaikageArt] Affair Hidden in the Leaves #1-2 (Boruto) [English]"))
        // Nothing but brackets: keep the name as it is.
        assertEquals("[Untitled]", Galleries.cleanTitle("[Untitled]"))
    }

    @Test
    fun `timeouts adapt to measured latency`() {
        var now = 0L
        val h = ProviderHealth(InMemoryStore()) { now }
        assertEquals(20_000, h.timeoutFor("a", 20_000)) // no history: the maximum
        repeat(5) { h.recordSuccess("a", 600) }
        assertEquals(6_000, h.timeoutFor("a", 20_000)) // fast provider: floor
        repeat(20) { h.recordSuccess("b", 3_000) }
        assertTrue(h.timeoutFor("b", 20_000) in 15_000..17_500)
        h.recordFailure("a", ErrorKind.TIMEOUT)
        assertEquals(20_000, h.timeoutFor("a", 20_000)) // just timed out: be patient again
    }

    @Test
    fun `cool-down grows with consecutive failures`() {
        var now = 0L
        val h = ProviderHealth(InMemoryStore()) { now }
        repeat(3) { h.recordFailure("a", ErrorKind.TIMEOUT) }
        assertTrue(h.isCircuitOpen("a"))
        now += 3 * 60_000
        assertFalse(h.isCircuitOpen("a")) // 2 minutes after the third failure
        repeat(2) { h.recordFailure("a", ErrorKind.SERVER) }
        now += 7 * 60_000
        assertTrue(h.isCircuitOpen("a")) // 8 minutes after the fifth
    }

    @Test
    fun `only relevant providers are searched to link a series`() {
        val registry = SourceRegistry.defaults(ctx, ProviderHealth(null))
        val s = AggregatorSettings()
        val normal = registry.forLinking(s, adultSeries = false)
        assertTrue(normal.none { it.adult || it.role == ProviderRole.GALLERY })
        assertTrue(normal.any { it.id == MangaDexAdapter.ID })
        val adult = registry.forLinking(s, adultSeries = true)
        assertTrue(adult.any { it.id == Hentai20Adapter.ID })
        assertTrue(adult.none { it.role == ProviderRole.GALLERY })
        assertTrue(registry.forBrowsing(s).none { it.id == MangaDexAdapter.ID || it.adult })
        assertTrue(SeriesMetadata("mangabaka", title = "x", genres = listOf("Smut")).looksAdult)
        assertFalse(SeriesMetadata("mangabaka", title = "x", genres = listOf("Action")).looksAdult)
    }

    @Test
    fun `hitomi image urls follow common js`() {
        val hash = "ecd0ee0909dcfa3112a786485ff0e7538960404b9b971c687cacd9124bfd6cb7"
        // Directory = parseInt("7" + "cb", 16) = 1995; gg.m(1995) = 1 -> w2.
        assertEquals(
            "https://w2.gold-usergeneratedcontent.net/1793768402/1995/$hash.webp",
            HitomiAdapter(ctx).imageUrl(hash, { if (it == 1995) 1 else 0 }, "1793768402/"),
        )
    }

    @Test
    fun `hentai20 reader data is parsed`() {
        val html = """<script>ts_reader.run({"post_id":1,"sources":[{"source":"Server 1","images":["https:\/\/img.hentai1.io\/a\/chapter-1\/001.jpg","https:\/\/img.hentai1.io\/a\/chapter-1\/002.jpg"]}]});</script>"""
        assertEquals(listOf("https://img.hentai1.io/a/chapter-1/001.jpg", "https://img.hentai1.io/a/chapter-1/002.jpg"), Hentai20Adapter(ctx).parsePages(html))
    }
}
