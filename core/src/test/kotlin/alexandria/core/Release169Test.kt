package alexandria.core

import alexandria.core.identity.IdentityResolver
import alexandria.core.merge.SearchClusterer
import alexandria.core.model.ContentKind
import alexandria.core.model.ReaderPage
import alexandria.core.model.SourceChapter
import alexandria.core.model.SourceSeries
import alexandria.core.sources.AdapterContext
import alexandria.core.sources.SourceAdapter
import alexandria.core.sources.VolumeGroupingAdapter
import alexandria.core.sources.VolumeSeries
import alexandria.core.sources.adapters.MangaBallAdapter
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Grouped hentai parts, duplicate results, Manga Ball covers (1.6.9). Titles are the ones live sites returned. */
class Release169Test {
    private fun s(src: String, id: String, title: String, vararg alt: String, authors: List<String> = emptyList(), cover: String? = "https://c/$id.jpg") =
        SourceSeries(src, id, title, alt.toList(), coverUrl = cover, authors = authors)

    // Manga Ball's search for "my little sister is an orc" (Nov 4 2026).
    private val mangaBall = listOf(
        s("loa_mangaball", "a1", "[H] Imouto Wa Mesu Orc", "My Little Sister is an Orc", "イモウトハメスオーク"),
        s("loa_mangaball", "a3", "[H] Imouto Wa Mesu Orc 3", "My Little Sister is a Female Orc 3", "イモウトハメスオーク3"),
        s("loa_mangaball", "a4", "[H] Imouto Wa Mesu Orc 4", "My Little Sister is a Female Orc 4", "イモウトハメスオーク4"),
        s("loa_mangaball", "a7", "[H] Imouto Wa Mesu Orc 7", "My Little Sister Is A Female Orc 7", "イモウトハメスオーク7"),
        s("loa_mangaball", "a2", "[H] Imouto Wa Mesu Orc 2", "My Little Sister is a Female Orc 2", "イモウトハメスオーク2"),
        s("loa_mangaball", "a5", "[H] Imouto Wa Mesu Orc 5", "My Little Sisters are Slutty Orcs 5", "イモウトハメスオーク5"),
        s("loa_mangaball", "a6", "[H] Imouto Wa Mesu Orc 6", "My Little Sisters are Slutty Orcs 6", "イモウトハメスオーク6"),
        s("loa_mangaball", "x", "Imouto wa Neko"),
    )

    @Test
    fun `numbered parts on one site become one series with a stable id`() {
        val (out, groups) = VolumeSeries.group(mangaBall) { true }
        assertEquals(2, out.size, out.map { it.title }.toString())
        val g = groups.single()
        assertEquals("vol~imouto-wa-mesu-orc", g.series.id)
        assertEquals("Imouto Wa Mesu Orc", g.series.title)
        assertEquals((1..7).toList(), g.parts.map { it.second })
        assertEquals("https://c/a1.jpg", g.series.coverUrl, "cover of the first part")
        assertTrue(g.series.altTitles.any { it.equals("My Little Sister is a Female Orc", true) })
        assertEquals("Imouto wa Neko", out[1].title, "unrelated hits stay as they are, in place")
    }

    @Test
    fun `gallery copies with differing English titles group through their romanised alias`() {
        val hitomi = listOf(
            s("hitomi", "4107086", "My Little Sister is a Female Orc 8", "Imouto wa Mesu Orc 8 | My Little Sister is a Female Orc 8"),
            s("hitomi", "2695311", "My Little Sisters are Slutty Orcs 6", "Imouto wa Mesu Orc 6 | My Little Sisters are Slutty Orcs 6"),
            s("hitomi", "3047250", "My Little Sister is a Female Orc 4", "Imouto wa Mesu Orc 4 | My Little Sister is a Female Orc 4 (decensored)"),
            s("hitomi", "2150675", "My Little Sister is a Female Orc 4", "Imouto wa Mesu Orc 4 | My Little Sister is a Female Orc 4"),
            s("hitomi", "2851865", "My Little Sister & The Mother is a Female Orc H.Q. COLLECTION"),
        )
        val (out, groups) = VolumeSeries.group(hitomi) { true }
        assertEquals(2, out.size)
        val g = groups.single()
        assertEquals("vol~imouto-wa-mesu-orc", g.series.id, "the alias every part shares names the series")
        assertEquals(listOf(4, 4, 6, 8), g.parts.map { it.second }, "a re-upload of part 4 is a second release of it")
    }

