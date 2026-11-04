package alexandria.core.covers

import alexandria.core.config.AggregatorSettings
import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.quality.ImageInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverSelectionTest {
    private val s = AggregatorSettings()
    private fun c(url: String, origin: CoverOrigin = CoverOrigin.PROVIDER_THUMBNAIL) = CoverCandidate(url, "p", origin.name)

    @Test
    fun `the highest resolution cover wins when every candidate is measured`() = runTest {
        val sizes = mapOf(
            "a" to ImageInfo(400, 600, "webp", 50_000), "b" to ImageInfo(1500, 2250, "jpg", 900_000), "c" to ImageInfo(200, 300, "webp", 15_000),
            "d" to ImageInfo(800, 1200, "jpg", 300_000), "e" to ImageInfo(600, 900, "jpg", 120_000),
        )
        val probes = AtomicInteger()
        val r = CoverResolver(probe = { probes.incrementAndGet(); sizes[it.url] })
        // Even the candidate listed last is measured.
        val ranked = r.rank(listOf("a", "c", "d", "e", "b").map { c(it) }, s, null, null)
        assertEquals("b", ranked.first().candidate.url)
        assertEquals(5, probes.get())
    }

    @Test
    fun `measuring runs in parallel under one budget and a slow probe is unknown, not broken`() = runTest {
        val r = CoverResolver(
            probe = { if (it.url == "slow") { delay(60_000); null } else { delay(500); ImageInfo(1000, 1500, "jpg", 400_000) } },
            budgetMs = 2_000,
        )
        val start = testScheduler.currentTime
        val ranked = r.rank(listOf(c("slow", CoverOrigin.PROVIDER_ORIGINAL), c("ok1"), c("ok2")), s, null, null)
        assertTrue(testScheduler.currentTime - start <= 2_000, "bounded by the budget, not by the slowest probe")
        assertTrue("probe failed" !in ranked.first { it.candidate.url == "slow" }.notes, "not treated as unreachable")
        assertEquals(1000, ranked.first { it.candidate.url == "ok1" }.candidate.width, "the others were measured in parallel")
    }

    @Test
    fun `sizes measured once are reused without a request`() = runTest {
        val memory = ConcurrentHashMap<String, ImageInfo>()
        val probes = AtomicInteger()
        val r = CoverResolver(probe = { probes.incrementAndGet(); ImageInfo(900, 1350, "jpg", 300_000) }, known = { memory[it] }, remember = { u, i -> memory[u] = i })
        r.rank(listOf(c("x"), c("y")), s, null, null)
        assertEquals(2, probes.get())
        val again = r.rank(listOf(c("x"), c("y")), s, null, null)
        assertEquals(2, probes.get(), "no new request")
        assertEquals(900, again.first().candidate.width)
    }

    @Test
    fun `an unreachable cover is ranked below a reachable one`() = runTest {
        val r = CoverResolver(probe = { if (it.url == "dead") throw java.io.IOException("404") else ImageInfo(400, 600, "jpg", 60_000) })
        assertEquals("alive", r.rank(listOf(c("dead", CoverOrigin.PROVIDER_ORIGINAL), c("alive")), s, null, null).first().candidate.url)
    }
}
