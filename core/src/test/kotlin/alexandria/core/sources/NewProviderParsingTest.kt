package alexandria.core.sources

import alexandria.core.model.SourceSeries
import alexandria.core.normalization.ChapterLabelParser
import alexandria.core.sources.adapters.AsuraScansAdapter
import alexandria.core.sources.adapters.MangaTownAdapter
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
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
        assertEquals(
            setOf("loa_asurascans", "loa_mangatown", "loa_mangafire", "loa_mangataro", "loa_flamecomics", "loa_mangaball"),
            ids.filter { it.startsWith("loa_") }.toSet(),
        )
    }

    // ------------------------------------------------------------ 1.6.8 providers

    @Test
    fun `mangafire signs requests exactly like the site expects`() {
        // Vectors from an independent implementation of the same scheme that the live API accepted (Nov 4 2026).
        assertEquals("8sK3xtqdFZfetBhus6bRAtoycP0b29wZ7blBfRyWj_IRKcufQ7Mxx9tZdzF55rJ7wlrnOs4a8WJojJlI",
            alexandria.core.sources.adapters.MangaFireAdapter.sign("/titles?keyword=solo leveling&languages[0]=en&limit=5&page=1"))
        val url = alexandria.core.sources.adapters.MangaFireAdapter(ctx).signedUrl("/titles", listOf("page" to "1", "keyword" to "solo leveling", "limit" to "5", "languages[]" to "en"))
        assertEquals("8sK3xtqdFZfetBhus6bRAtoycP0b29wZ7blBfRyWj_IRKcufQ7Mxx9tZdzF55rJ7wlrnOs4a8WJojJlI", url.queryParameter("vrf"))
        assertEquals("8vPRXa1JjvVTxngXIb2X76U", alexandria.core.sources.adapters.MangaFireAdapter.sign("/chapters/8112960"))
    }

    @Test
    fun `mangafire parses titles, details and chapters`() {
        val a = alexandria.core.sources.adapters.MangaFireAdapter(ctx)
        val hits = a.parseTitles("""{"items":[{"hid":"lrmyz","slug":"solo-leveling","title":"Solo Leveling","type":"manhwa","status":"finished","year":2018,
            "poster":{"small":"https://static.mfcdn.nl/a@100.jpg","medium":"https://static.mfcdn.nl/a@280.jpg","large":"https://static.mfcdn.nl/a.jpg"}},
            {"hid":"nv1","title":"Solo Leveling","type":"novel"}],"meta":{"lastPage":1}}""")
        assertEquals(listOf("lrmyz"), hits.map { it.id }, "novels are left out")
        assertEquals("https://static.mfcdn.nl/a.jpg", hits[0].coverUrl, "the original-size poster")
        assertEquals(2018, hits[0].year)
        val d = a.parseDetails("""{"data":{"hid":"lrmyz","title":"Solo Leveling","year":"2018","synopsisHtml":"<p>Hunter</p>","altTitles":["Na Honjaman Level Up"],
            "authors":[{"title":"Chugong"}],"artists":[{"title":"DUBU"}],"genres":[{"title":"Action"}],"links":{"mu":"https://mangaupdates.com/series/6z1uqw7/"},"anilistId":"105398","malId":""}}""")
        assertEquals(listOf("Chugong", "DUBU"), d.authors)
        assertEquals("6z1uqw7", d.ids.mangaUpdates)
        assertEquals(105398L, d.ids.anilist)
        assertEquals("Hunter", d.description)
        val ch = a.parseChapters("lrmyz", listOf(
            alexandria.core.sources.adapters.MangaFireAdapter.Chapter(1, 200.0, "", "en", "official", 1_700_000_000),
            alexandria.core.sources.adapters.MangaFireAdapter.Chapter(2, 200.0, "Side Story 21", "en", "unofficial"),
            alexandria.core.sources.adapters.MangaFireAdapter.Chapter(3, 10.5, "The Gate", "en", "unofficial"),
            alexandria.core.sources.adapters.MangaFireAdapter.Chapter(4, 9.0, "", "fr", "unofficial"),
        ))
        assertEquals(listOf("Chapter 200", "Side Story 21", "Chapter 10.5: The Gate"), ch.map { it.label })
        assertEquals(listOf("200", null, "10.5"), ch.map { it.number })
        assertEquals(ChapterLabelParser.parse("Side Story 21").identity.kind, alexandria.core.normalization.ChapterKind.SIDE_STORY)
        assertEquals(true, ch[0].official)
        assertEquals(1_700_000_000_000L, ch[0].uploadedAt)
    }

    @Test
    fun `mangataro parses search with authors, chapters and pages`() {
        val now = java.time.Instant.parse("2026-11-04T17:30:00Z").toEpochMilli()
        val a = alexandria.core.sources.adapters.MangaTaroAdapter(ctx, clock = { now })
        val hits = a.parseSearch("""{"results":[{"id":9066,"slug":"solo-leveling","title":"Solo Leveling","thumbnail":"https://mangataro.org/c.webp","type":"Manhwa",
            "description":"Hunter &amp; gates","status":"Completed","alt_titles":["Na Honjaman Level Up"],"authors":["Chugong"]},
            {"id":1,"slug":"n","title":"Solo Leveling","type":"Novel","authors":[]}]}""")
        assertEquals(listOf("9066/solo-leveling"), hits.map { it.id })
        assertEquals(listOf("Chugong"), hits[0].authors)
        assertTrue(hits[0].detailed, "search already carries what matching needs")
        assertEquals("Hunter & gates", hits[0].description)
        assertEquals("https://mangataro.org/manga/solo-leveling", a.seriesWebUrl(hits[0].id))
        val ch = a.parseChapters("9066/solo-leveling", listOf(
            alexandria.core.sources.adapters.MangaTaroAdapter.Chapter(url = "https://mangataro.org/read/solo-leveling/ch200-9355", chapter = "200", title = "Side Story 21 [END]"),
            alexandria.core.sources.adapters.MangaTaroAdapter.Chapter(url = "https://mangataro.org/read/solo-leveling/ch1-11", chapter = "1", title = "N/A", groupName = "—"),
            alexandria.core.sources.adapters.MangaTaroAdapter.Chapter(url = "https://mangataro.org/read/x/ch1-12", chapter = "1", language = "es"),
        ))
        assertEquals(listOf("9355", "11"), ch.map { it.id })
        assertEquals(listOf("Chapter 200: Side Story 21 [END]", "Chapter 1"), ch.map { it.label })
        assertEquals(null, ch[1].group)
        // Token: md5("<epoch>mng_ch_<yyyyMMddHH UTC>"), first 16 hex digits, with the epoch alongside.
        val url = a.chapterUrl("9066", 0)
        val epoch = now / 1000
        assertTrue("_ts=$epoch" in url)
        assertTrue("_t=" + alexandria.core.sources.adapters.MangaTaroAdapter.md5("${epoch}mng_ch_2026110417").substring(0, 16) in url, url)
        assertEquals(2, a.parsePages("""{"images":["https://mangataro.yachts/1.webp","https://mangataro.yachts/2.webp"]}""").size)
    }

    @Test
    fun `manga ball lists only english chapters on the mirror that loads without a browser challenge`() {
        val a = alexandria.core.sources.adapters.MangaBallAdapter(ctx)
        val ch = a.parseChapters("t1", """{"data":[
            {"id":"a","name":"Chapter 202","number":202,"volume":0,"lang":"en","storage":"cdn_2","status":"published","group_name":"Suicune","created_at":"2025-10-02T13:07:48.357000"},
            {"id":"b","name":"","number":201,"volume":0,"lang":"en","storage":"cdn_1"},
            {"id":"c","name":"Kapitel 5","number":5,"volume":1,"lang":"de","storage":"cdn_2"},
            {"id":"d","name":"The End","number":203.5,"volume":2,"lang":"en","storage":"cdn_2"}]}""")
        assertEquals(listOf("a", "d"), ch.map { it.id })
        assertEquals(listOf("Chapter 202", "Chapter 203.5: The End"), ch.map { it.label })
        assertEquals("2", ch[1].volume)
        assertEquals("Suicune", ch[0].group)
        val d = a.parseDetails("""{"data":{"id":"t1","name":"Solo Leveling","alternateName":["Only I Level Up"],"author":[{"name":"Chugong (추공)"}],"status":"completed",
            "image":{"cdn_mangadex":"https://uploads.mangadex.org/c.jpg","cover":{"path":"t1\\c.jpg"}},"links":{"mangadex":"32d76d19-8a05-4db0-9fc2-e0b0648fe9d0","mangaUpdate":"151349"}}}""")
        assertEquals("32d76d19-8a05-4db0-9fc2-e0b0648fe9d0", d.ids.mangadex)
        assertEquals(null, d.coverUrl, "no cover from the challenged host or MangaDex's CDN")
        assertEquals(alexandria.core.model.PublicationStatus.COMPLETED, d.status)
    }

    @Test
    fun `flame comics parses its series list, chapters and image urls`() {
        val a = alexandria.core.sources.adapters.FlameComicsAdapter(ctx)
        val list = a.parseBrowse("""{"pageProps":{"series":[{"title":"Omniscient Reader's Viewpoint","altTitles":"[\"전지적 독자 시점\"]","cover":"thumbnail.webp","type":"Manhwa",
            "author":["Sing-Shong"],"artist":["Sleepy-C"],"status":"Ongoing","series_id":2,"last_edit":17}, {"title":"No id","status":"Ongoing"}]}}""")
        assertEquals(listOf("2"), list.map { it.id })
        assertEquals("https://cdn.flamecomics.xyz/uploads/images/series/2/thumbnail.webp?17", list[0].coverUrl)
        assertEquals(listOf("전지적 독자 시점"), list[0].altTitles)
        assertEquals(listOf("Sing-Shong", "Sleepy-C"), list[0].authors)
        val (s, ch) = a.parseSeriesPage("""{"pageProps":{"series":{"title":"ORV","series_id":2,"status":"Ongoing","author":["Sing-Shong"]},
            "chapters":[{"chapter":"22.00","title":"Lesson (4)","release_date":1793279454,"series_id":2,"token":"d8eb"},{"chapter":"21.50","series_id":2,"token":"aa11"}]}}""")
        assertEquals("2", s.id)
        assertEquals(listOf("Chapter 22: Lesson (4)", "Chapter 21.5"), ch.map { it.label })
        assertEquals(listOf("22", "21.5"), ch.map { it.number })
        val pages = a.parsePages("""{"pageProps":{"chapter":{"release_date":5,"series_id":2,"token":"d8eb","images":{"0":{"name":"01.webp"},"1":{"name":"02.webp"}}}}}""")
        assertEquals("https://cdn.flamecomics.xyz/uploads/images/series/2/d8eb/01.webp?5", pages[0].imageUrl)
        assertEquals("https://flamecomics.xyz/", pages[0].headers["Referer"])
    }

    @Test
    fun `mangafire refreshes read page 1 only, with a full read once a day`() = kotlinx.coroutines.test.runTest {
        var now = 1_000_000_000L
        var newest = 450
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val page = chain.request().url.queryParameter("page")!!.toInt()
            // 200 per page, numbers descending from [newest].
            val nums = (newest downTo 1).toList().chunked(200).getOrElse(page - 1) { emptyList() }
            val items = nums.joinToString(",") { """{"id":$it,"number":$it,"name":"","language":"en","type":"unofficial"}""" }
            val more = page * 200 < newest
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(okhttp3.ResponseBody.Companion.run { """{"items":[$items],"meta":{"lastPage":3,"hasNext":$more}}""".toResponseBody(null) }).build()
        }.build()
        val a = alexandria.core.sources.adapters.MangaFireAdapter(AdapterContext(client), clock = { now })
        val s = alexandria.core.model.SourceSeries("loa_mangafire", "abc", "X")
        assertEquals(450, a.chapters(s, listOf("en")).size)
        assertEquals(3, calls.get(), "first read: every page")
        newest = 452
        now += 60 * 60_000L
        val second = a.chapters(s, listOf("en"))
        assertEquals(452, second.size, "new chapters merged with the known list")
        assertEquals(4, calls.get(), "a refresh reads page 1 only")
        now += 25 * 60 * 60_000L
        a.chapters(s, listOf("en"))
        assertEquals(7, calls.get(), "a full read once a day")
    }
}