    @Test
    fun `titles that merely end in a number are not parts`() {
        assertNull(VolumeSeries.numbered("Mob Psycho 100"))
        assertNull(VolumeSeries.numbered("Orc7"))
        assertNull(VolumeSeries.numbered("Summer 2019"))
        assertNull(VolumeSeries.numbered("Re 2"))
        assertEquals("Imouto wa Mesu Orc" to 5, VolumeSeries.numbered("Imouto wa Mesu Orc 5"))
        assertEquals("Title" to 3, VolumeSeries.numbered("Title Vol. 3"))
        // One numbered hit alone is not a series of parts.
        val single = listOf(s("hitomi", "1", "Some Doujin 2"), s("hitomi", "2", "Other Thing"))
        assertEquals(single, VolumeSeries.group(single) { true }.first)
        // Two different works that both end in "2" do not group.
        val two = listOf(s("hitomi", "1", "Alpha Story 2"), s("hitomi", "2", "Beta Story 2"))
        assertEquals(two, VolumeSeries.group(two) { true }.first)
    }

    private class Fake(override val id: String = "loa_mangaball") : SourceAdapter {
        override val name = "Fake"
        override val baseUrl = "https://fake"
        override val languages: Set<String>? = null
        var searches = 0
        var hits: List<SourceSeries> = emptyList()
        override suspend fun search(query: String): List<SourceSeries> { searches++; return hits }
        override suspend fun chapters(series: SourceSeries, languages: List<String>) =
            if (series.id == "a5") listOf(SourceChapter(id, series.id, "c5a", "Chapter 1", "1"), SourceChapter(id, series.id, "c5b", "Chapter 2", "2"))
            else listOf(SourceChapter(id, series.id, "c-${series.id}", "Oneshot"))
        override suspend fun pages(chapter: SourceChapter, dataSaver: Boolean) = listOf(ReaderPage(0, "https://img/${chapter.id}.jpg"))
        override fun seriesWebUrl(seriesId: String) = "$baseUrl/$seriesId"
    }

    @Test
    fun `a part series lists every part as a chapter and reads from the part`() = runTest {
        val fake = Fake().apply { hits = mangaBall }
        val a = VolumeGroupingAdapter(fake) { it.title.startsWith("[H]") }
        val composite = a.search("orc").first()
        assertEquals("vol~imouto-wa-mesu-orc", composite.id)
        // Restart: nothing in memory, the parts are found again from the id alone.
        val cold = VolumeGroupingAdapter(Fake().apply { hits = mangaBall }) { it.title.startsWith("[H]") }
        val chapters = cold.chapters(SourceSeries("loa_mangaball", composite.id, "Imouto Wa Mesu Orc"), listOf("en"))
        assertEquals(listOf("1", "2", "3", "4", "5.1", "5.2", "6", "7"), chapters.map { it.number })
        assertEquals("a3", chapters.first { it.number == "3" }.seriesId, "a chapter keeps its part's own ids")
        assertEquals("https://img/c-a3.jpg", cold.pages(chapters.first { it.number == "3" }, false).single().imageUrl)
        assertTrue(cold.seriesWebUrl(composite.id).startsWith("https://fake/"))
        // Ordinary entries pass straight through.
        assertEquals("Oneshot", a.chapters(mangaBall.last(), listOf("en")).single().label)
    }

    // ------------------------------------------------------------ duplicate results

    @Test
    fun `entries group when one's title is the other's alternative title`() {
        val weeb = s("weebcentral", "w", "30 Years Since the Prologue")
        val read = s("mangaread", "r", "30 Years Have Passed Since the Prologue", "30 Years After the Prologue", "30 Years Since the Prologue", authors = listOf("Mak Ellaha"))
        val asura = s("loa_asurascans", "a", "30 Years Since the Prologue", authors = listOf("Markellaha", "GreenKirin"))
        val katana = s("mangakatana", "k", "30 Years Since the Prologue", "30 Years Have Passed Since the Prologue", authors = listOf("Mak Ellaha", "Girin Geurin(기린그린)"))
        val clusters = SearchClusterer.cluster(listOf(weeb, read, asura, katana), { null }, { _, _ -> false }, { listOf("weebcentral", "mangakatana", "mangaread", "loa_asurascans").indexOf(it) }, { true })
        assertEquals(1, clusters.size, clusters.map { c -> c.members.map { it.sourceId } }.toString())
        assertTrue(IdentityResolver.authorsAlike(listOf("Mak Ellaha"), listOf("Markellaha")))
        assertFalse(SearchClusterer.contradicts(asura, katana), "two spellings of one author are not two works")
        // Short titles only match a main title, never an alias.
        assertFalse(SearchClusterer.sameTitle(s("a", "1", "Orc"), s("b", "2", "Something Else", "Orc")))
        assertTrue(SearchClusterer.contradicts(s("a", "1", "X", authors = listOf("John Smith")), s("b", "2", "X", authors = listOf("Peter Parker"))))
    }

    @Test
    fun `authors shared with one member outweigh another member's romanisation`() {
        val katana = s("mangakatana", "k", "Nano Machine", authors = listOf("REDICE studio", "Hanjung Wolya (한중월야)", "Guem-Gang-Bul-Gae"))
        val atsu = s("atsumaru", "t", "Nano Machine", authors = listOf("Guem-Gang-Bul-Gae", "Han-Joong-Wueol-Ya"))
        val asura = s("loa_asurascans", "a", "Nano Machine", authors = listOf("Hanjung Wolya", "REDICE Studio"))
        val order = listOf("mangakatana", "atsumaru", "loa_asurascans")
        val clusters = SearchClusterer.cluster(listOf(katana, atsu, asura), { null }, { _, _ -> false }, { order.indexOf(it) }, { true })
        assertEquals(1, clusters.size, clusters.map { c -> c.members.map { it.sourceId } }.toString())
    }

