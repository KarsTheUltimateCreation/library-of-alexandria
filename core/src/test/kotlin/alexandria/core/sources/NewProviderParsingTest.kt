package alexandria.core.sources

import alexandria.core.model.SourceSeries
import alexandria.core.normalization.ChapterLabelParser
import alexandria.core.sources.adapters.AsuraScansAdapter
import alexandria.core.sources.adapters.MangaTownAdapter
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Hand-written snippets that mimic the providers' structure (no copied pages). */
class NewProviderParsingTest {
    private val ctx = AdapterContext(OkHttpClient())

    @Test
    fun `asura skips locked chapters and keeps numbers and titles`() {
        val now = java.time.Instant.parse("2026-11-04T00:00:00Z").toEpochMilli()
        val a = AsuraScansAdapter(ctx, clock = { now })
        val list = a.parseChapters(
            "overgeared",
            listOf(
                AsuraScansAdapter.ChapterInfo(2.0, "Second", 20, false, null, "2025-01-02T00:00:00Z"),
                AsuraScansAdapter.ChapterInfo(1.5, null, 10, false, "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z"),
                AsuraScansAdapter.ChapterInfo(3.0, null, 0, true, null, null), // premium
                AsuraScansAdapter.ChapterInfo(4.0, null, 0, false, "2026-11-05T00:00:00Z", null), // early access
            ),
        )
        assertEquals(listOf("1.5", "2"), list.map { it.number })
        assertEquals("overgeared/2", list.last().id)
        assertEquals("Chapter 2 - Second", list.last().label)
        assertEquals(20, list.last().pageCount)
        assertEquals("2", ChapterLabelParser.parse(list.last().label, list.last().number, null).identity.number.toString())
    }

    @Test
    fun `asura reads astro page props and refuses scrambled pages`() {
        val a = AsuraScansAdapter(ctx)
        fun page(tiles: String) = """<astro-island props="{&quot;seriesSlug&quot;:[0,&quot;x&quot;],&quot;pages&quot;:[1,[[0,{&quot;url&quot;:[0,&quot;https://cdn.asurascans.com/a/1.webp&quot;]$tiles}],[0,{&quot;url&quot;:[0,&quot;https://cdn.asurascans.com/a/2.webp&quot;]}]]]}"></astro-island>"""
        val pages = a.parsePages(page(",&quot;tiles&quot;:[0,null]"))
        assertEquals(listOf("https://cdn.asurascans.com/a/1.webp", "https://cdn.asurascans.com/a/2.webp"), pages.map { it.imageUrl })
        assertEquals("https://asurascans.com/", pages.first().headers["Referer"])
        assertFailsWith<UnsupportedOperationException> { a.parsePages(page(",&quot;tiles&quot;:[1,[[0,3],[0,1]]]")) }
        assertFailsWith<IllegalStateException> { a.parsePages("<html><body>locked</body></html>") }
    }

    @Test
    fun `mangatown takes numbers and volumes from chapter urls`() {
        val m = MangaTownAdapter(ctx)
        val html = """
            <ul class="chapter_list">
              <li><a href="/manga/berserk/c386/">Berserk 386</a><span class="time">Jul 10,2026</span><span class="new">new</span></li>
              <li><a href="/manga/berserk/v02/c010.5/">Berserk 10.5</a><span>Extra Story</span><span class="time">Today</span></li>
              <li><a href="/manga/berserk/v01/c001/">Berserk 1</a><span class="time">Jan 01,2010</span></li>
              <li><a href="/manga/other/c001/">elsewhere</a></li>
            </ul>"""
        val list = m.parseChapters("berserk", Jsoup.parse(html, "https://www.mangatown.com/manga/berserk/"))
        assertEquals(listOf("1", "10.5", "386"), list.map { it.number })
        assertEquals(listOf("1", "2", null), list.map { it.volume })
        assertEquals("berserk/v02/c010.5", list[1].id)
        assertEquals("Chapter 10.5 - Extra Story", list[1].label)
        assertTrue(list.first().uploadedAt!! < list.last().uploadedAt!!)
    }

    @Test
    fun `mangatown search, details and pages`() {
        val m = MangaTownAdapter(ctx)
        val search = """<ul class="manga_pic_list"><li><a class="manga_cover" href="/manga/berserk/"><img src="https://fmcdn.mangahere.com/c.jpg"></a>
            <p class="title"><a href="/manga/berserk/" title="Berserk"> Berserk </a></p></li>
            <li><a class="manga_cover" href="/manga/berserk_of_gluttony/"></a><p class="title"><a href="/manga/berserk_of_gluttony/" title="Berserk of Gluttony">x</a></p></li></ul>"""
        val hits = m.parseList(Jsoup.parse(search, "https://www.mangatown.com/search"))
        assertEquals(listOf("berserk", "berserk_of_gluttony"), hits.map { it.id })
        assertEquals("Berserk", hits.first().title)

        val details = """<div class="article_content"><h1 class="title-top">Berserk</h1><div class="detail_info"><img src="https://fmcdn.mangahere.com/o.jpg">
            <ul><li><b>Alternative Name:</b>ベルセルク;Berserk Prototype</li><li><b>Genre(s):</b><a>Action</a>,<a>Horror</a></li>
            <li><b>Author(s):</b><a>Miura Kentarou</a></li><li><b>Status(s):</b>Ongoing</li></ul></div></div><span id="show">A story. HIDE</span>"""
        val d = m.parseDetails(SourceSeries(m.id, "berserk", "Berserk"), Jsoup.parse(details, "https://www.mangatown.com/manga/berserk/"))
        assertEquals(listOf("ベルセルク", "Berserk Prototype"), d.altTitles)
        assertEquals(listOf("Action", "Horror"), d.genres)
        assertEquals("A story.", d.description)
        assertTrue(d.detailed)

        val paged = """<div class="page_select"><select><option value="/manga/berserk/c386/">01</option><option value="/manga/berserk/c386/2.html">02</option>
            <option value="/manga/berserk/c386/featured.html">featured</option></select></div>"""
        val pages = m.parsePages(Jsoup.parse(paged, "https://www.mangatown.com/manga/berserk/c386/"))
        assertEquals(listOf("https://www.mangatown.com/manga/berserk/c386/", "https://www.mangatown.com/manga/berserk/c386/2.html"), pages.map { it.imageUrl })
        assertTrue(pages.all { it.lazy })
        val strip = m.parsePages(Jsoup.parse("""<div id="viewer"><img src="//zjcdn.mangahere.org/s/1.jpg"><img src="//zjcdn.mangahere.org/s/2.jpg"></div>""", "https://www.mangatown.com/manga/x/c1/"))
        assertEquals("https://zjcdn.mangahere.org/s/1.jpg", strip.first().imageUrl)
        assertEquals("https://www.mangatown.com/", strip.first().headers["Referer"])
    }

    @Test
    fun `new provider ids are namespaced and existing ids are unchanged`() {
        val ids = SourceRegistry.defaults(ctx, alexandria.core.ranking.ProviderHealth(null)).all.map { it.id }
        assertEquals(
            listOf("mangadex", "weebcentral", "mangapill", "mangakatana", "mangaread", "mgeko", "atsumaru"),
            ids.take(7), "ids persisted in users' libraries must not change",
        )
        assertTrue("loa_asurascans" in ids && "loa_mangatown" in ids)
        assertTrue(ids.filter { it.startsWith("loa_") }.size == 2)
    }
}
