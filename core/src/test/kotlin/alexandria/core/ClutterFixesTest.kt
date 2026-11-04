package alexandria.core

import alexandria.core.config.AggregatorSettings
import alexandria.core.model.SourceChapter
import alexandria.core.normalization.ChapterKind
import alexandria.core.normalization.ChapterLabelParser
import alexandria.core.normalization.SearchRelevance
import alexandria.core.reconciliation.ChapterReconciler
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.adapters.HiperdexAdapter
import alexandria.core.sources.adapters.ManhwaReadAdapter
import okhttp3.OkHttpClient
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regressions from real chapter lists and searches (1.6.4). */
class ClutterFixesTest {

    private fun parse(label: String) = ChapterLabelParser.parse(label).identity

    @Test
    fun `season mentioned in a title or note is not a season`() {
        // MangaKatana, "A Regressor's Tale of Cultivation".
        assertNull(parse("Chapter 25: Refining (4) - Season 1 End").season)
        assertNull(parse("Chapter 26 (S2 Start)").season)
        assertEquals("c:26", parse("Chapter 26 (S2 Start)").key())
        // Real season prefixes still count.
        assertEquals(2, parse("Season 2 Chapter 10").season)
        assertEquals(2, parse("S2 Ep 5").season)
        assertEquals(3, parse("S3E12").season)
    }

    @Test
    fun `kind words in a chapter title do not change the kind`() {
        // MangaKatana, "The God of High School": these are chapters 565-568, not side stories.
        assertEquals("c:568", parse("Chapter 568: Ep. 568 (Spin-off #4)").key())
        assertEquals(ChapterKind.MAIN, parse("Chapter 565: Ep. 565 (Spin-off #1)").kind)
        // A kind tag right after the number (no title separator) still marks the kind.
        assertEquals(ChapterKind.EXTRA, parse("Chapter 10 (Extra)").kind)
        assertEquals(ChapterKind.SIDE_STORY, parse("Side Story 3").kind)
        // Atsumaru, "A Regressor's Tale of Cultivation": chapter 1 is the prologue.
        assertEquals("c:1", parse("Chapter 1 Prologue - 0th Regression").key())
        assertEquals(ChapterKind.PROLOGUE, parse("Prologue").kind)
        assertEquals(ChapterKind.EPILOGUE, parse("Epilogue 2").kind)
    }

    private fun ch(source: String, label: String) = SourceChapter(source, "s-$source", "$source-$label", label)

    @Test
    fun `seasons that continue the numbering merge with plain chapters`() {
        val a = (1..25).map { ch("a", "S1 Chapter $it") } + (26..36).map { ch("a", "S2 Chapter $it") }
        val b = (1..36).map { ch("b", "Chapter $it") }
        val r = ChapterReconciler().reconcile(mapOf("a" to a, "b" to b), AggregatorSettings())
        assertEquals((1..36).map { "en:c:$it" }, r.chapters.map { it.key })
        assertTrue(r.chapters.all { it.sources.toSet() == setOf("a", "b") })
    }

    @Test
    fun `seasons that restart the numbering stay separate`() {
        val a = (1..10).map { ch("a", "Season 1 Chapter $it") } + (1..5).map { ch("a", "Season 2 Chapter $it") }
        val r = ChapterReconciler().reconcile(mapOf("a" to a), AggregatorSettings())
        assertEquals(15, r.chapters.size)
        assertEquals("en:s2:c:1", r.chapters[10].key)
    }

    @Test
    fun `search relevance puts the exact title first and drops fuzzy provider hits`() {
        val q = SearchRelevance.cleanQuery("don’t tell mom")
        assertEquals("don't tell mom", q)
        val exact = SearchRelevance.score(q, "Don't Tell Mom!")
        val fuzzy = listOf(
            "Don't Cry, Girl♥", "Don Dracula", "Zig Zag Don", "I Don't Told the Teacher Yet.", "Mom, Please Don't Come Adventuring with Me!",
            "Don't Tell Me You Think Cultivating Immortality Is Difficult?", "Ijiranaide, Nagatoro-san",
        )
        fuzzy.forEach { t ->
            val s = SearchRelevance.score(q, t)
            assertTrue(s < exact, "'$t' ($s) should rank below the exact title ($exact)")
        }
        assertTrue(exact >= 0.99)
        listOf("Don Dracula", "Zig Zag Don", "Don't Cry, Girl♥", "I Don't Told the Teacher Yet.").forEach {
            assertTrue(SearchRelevance.score(q, it) < SearchRelevance.PROVIDER_HIT_MIN, "'$it' should be dropped")
        }
        // Titles containing every query word are kept.
        assertTrue(SearchRelevance.score("solo leveling", "Solo Leveling: Ragnarok") >= SearchRelevance.PROVIDER_HIT_MIN)
        assertTrue(SearchRelevance.score("solo leveling", "Solo Leveling") > SearchRelevance.score("solo leveling", "Solo Leveling: Ragnarok"))
    }

    @Test
    fun `hiperdex pages follow the chapter directory numbering`() {
        val html = """<script type="application/ld+json">{"image":["https://i1.r2d2storage.com/manga_6129/25eb/01.webp",""" +
            """"https://i1.r2d2storage.com/manga_6129/25eb/02.webp","https://i1.r2d2storage.com/manga_6129/25eb/03.webp"]}</script>""" +
            """<img src="https://cloud-7.r2d2storage.com/covers/x/cover.webp">"""
        val pages = HiperdexAdapter.pagesFromHtml(html, 37)
        assertEquals(37, pages.size)
        assertEquals("https://i1.r2d2storage.com/manga_6129/25eb/01.webp", pages.first())
        assertEquals("https://i1.r2d2storage.com/manga_6129/25eb/37.webp", pages.last())
        assertEquals(3, HiperdexAdapter.pagesFromHtml(html, 0).size)
        assertEquals("100.5", HiperdexAdapter.formatNumber(100.5))
        assertEquals("7", HiperdexAdapter.formatNumber(7.0))
    }

    @Test
    fun `manhwaread chapter data decodes to page urls`() {
        val data = Base64.getEncoder().encodeToString("""[{"src":"ch1/001.jpg"},{"src":"ch1/002.jpg"}]""".toByteArray())
        val html = """<script>var chapterData = {"data":"$data","base":"https://cdn.example/m/"};</script>"""
        val adapter = ManhwaReadAdapter(AdapterContext(OkHttpClient()))
        assertEquals(listOf("https://cdn.example/m/ch1/001.jpg", "https://cdn.example/m/ch1/002.jpg"), adapter.parsePages(html))
    }
}
