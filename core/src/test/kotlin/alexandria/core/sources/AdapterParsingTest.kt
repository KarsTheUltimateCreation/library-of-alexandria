package alexandria.core.sources

import alexandria.core.model.ContentKind
import alexandria.core.model.PublicationStatus
import alexandria.core.sources.adapters.AtsumaruAdapter
import alexandria.core.sources.adapters.MangaKatanaAdapter
import alexandria.core.sources.adapters.MangaReadAdapter
import alexandria.core.sources.adapters.MgekoAdapter
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Offline parsing tests against small hand-written pages that mimic each provider's markup. */
class AdapterParsingTest {

    private fun serve(routes: Map<String, String>, block: suspend (MockWebServer) -> Unit) = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val target = request.target
                    val body = routes.entries.firstOrNull { target.startsWith(it.key) }?.value
                        ?: return MockResponse.Builder().code(404).build()
                    return MockResponse.Builder().code(200).body(body.replace("{BASE}", server.url("/").toString().trimEnd('/'))).build()
                }
            }
            server.start()
            block(server)
        }
    }

    private val ctx = AdapterContext(OkHttpClient())

    @Test
    fun `mangakatana search details chapters and pages`() = serve(
        mapOf(
            "/?search=" to """
                <div id="book_list">
                  <div class="item" data-id="49">
                    <div class="media"><div class="wrap_img"><a href="{BASE}/manga/sample-series.49"><img src="{BASE}/imgs/cover/a.jpg"></a></div>
                    <div class="status ongoing">Ongoing</div></div>
                    <div class="text"><h3 class="title"><a href="{BASE}/manga/sample-series.49">Sample Series</a><span> - Update chapter 12</span></h3>
                    <div class="genres"><a href="#">Action</a><a href="#">Webtoon</a></div></div>
                  </div>
                  <div class="item" data-id="50"><div class="text"><h3 class="title"><a href="{BASE}/genre/x">Not a series</a></h3></div></div>
                </div>""",
            "/manga/sample-series.49/c" to """
                <div id="imgs"></div>
                <script>var decoy=['{BASE}/token/x/0.jpg'];
                var real=['{BASE}/token/a/0.jpg','{BASE}/token/b/1.jpg',];
                for (var i = 0; i < real.length; i++) { el.setAttribute('data-src', real[i]); }</script>""",
            "/manga/sample-series.49" to """
                <div id="single_book"><div class="d-cell-medium media"><div class="cover"><img src="{BASE}/imgs/cover/full.jpg"></div></div>
                <div class="info"><h1 class="heading">Sample Series</h1>
                <div class="alt_name">サンプル ; Sample, Alternative ; 샘플</div>
                <div class="value authors"><a class="author" href="#">Some Author</a></div>
                <div class="genres"><a href="#">Action</a><a href="#">Manhwa</a></div>
                <div class="d-cell-small value status completed">Completed</div></div>
                <div class="summary"><div class="label">Description</div><p>A description.</p></div>
                <div class="chapters"><table><tbody>
                  <tr><td><div class="chapter"><a href="{BASE}/manga/sample-series.49/c12">Chapter 12: The End</a></div></td><td><div class="update_time">Oct-30-2026</div></td></tr>
                  <tr><td><div class="chapter"><a href="{BASE}/manga/sample-series.49/c11.5">Chapter 11.5</a></div></td><td><div class="update_time">Jan-02-2026</div></td></tr>
                </tbody></table></div></div>""",
        ),
    ) { server ->
        val adapter = MangaKatanaAdapter(ctx, server.url("/").toString().trimEnd('/'))
        val hits = adapter.search("sample")
        assertEquals(listOf("sample-series.49"), hits.map { it.id })
        assertEquals("Sample Series", hits[0].title)
        assertEquals(PublicationStatus.ONGOING, hits[0].status)
        assertEquals(ContentKind.MANHWA, hits[0].kind)

        val d = adapter.details(hits[0])
        // Alt names are ';'-separated; a comma inside one title must not split it.
        assertEquals(listOf("サンプル", "Sample, Alternative", "샘플"), d.altTitles)
        assertEquals(listOf("Some Author"), d.authors)
        assertEquals(PublicationStatus.COMPLETED, d.status)
        assertTrue(d.coverUrl!!.endsWith("/imgs/cover/full.jpg"))
        assertEquals("A description.", d.description)

        val chapters = adapter.chapters(d, listOf("en"))
        assertEquals(listOf("sample-series.49/c12", "sample-series.49/c11.5"), chapters.map { it.id })
        assertEquals("Chapter 12: The End", chapters[0].label)
        assertEquals(1_793_318_400_000, chapters[0].uploadedAt) // 2026-10-30T00:00Z

        val pages = adapter.pages(chapters[0], dataSaver = false)
        assertEquals(listOf("/token/a/0.jpg", "/token/b/1.jpg"), pages.map { it.imageUrl.substringAfter(server.url("/").toString().trimEnd('/')) })
        assertTrue(pages[0].headers["Referer"]!!.startsWith(server.url("/").toString().trimEnd('/')))
    }

    @Test
    fun `mangaread search details chapters and pages`() = serve(
        mapOf(
            "/?s=" to """
                <div class="row c-tabs-item__content">
                  <div class="tab-thumb"><a href="{BASE}/manga/sample-series/"><img src="{BASE}/wp-content/uploads/2020/01/cover-193x278.jpg"></a></div>
                  <div class="tab-summary"><div class="post-title"><h3><a href="{BASE}/manga/sample-series/">Sample Series</a></h3></div>
                  <div class="post-content">
                    <div class="post-content_item mg_alternative"><div class="summary-heading"><h5>Alternative</h5></div><div class="summary-content"> サンプル; Pick Me Up, Again </div></div>
                    <div class="post-content_item mg_status"><div class="summary-heading"><h5>Status</h5></div><div class="summary-content">OnGoing</div></div>
                  </div></div>
                </div>""",
            "/manga/sample-series/ajax/chapters/" to """
                <ul>
                  <li class="wp-manga-chapter"><a href="{BASE}/manga/sample-series/chapter-2/"> Chapter 2 </a><span class="chapter-release-date"><i>30.10.2026</i></span></li>
                  <li class="wp-manga-chapter"><a href="{BASE}/manga/sample-series/chapter-1/"> Chapter 1 </a><span class="chapter-release-date"><i>1 day ago</i></span></li>
                </ul>""",
            "/manga/sample-series/chapter-2/" to """
                <div class="reading-content">
                  <div class="page-break"><img id="image-0" src=" {BASE}/wp-content/uploads/WP-manga/data/1.jpg" class="wp-manga-chapter-img"></div>
                  <div class="page-break"><img id="image-1" data-src="{BASE}/wp-content/uploads/WP-manga/data/2.jpg" src="data:image/gif;base64,R0lG" class="wp-manga-chapter-img"></div>
                </div>""",
            "/manga/sample-series/" to """
                <div class="post-title"><span class="manga-title-badges">HOT</span><h1> Sample Series <span class="badge">SS</span></h1></div>
                <div class="summary_image"><img src="{BASE}/wp-content/uploads/placeholder.gif"></div>
                <div class="post-content_item"><div class="summary-heading"><h5>Alternative</h5></div><div class="summary-content">Sample Series</div></div>
                <div class="post-content_item"><div class="summary-heading"><h5>Author(s)</h5></div><div class="summary-content"><a href="#">Some Author</a></div></div>
                <div class="post-content_item"><div class="summary-heading"><h5>Genre(s)</h5></div><div class="summary-content"><a href="#">Action</a>, <a href="#">Fantasy</a></div></div>
                <div class="post-content_item"><div class="summary-heading"><h5>Type</h5></div><div class="summary-content">Manhua</div></div>
                <div class="post-content_item"><div class="summary-heading"><h5>Release</h5></div><div class="summary-content">2019</div></div>
                <div class="post-content_item"><div class="summary-heading"><h5>Status</h5></div><div class="summary-content">Completed</div></div>
                <div class="summary__content"><p><strong>A brief description of the manga Sample Series:</strong></p><p>Real text.</p></div>""",
        ),
    ) { server ->
        val base = server.url("/").toString().trimEnd('/')
        val adapter = MangaReadAdapter(ctx, base)
        val hits = adapter.search("sample")
        assertEquals(listOf("sample-series"), hits.map { it.id })
        assertEquals(listOf("サンプル", "Pick Me Up, Again"), hits[0].altTitles)
        assertTrue(adapter.search("unrelated words").isEmpty(), "description-only matches are dropped")
        assertEquals(listOf("sample-series"), adapter.search("ピック サンプル").map { it.id }) // non-Latin query keeps hits
        assertEquals(PublicationStatus.ONGOING, hits[0].status)

        val d = adapter.details(hits[0])
        assertEquals("Sample Series", d.title)
        assertEquals(ContentKind.MANHUA, d.kind)
        assertEquals(PublicationStatus.COMPLETED, d.status)
        assertEquals(2019, d.year)
        assertEquals(listOf("Action", "Fantasy"), d.genres)
        assertEquals("Real text.", d.description)
        // Thumbnail size suffix stripped; the .gif placeholder on the detail page is ignored.
        assertEquals("$base/wp-content/uploads/2020/01/cover.jpg", d.coverUrl)

        val chapters = adapter.chapters(d, listOf("en"))
        assertEquals(listOf("sample-series/chapter-2", "sample-series/chapter-1"), chapters.map { it.id })
        assertEquals("Chapter 2", chapters[0].label)
        assertEquals(1_793_318_400_000, chapters[0].uploadedAt)
        assertEquals(null, chapters[1].uploadedAt)

        val pages = adapter.pages(chapters[0], dataSaver = false)
        assertEquals(listOf("$base/wp-content/uploads/WP-manga/data/1.jpg", "$base/wp-content/uploads/WP-manga/data/2.jpg"), pages.map { it.imageUrl })
    }

    @Test
    fun `mgeko search details chapters and pages`() = serve(
        mapOf(
            "/search/" to """
                <ul><li class="novel-item"><a href="/manga/manga-1003/" title="Sample Series"><figure class="novel-cover">
                <img class="lazy" src="/static/img/loading.gif" data-src="/media/covers/s.jpg"></figure><h4 class="novel-title"> Sample Series </h4></a></li></ul>""",
            "/manga/manga-1003/all-chapters/" to """
                <ul class="chapter-list">
                  <li><a href="/reader/en/manga-1003-chapter-12-5-eng-li/"><strong class="chapter-title"> 12-5-eng-li </strong><time class="chapter-update" datetime="Jan. 7, 2026, 2:50 p.m."></time></a></li>
                  <li><a href="/reader/en/manga-1003-chapter-12-eng-li/"><strong class="chapter-title"> 12-eng-li </strong><time class="chapter-update" datetime="Sept. 3, 2025, noon"></time></a></li>
                  <li><a href="/reader/en/manga-1003-chapter-11-the-end-eng-li/"><strong class="chapter-title"> 11-the-end-eng-li </strong></a></li>
                  <li><a href="/reader/en/manga-1003-side-story-1-eng-li/"><strong class="chapter-title"> side-story-1-eng-li </strong></a></li>
                  <li><a href="/reader/en/manga-1003-spoiler-eng-li/"><strong class="chapter-title"> spoiler-eng-li </strong></a></li>
                  <li><a href="/reader/en/manga-1003-announcement-eng-li/"><strong class="chapter-title"> announcement </strong></a></li>
                </ul>""",
            "/reader/en/manga-1003-chapter-12-5-eng-li/" to """
                <div id="chapter-reader"><img class="mgr-page" src="https://img.example/1/0.jpg"><img src="https://img.example/credits-mgeko.png"><img class="mgr-page" src="https://img.example/1/1.jpg"></div>""",
            "/manga/manga-1003/" to """
                <div class="cover"><img class="lazy" src="x/default-placeholder.png" data-src="{BASE}/covers/l_30.jpg"></div>
                <div class="novel-info"><h1>Sample Series</h1><div class="alternative-title">Updating;Sample One;SO • Sample, Again • サンプル</div>
                <div class="author"><a title="Updating">Updating</a></div></div>
                <div class="header-stats"><strong class="ongoing">Ongoing</strong></div>
                <div class="categories"><a href="/genre/action">Action</a></div>
                <p class="description">Sample is a Manhwa ... The Summary is <br><br> Real plot.</p>""",
        ),
    ) { server ->
        val base = server.url("/").toString().trimEnd('/')
        val adapter = MgekoAdapter(ctx, base)
        assertEquals("Omniscient Reader", MgekoAdapter.searchTerm("Omniscient Reader's Viewpoint"))
        assertEquals("Solo Leveling", MgekoAdapter.searchTerm("Solo Leveling: Arise"))
        val hits = adapter.search("sample")
        assertEquals(listOf("manga-1003"), hits.map { it.id })
        assertEquals("$base/media/covers/s.jpg", hits[0].coverUrl)

        val d = adapter.details(hits[0])
        assertEquals(listOf("Sample One", "SO", "Sample, Again", "サンプル"), d.altTitles)
        assertEquals(emptyList(), d.authors)
        assertEquals(PublicationStatus.ONGOING, d.status)
        assertEquals("Real plot.", d.description)
        assertEquals("$base/covers/l_30.jpg", d.coverUrl)

        val chapters = adapter.chapters(d, listOf("en"))
        assertEquals(listOf("Chapter 12.5", "Chapter 12", "Chapter 11: The End", "Side Story 1"), chapters.map { it.label })
        assertEquals(1_767_797_400_000, chapters[0].uploadedAt) // 2026-01-07T14:50Z
        assertEquals(1_756_900_800_000, chapters[1].uploadedAt) // 2025-09-03T12:00Z

        val pages = adapter.pages(chapters[0], dataSaver = false)
        assertEquals(listOf("https://img.example/1/0.jpg", "https://img.example/1/1.jpg"), pages.map { it.imageUrl })
    }

    @Test
    fun `atsumaru search details chapters and pages`() = serve(
        mapOf(
            "/collections/manga/documents/search" to """{"page":1,"found":1,"request_params":{"per_page":20},"hits":[{"document":
                {"id":"oZOG5","title":"Sample Series","otherNames":["sample series","サンプル","Sample, Again"],"poster":"/static/posters/p.jpg",
                 "posterSmall":"/static/posters/p-small.avif","type":"Manwha","medium":"Comic","status":"Completed","authors":["A One"]}}]}""",
            "/api/manga/page" to """{"mangaPage":{"id":"oZOG5","title":"Sample Series","englishTitle":"The Sample","otherNames":["Sample Series"],
                "poster":{"image":"posters/big.jpg","largeImage":"posters/big-large.avif"},"type":"Manwha","medium":"Comic","status":"Ongoing",
                "released":1514764800000,"synopsis":" Plot. ","anilistId":"105398","malId":"121496","mangaBakaId":"3397","mangaUpdatesId":"151349",
                "authors":[{"name":"A One","type":"Author"},{"name":"B Two","type":"Artist"}],"genres":[{"id":"1","name":"Action"}],
                "scanlators":[{"id":"g1","name":"Alpha","score":4},{"id":"g2","name":"Beta","score":1}]}}""",
            "/api/manga/allChapters" to """{"chapters":[
                {"id":"c1","scanlationMangaId":"g1","title":"Chapter 10.10","number":10.10,"createdAt":1751846327498,"pageCount":49},
                {"id":"c2","scanlationMangaId":"g2","title":"Chapter 10","number":10,"createdAt":"2025-07-07T00:00:00Z"},
                {"id":"c3","scanlationMangaId":"zz","title":"","number":9},
                {"id":"c4","scanlationMangaId":"g1","title":"Chapter 9 (Part 13)","number":9},
                {"id":"c5","scanlationMangaId":"g1","title":"Chapter 8 (Part 1)","number":8},
                {"id":"c6","scanlationMangaId":"g1","title":"Chapter 8 (Part 2)","number":8}]}""",
            "/api/read/chapter" to """{"readChapter":{"id":"c1","pages":[{"image":"/static/pages/oZOG5/c1/0.webp"},{"image":"https://other.example/1.webp"}]}}""",
        ),
    ) { server ->
        val base = server.url("/").toString().trimEnd('/')
        val adapter = AtsumaruAdapter(ctx, base, cdnUrl = "$base/cdn")
        val hits = adapter.search("sample")
        assertEquals(listOf("oZOG5"), hits.map { it.id })
        assertEquals(listOf("サンプル", "Sample, Again"), hits[0].altTitles) // own title (any case) dropped
        assertEquals(ContentKind.MANHWA, hits[0].kind)
        assertEquals("$base/cdn/static/posters/p.jpg", hits[0].coverUrl)

        val d = adapter.details(hits[0])
        assertEquals(105398L, d.ids.anilist)
        assertEquals(121496L, d.ids.mal)
        assertEquals(3397L, d.ids.mangabaka)
        assertEquals(null, d.ids.mangaUpdates) // legacy numeric id is not comparable with base-36 ids
        assertEquals(listOf("A One"), d.authors)
        assertEquals(2018, d.year)
        assertEquals("Plot.", d.description)
        assertEquals(PublicationStatus.ONGOING, d.status)
        assertEquals("$base/cdn/static/posters/big.jpg", d.coverUrl)
        assertTrue("The Sample" in d.altTitles && "サンプル" in d.altTitles)

        val chapters = adapter.chapters(d, listOf("en"))
        assertEquals(listOf("oZOG5/c1", "oZOG5/c2", "oZOG5/c3", "oZOG5/c4", "oZOG5/c5", "oZOG5/c6"), chapters.map { it.id })
        assertEquals(listOf("Alpha", "Beta", null, "Alpha", "Alpha", "Alpha"), chapters.map { it.group })
        assertEquals(listOf("10.10", "10", "9", "9", "8", "8"), chapters.map { it.number })
        // Chapter 9 also exists without a part marker, so "(Part 13)" is an arc subtitle and merges with chapter 9.
        assertEquals("Chapter 9: Part 13", chapters[3].label)
        fun key(c: alexandria.core.model.SourceChapter) = alexandria.core.normalization.ChapterLabelParser.parse(c.label, c.number).identity.key()
        assertEquals(key(chapters[2]), key(chapters[3]))
        // Chapter 8 only exists as parts: a real split, kept distinct.
        assertEquals("Chapter 8 (Part 1)", chapters[4].label)
        assertTrue(key(chapters[4]) != key(chapters[5]))
        assertEquals("Chapter 9", chapters[2].label)
        assertEquals(1_751_846_327_498, chapters[0].uploadedAt)
        assertEquals(1_751_846_400_000, chapters[1].uploadedAt)

        val pages = adapter.pages(chapters[0], dataSaver = false)
        assertEquals(listOf("$base/cdn/static/pages/oZOG5/c1/0.webp", "https://other.example/1.webp"), pages.map { it.imageUrl })
        assertTrue(server.takeRequest().target.contains("filter_by="))
    }
}
