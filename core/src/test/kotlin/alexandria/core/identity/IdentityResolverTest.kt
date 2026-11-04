package alexandria.core.identity

import alexandria.core.model.ContentKind
import alexandria.core.model.ExternalIds
import alexandria.core.model.SeriesMetadata
import alexandria.core.model.SourceSeries
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentityResolverTest {
    private val resolver = IdentityResolver()

    private val soloLeveling = SeriesMetadata(
        provider = "mangabaka",
        ids = ExternalIds(mangabaka = 3397, anilist = 105398, mangaUpdates = "6z1uqw7"),
        title = "Solo Leveling",
        romanizedTitle = "Na Honjaman Level Up",
        nativeTitle = "나 혼자만 레벨업",
        altTitles = listOf("Only I Level Up", "Ore dake Level Up na Ken", "俺だけレベルアップな件"),
        kind = ContentKind.MANHWA,
        year = 2018,
    )

    private fun s(title: String, alt: List<String> = emptyList(), ids: ExternalIds = ExternalIds(), kind: ContentKind = ContentKind.UNKNOWN, year: Int? = null) =
        SourceSeries("p", title.lowercase(), title, alt, ids = ids, kind = kind, year = year)

    @Test
    fun `identical and punctuation-different titles match when corroborated`() {
        assertEquals(MatchDecision.CONFIRMED, resolver.match(soloLeveling, s("Solo Leveling", year = 2018)).decision)
        assertEquals(MatchDecision.CONFIRMED, resolver.match(soloLeveling, s("Solo-Leveling!", year = 2018)).decision)
        assertEquals(MatchDecision.CONFIRMED, resolver.match(soloLeveling, s("SOLO LEVELING", year = 2019)).decision)
    }

    @Test
    fun `alternative, native and romanized titles match when corroborated`() {
        assertEquals(MatchDecision.CONFIRMED, resolver.match(soloLeveling, s("Only I Level Up", year = 2018)).decision)
        assertEquals(MatchDecision.CONFIRMED, resolver.match(soloLeveling, s("나 혼자만 레벨업", year = 2018)).decision)
        assertEquals(MatchDecision.CONFIRMED, resolver.match(soloLeveling, s("Na Honjaman Level-Up", year = 2018)).decision)
        // Two title variants agreeing are evidence on their own.
        assertEquals(MatchDecision.CONFIRMED, resolver.match(soloLeveling, s("Ore Dake Level Up Na Ken", alt = listOf("Solo Leveling"))).decision)
    }

    @Test
    fun `equal titles alone are never enough to merge`() {
        val r = resolver.match(soloLeveling, s("Solo Leveling"))
        assertEquals(MatchDecision.AMBIGUOUS, r.decision, "${r.reasons}")
        assertNull(resolver.pick(soloLeveling, listOf(s("Solo Leveling"))))
        // Same title, same year range, but different authors in the same script: another work.
        val monster = SeriesMetadata("p", title = "Monster", authors = listOf("Naoki Urasawa"), year = 1994)
        val other = SourceSeries("q", "m2", "Monster", authors = listOf("Kim Jae-han"), year = 1995)
        assert(resolver.match(monster, other).decision != MatchDecision.CONFIRMED)
        // Same title and an author in common (name order and case differ): confirmed.
        val same = SourceSeries("q", "m1", "MONSTER", authors = listOf("URASAWA Naoki"))
        assertEquals(MatchDecision.CONFIRMED, resolver.match(monster, same).decision)
    }

    @Test
    fun `different editions and sequels are rejected`() {
        assertEquals(MatchDecision.REJECTED, resolver.match(soloLeveling, s("Solo Leveling Novel")).decision)
        assertEquals(MatchDecision.REJECTED, resolver.match(soloLeveling, s("Solo Leveling (Volume Version)")).decision)
        assertEquals(MatchDecision.REJECTED, resolver.match(soloLeveling, s("Solo Leveling: Ragnarok")).decision)
        assertEquals(MatchDecision.REJECTED, resolver.match(soloLeveling, s("Solo Leveling", kind = ContentKind.NOVEL)).decision)
    }

    @Test
    fun `similar but unrelated titles are not merged`() {
        assertEquals(MatchDecision.REJECTED, resolver.match(soloLeveling, s("Solo Max-Level Newbie")).decision)
        val tower = SeriesMetadata("mb", title = "Tower of God")
        assertEquals(MatchDecision.REJECTED, resolver.match(tower, s("Tower of Babel")).decision)
    }

    @Test
    fun `short titles require exact equality`() {
        val ren = SeriesMetadata("mb", title = "Ren", year = 2010)
        assertEquals(MatchDecision.CONFIRMED, resolver.match(ren, s("Ren", year = 2010)).decision)
        assertEquals(MatchDecision.REJECTED, resolver.match(ren, s("Rena", year = 2010)).decision)
    }

    @Test
    fun `shared ids confirm, conflicting ids reject`() {
        assertEquals(MatchDecision.CONFIRMED, resolver.match(soloLeveling, s("Totally Different Name", ids = ExternalIds(anilist = 105398))).decision)
        // Same title but an incorrect external id: not merged.
        assertEquals(MatchDecision.REJECTED, resolver.match(soloLeveling, s("Solo Leveling", ids = ExternalIds(anilist = 1))).decision)
    }

    @Test
    fun `year mismatch lowers confidence`() {
        val r = resolver.match(soloLeveling, s("Solo Leveling", year = 2005))
        assert(r.decision != MatchDecision.CONFIRMED)
        assertEquals(MatchDecision.CONFIRMED, resolver.match(soloLeveling, s("Solo Leveling", year = 2018)).decision)
        assert(r.score < 1.0)
    }

    @Test
    fun `pick refuses ambiguous duplicates`() {
        val a = SourceSeries("p", "1", "Solo Leveling")
        val b = SourceSeries("p", "2", "Solo Leveling")
        assertNull(resolver.pick(soloLeveling.copy(year = null), listOf(a, b)))
        // Exact id evidence breaks the tie.
        val c = b.copy(ids = ExternalIds(anilist = 105398))
        assertEquals("2", resolver.pick(soloLeveling, listOf(a, c))!!.first.id)
    }
}
