package alexandria.core.quality

import alexandria.core.cache.InMemoryStore
import alexandria.core.config.AggregatorSettings
import alexandria.core.config.CoverProvider
import alexandria.core.config.QualityMode
import alexandria.core.covers.CoverResolver
import alexandria.core.model.CoverCandidate
import alexandria.core.model.CoverOrigin
import alexandria.core.model.ReaderPage
import alexandria.core.net.ErrorKind
import alexandria.core.ranking.CandidateEvidence
import alexandria.core.ranking.ChapterQualityAnalyzer
import alexandria.core.ranking.ProviderHealth
import alexandria.core.ranking.SourceRanker
import alexandria.core.reconciliation.Release
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun encodeImage(w: Int, h: Int, format: String): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    // Some texture so JPEGs are not trivially tiny.
    for (y in 0 until h step 3) for (x in 0 until w step 3) img.setRGB(x, y, (x * 31 + y * 17) and 0xFFFFFF)
    return ByteArrayOutputStream().also { ImageIO.write(img, format, it) }.toByteArray()
}

class ImageProbeTest {
    @Test
    fun `parses jpeg png gif headers`() {
        listOf("jpg" to "jpeg", "png" to "png", "gif" to "gif").forEach { (fmt, name) ->
            val info = ImageProbe.parse(encodeImage(321, 654, fmt))
            assertNotNull(info, fmt)
            assertEquals(321 to 654, info.width to info.height, fmt)
            assertEquals(name, info.format)
        }
    }

    @Test
    fun `parses webp vp8x header`() {
        // RIFF....WEBPVP8X + flags + 24-bit (w-1) + 24-bit (h-1)
        val b = ByteArray(32)
        "RIFF".toByteArray().copyInto(b, 0)
        "WEBPVP8X".toByteArray().copyInto(b, 8)
        val w = 1199; val h = 1799
        b[24] = (w and 0xFF).toByte(); b[25] = (w shr 8 and 0xFF).toByte(); b[26] = (w shr 16).toByte()
        b[27] = (h and 0xFF).toByte(); b[28] = (h shr 8 and 0xFF).toByte(); b[29] = (h shr 16).toByte()
        assertEquals(1200 to 1800, ImageProbe.parse(b)!!.let { it.width to it.height })
    }

    @Test
    fun `garbage is not an image`() {
        assertNull(ImageProbe.parse("<html>blocked</html>".toByteArray()))
    }

    @Test
    fun `probe uses range request and reports total size`() = runTest {
        val bytes = encodeImage(800, 1200, "jpg")
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-1023/${bytes.size}").body(Buffer().write(bytes.copyOf(minOf(bytes.size, 4096)))).build())
            server.start()
            val info = ImageProbe.probe(OkHttpClient(), server.url("/a.jpg").toString())!!
            assertEquals(800 to 1200, info.width to info.height)
            assertEquals(bytes.size.toLong(), info.bytes)
            assertTrue(server.takeRequest().headers["Range"]!!.startsWith("bytes=0-"))
        }
    }
}

class CoverResolverTest {
    private val settings = AggregatorSettings()

    private fun c(url: String, provider: String, origin: CoverOrigin, w: Int? = null, h: Int? = null, bytes: Long? = null, lang: String? = null) =
        CoverCandidate(url, provider, origin.name, w, h, bytes, lang)

    @Test
    fun `prefers large original over thumbnails`() = runTest {
        val resolver = CoverResolver(probe = { null })
        val ranked = resolver.rank(
            listOf(
                c("thumb", "weebcentral", CoverOrigin.PROVIDER_THUMBNAIL, 300, 450, 40_000),
                c("mid", "mangapill", CoverOrigin.PROVIDER_THUMBNAIL, 600, 900, 150_000),
                c("raw", "mangabaka", CoverOrigin.METADATA_ORIGINAL, 1400, 2000, 900_000),
            ),
            settings, "ja", "weebcentral",
        )
        assertEquals("raw", ranked.first().candidate.url)
        assertEquals("thumb", ranked.last().candidate.url)
    }

    @Test
    fun `upscaled-looking image loses to genuinely detailed one`() = runTest {
        val resolver = CoverResolver(probe = { null })
        val ranked = resolver.rank(
            listOf(
                // Same resolution class, but a suspiciously tiny file for its pixel count.
                c("upscaled", "x", CoverOrigin.PROVIDER_ORIGINAL, 1500, 2250, 60_000),
                c("detailed", "y", CoverOrigin.PROVIDER_ORIGINAL, 1400, 2100, 1_100_000),
            ),
            settings, null, null,
        )
        assertEquals("detailed", ranked.first().candidate.url)
    }

    @Test
    fun `unknown dimensions are probed and failures demoted`() = runTest {
        val resolver = CoverResolver(probe = { cand -> if (cand.url == "broken") throw java.io.IOException("404") else ImageInfo(1000, 1500, "jpeg", 500_000) })
        val ranked = resolver.rank(
            listOf(c("broken", "mangadex", CoverOrigin.PROVIDER_ORIGINAL), c("ok", "anilist", CoverOrigin.METADATA_LARGE)),
            settings, null, null,
        )
        assertEquals("ok", ranked.first().candidate.url)
    }