    @Test
    fun `an unnumbered first part pairs with a single numbered part`() {
        val nh = listOf(
            s("nhentai", "305196", "My Little Sister is an Orc", "Imouto wa Mesu Orc", "イモウトハメスオーク"),
            s("nhentai", "585167", "My Little Sister Is A Female Orc 7", "Imouto wa Mesu Orc 7"),
        )
        val g = VolumeSeries.group(nh) { true }.second.single()
        assertEquals("vol~imouto-wa-mesu-orc", g.series.id)
        assertEquals(listOf(1, 7), g.parts.map { it.second })
    }

    @Test
    fun `a site's second entry of one series is not shown, placeholder authors prove nothing`() {
        val fireA = s("loa_mangafire", "42xox", "Golden Mage").copy(year = 2023)
        val fireB = s("loa_mangafire", "other", "Golden Mage").copy(year = 2023)
        val read = s("mangaread", "golden-mage", "Golden Mage", authors = listOf("Hyunwoo Kim")).copy(year = 2023)
        val order = listOf("mangaread", "loa_mangafire")
        val c = SearchClusterer.cluster(listOf(fireA, fireB, read), { null }, { _, _ -> false }, { order.indexOf(it) }, { true })
        assertEquals(1, c.size)
        assertEquals(listOf("mangaread", "loa_mangafire"), c.single().members.map { it.sourceId })
        // Different works under one name on one site stay apart (years far apart).
        val old = s("loa_mangafire", "o", "Overgeared").copy(year = 2017)
        val new = s("loa_mangafire", "n", "Overgeared").copy(year = 2020 + 4)
        assertEquals(2, SearchClusterer.cluster(listOf(old, new), { null }, { _, _ -> false }, { 0 }, { true }).size)
        val bot = s("mangaread", "r", "I'm Destined For Greatness!", authors = listOf("Bot"))
        val taro = s("loa_mangataro", "t", "I'm Destined For Greatness!", authors = listOf("Geulsseuneun Gigye", "Seo, Yunkyo"))
        assertFalse(SearchClusterer.contradicts(bot, taro))
    }

    @Test
    fun `a site's edition entry does not take the place of its plain entry`() {
        val book = s("loa_mangaball", "b", "Tower of God (Book Version)", "Tower of God")
        val plain = s("loa_mangaball", "p", "Tower of God")
        val weeb = s("weebcentral", "w", "Tower of God")
        val c = SearchClusterer.cluster(listOf(book, plain, weeb), { null }, { _, _ -> false }, { listOf("weebcentral", "loa_mangaball").indexOf(it) }, { true },
            relevance = { alexandria.core.normalization.SearchRelevance.score("Tower of God", it.title) })
        assertEquals(listOf(listOf("w", "p"), listOf("b")), c.map { r -> r.members.map { it.id } })
    }

    @Test
    fun `romanised korean credits do not split a manhwa`() {
        val taro = s("loa_mangataro", "t", "Superhuman Era", "The Superhuman Era", authors = listOf("Seop-I")).copy(kind = ContentKind.MANHWA)
        val read = s("mangaread", "r", "Superhuman Era", authors = listOf("Supp")).copy(year = 2019)
        assertFalse(SearchClusterer.contradicts(taro, read))
        // Manga keep the author check.
        assertTrue(SearchClusterer.contradicts(taro.copy(kind = ContentKind.MANGA), read.copy(kind = ContentKind.MANGA)))
    }

    // ------------------------------------------------------------ covers

    @Test
    fun `manga ball hentai covers load without a referer, google previews come last`() {
        val mb = MangaBallAdapter(AdapterContext(OkHttpClient()))
        assertEquals("", mb.imageHeaders("https://hencover.xyz/cover/2020/05/hr_21893.jpg")["Referer"])
        assertEquals("", mb.imageHeaders("https://mancover.xyz/cover/2024/07/manhwaread-the-lucky-guy.webp")["Referer"])
        assertEquals("https://mangaball.com/", mb.imageHeaders("https://red-and-blue.net/x.jpg")["Referer"])
        val json = """{"data":[{"id":"t","name":"T","image":{"file":"https://encrypted-tbn0.gstatic.com/images?q=x","cdn_mangaupdate":"https://cdn.mangaupdates.com/image/i1.jpg"}}]}"""
        assertEquals("https://cdn.mangaupdates.com/image/i1.jpg", mb.parseSearch(json).single().coverUrl)
        assertTrue(mb.coverPixels("https://encrypted-tbn0.gstatic.com/images?q=x") < 400L * 600)
    }
}