    @Test
    fun `translated edition covers discounted when preferring original artwork`() = runTest {
        val resolver = CoverResolver(probe = { null })
        val list = listOf(
            c("en", "mangadex", CoverOrigin.PROVIDER_ORIGINAL, 1000, 1500, 400_000, "en"),
            c("ja", "mangadex", CoverOrigin.PROVIDER_ORIGINAL, 1000, 1500, 400_000, "ja"),
        )
        assertEquals("ja", resolver.rank(list, settings, "ja", null).first().candidate.url)
    }

    @Test
    fun `disabled feature uses primary source cover`() = runTest {
        val resolver = CoverResolver(probe = { error("must not probe") })
        val ranked = resolver.rank(
            listOf(c("raw", "mangabaka", CoverOrigin.METADATA_ORIGINAL, 1400, 2000), c("primary", "weebcentral", CoverOrigin.PROVIDER_THUMBNAIL)),
            settings.copy(highQualityCovers = false), null, "weebcentral",
        )
        assertEquals("primary", ranked.first().candidate.url)
    }

    @Test
    fun `explicit cover provider wins`() = runTest {
        val resolver = CoverResolver(probe = { null })
        val ranked = resolver.rank(
            listOf(c("raw", "mangabaka", CoverOrigin.METADATA_ORIGINAL, 1400, 2000), c("al", "anilist", CoverOrigin.METADATA_LARGE, 460, 650)),
            settings.copy(coverProvider = CoverProvider.ANILIST), null, null,
        )
        assertEquals("al", ranked.first().candidate.url)
    }
}

class ChapterQualityTest {
    private val health = ProviderHealth(InMemoryStore())
    private val analyzer = ChapterQualityAnalyzer(SourceRanker(health), health)

    private fun rel(s: String) = Release(s, "x", "$s-1", "Chapter 1", "en")
    private fun pages(n: Int, prefix: String = "p") = (0 until n).map { ReaderPage(it, "https://img/$prefix$it.jpg") }

    @Test
    fun `complete chapter beats incomplete higher-resolution chapter`() {
        val ev = listOf(
            CandidateEvidence(rel("a"), pages(12, "a"), listOf(ImageInfo(1600, 2400, "jpeg", 900_000)), firstPageReachable = true),
            CandidateEvidence(rel("b"), pages(20, "b"), listOf(ImageInfo(800, 1200, "jpeg", 200_000)), firstPageReachable = true),
        )
        assertEquals("b", analyzer.evaluate(ev, AggregatorSettings()).first().release.sourceId)
    }

    @Test
    fun `higher resolution wins when both are complete`() {
        val ev = listOf(
            CandidateEvidence(rel("a"), pages(20, "a"), listOf(ImageInfo(720, 1000, "jpeg", 150_000)), firstPageReachable = true),
            CandidateEvidence(rel("b"), pages(20, "b"), listOf(ImageInfo(1400, 2000, "jpeg", 700_000)), firstPageReachable = true),
        )
        assertEquals("b", analyzer.evaluate(ev, AggregatorSettings()).first().release.sourceId)
    }

    @Test
    fun `data saver prefers smaller files`() {
        val ev = listOf(
            CandidateEvidence(rel("a"), pages(20, "a"), listOf(ImageInfo(720, 1000, "jpeg", 120_000)), firstPageReachable = true),
            CandidateEvidence(rel("b"), pages(20, "b"), listOf(ImageInfo(1400, 2000, "jpeg", 1_500_000)), firstPageReachable = true),
        )
        assertEquals("a", analyzer.evaluate(ev, AggregatorSettings(qualityMode = QualityMode.DATA_SAVER)).first().release.sourceId)
    }

    @Test
    fun `duplicate pages and broken first page are penalised`() {
        val dup = (0 until 20).map { ReaderPage(it, "https://img/same.jpg") }
        val ev = listOf(
            CandidateEvidence(rel("dup"), dup, emptyList()),
            CandidateEvidence(rel("broken"), pages(20, "x"), listOf(null), firstPageReachable = false),
            CandidateEvidence(rel("good"), pages(20, "g"), listOf(ImageInfo(900, 1300, "jpeg", 300_000)), firstPageReachable = true),
        )
        assertEquals("good", analyzer.evaluate(ev, AggregatorSettings()).first().release.sourceId)
    }

    @Test
    fun `unavailable candidates are excluded`() {
        val ev = listOf(
            CandidateEvidence(rel("down"), null, emptyList(), error = java.io.IOException("timeout")),
            CandidateEvidence(rel("empty"), emptyList(), emptyList()),
            CandidateEvidence(rel("up"), pages(3), emptyList()),
        )
        assertEquals(listOf("up"), analyzer.evaluate(ev, AggregatorSettings()).map { it.release.sourceId })
    }

    @Test
    fun `ranker honours preference, language and health`() {
        val h = ProviderHealth(InMemoryStore())
        repeat(6) { h.recordFailure("flaky", ErrorKind.SERVER) }
        val ranker = SourceRanker(h)
        val s = AggregatorSettings(languages = listOf("en", "es"), sourceOrder = listOf("flaky", "solid"))
        val spanish = Release("solid", "x", "solid-es", "Ch 1", "es")
        val ranked = ranker.rank(listOf(rel("flaky"), rel("solid"), spanish), s)
        // Healthy source first despite user order; the secondary language ranks below the primary one.
        assertEquals("solid", ranked.first().sourceId)
        assertEquals("en", ranked.first().language)
        assertTrue(ranked.indexOf(spanish) > ranked.indexOf(rel("solid")))
        assertEquals("flaky", ranker.rank(listOf(rel("flaky"), rel("solid")), s.copy(preferredSource = "flaky")).first().sourceId)
    }
}
