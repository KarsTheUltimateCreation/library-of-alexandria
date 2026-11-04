package alexandria.core.reconciliation

import alexandria.core.completeness.CompletenessAnalyzer
import alexandria.core.completeness.GapType
import alexandria.core.config.AggregatorSettings
import alexandria.core.model.PublicationStatus
import alexandria.core.model.SourceChapter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterReconcilerTest {

    private val reconciler = ChapterReconciler()
    private val settings = AggregatorSettings(languages = listOf("en"))

    private fun ch(source: String, label: String, lang: String = "en", group: String? = null, number: String? = null, volume: String? = null, title: String? = null) =
        SourceChapter(source, "s-$source", "$source-$label-$group-$lang", label, number = number, volume = volume, language = lang, group = group, title = title)

    private fun keys(r: ReconcileResult) = r.chapters.map { it.key }

    @Test
    fun `overlapping ranges merge into one ordered list`() {
        val a = (1..50).map { ch("a", "Chapter $it") }
        val b = (45..75).map { ch("b", "Ch. $it") }
        val c = (70..100).map { ch("c", "Episode $it") }
        val r = reconciler.reconcile(mapOf("a" to a, "b" to b, "c" to c), settings)
        assertEquals((1..100).map { "en:c:$it" }, keys(r))
        // Alternatives are preserved, never discarded.
        assertEquals(setOf("a", "b"), r.chapters.first { it.key == "en:c:47" }.sources.toSet())
        assertEquals(setOf("b", "c"), r.chapters.first { it.key == "en:c:72" }.sources.toSet())
    }

    @Test
    fun `missing chapter is recovered from another provider`() {
        val a = listOf(1, 2, 3, 5, 6).map { ch("a", "Chapter $it") }
        val b = listOf(4, 5, 6).map { ch("b", "Chapter $it") }
        val r = reconciler.reconcile(mapOf("a" to a, "b" to b), settings)
        assertEquals((1..6).map { "en:c:$it" }, keys(r))
        assertEquals(listOf("b"), r.chapters.first { it.key == "en:c:4" }.sources)
        val report = CompletenessAnalyzer().analyze(r, null, PublicationStatus.ONGOING, "en")
        assertTrue(report.gaps.any { it.type == GapType.CONFIRMED_MISSING && it.from == 4L && it.note == "a" }, "${report.gaps}")
        assertTrue(report.gaps.none { it.type == GapType.POSSIBLE_MISSING })
    }

    @Test
    fun `decimal, part, extra and season chapters stay separate`() {
        val a = listOf("Chapter 10", "Chapter 10.1", "Chapter 10.5", "Chapter 10.10", "Chapter 10 Part 1", "Chapter 10 Extra", "Season 2 Chapter 10")
            .map { ch("a", it) }
        val r = reconciler.reconcile(mapOf("a" to a), settings)
        assertEquals(7, r.chapters.size)
    }

    @Test
    fun `ordering puts extras after their anchor and seasons after season one`() {
        val a = listOf("Season 2 Chapter 1", "Chapter 2", "Chapter 1 Extra", "Chapter 1", "Prologue", "Chapter 1.5").map { ch("a", it) }
        val r = reconciler.reconcile(mapOf("a" to a), settings)
        assertEquals(listOf("en:pro", "en:c:1", "en:x:1", "en:c:1.5", "en:c:2", "en:s2:c:1"), keys(r))
    }

    @Test
    fun `languages are filtered and never merged`() {
        val a = listOf(ch("a", "Chapter 1", "en"), ch("a", "Chapter 1", "es"))
        val en = reconciler.reconcile(mapOf("a" to a), settings)
        assertEquals(listOf("en:c:1"), keys(en))
        val both = reconciler.reconcile(mapOf("a" to a), settings.copy(languages = listOf("en", "es")))
        assertEquals(setOf("en:c:1", "es:c:1"), keys(both).toSet())
    }

    @Test
    fun `alternative translations shown separately only when enabled`() {
        val a = listOf(ch("a", "Ch. 1", group = "Alpha Scans"), ch("a", "Ch. 1", group = "Beta TL"))
        assertEquals(1, reconciler.reconcile(mapOf("a" to a), settings).chapters.size)
        assertEquals(2, reconciler.reconcile(mapOf("a" to a), settings.copy(showAlternativeTranslations = true)).chapters.size)
        // With grouping on, both releases are still kept on the single record.
        assertEquals(2, reconciler.reconcile(mapOf("a" to a), settings).chapters.single().releases.size)
    }

    @Test
    fun `deduplication off keeps each release but retains alternatives`() {
        val r = reconciler.reconcile(mapOf("a" to listOf(ch("a", "Chapter 1")), "b" to listOf(ch("b", "Chapter 1"))), settings.copy(deduplicate = false))
        assertEquals(2, r.chapters.size)
        r.chapters.forEach { assertEquals(2, it.releases.size) }
        assertEquals(setOf("a", "b"), r.chapters.map { it.releases.first().sourceId }.toSet())
        // Keys must be distinct and stable.
        assertEquals(keys(r), keys(reconciler.reconcile(mapOf("a" to listOf(ch("a", "Chapter 1")), "b" to listOf(ch("b", "Chapter 1"))), settings.copy(deduplicate = false))))
    }

    @Test
    fun `specials can be excluded`() {
        val a = listOf("Chapter 1", "Chapter 1 Extra", "Side Story 1", "Notice").map { ch("a", it) }
        assertEquals(4, reconciler.reconcile(mapOf("a" to a), settings).chapters.size)
        assertEquals(listOf("en:c:1"), keys(reconciler.reconcile(mapOf("a" to a), settings.copy(includeSpecials = false))))
    }

    @Test
    fun `volume reset providers keep volume in identity`() {
        // Provider numbers 1..3 inside each of three volumes.
        val a = (1..3).flatMap { v -> (1..3).map { c -> ch("a", "Vol.$v Ch.$c", number = "$c", volume = "$v") } }
        val r = reconciler.reconcile(mapOf("a" to a), settings)
        assertEquals(9, r.chapters.size)
        assertEquals(setOf("a"), r.volumeResetSources)
    }

    @Test
    fun `continuous numbering with volume labels is not a reset`() {
        val a = (1..9).map { c -> ch("a", "Vol.${(c - 1) / 3 + 1} Ch.$c", number = "$c", volume = "${(c - 1) / 3 + 1}") }
        val b = (1..9).map { ch("b", "Chapter $it") }
        val r = reconciler.reconcile(mapOf("a" to a, "b" to b), settings)
        assertEquals(9, r.chapters.size)
        assertTrue(r.chapters.all { it.sources.size == 2 })
    }

    @Test
    fun `conflicting titles lower confidence instead of deleting`() {
        val a = listOf(ch("a", "Chapter 10: Awakening of the Hunter"))
        val b = listOf(ch("b", "Chapter 10: Dinner with Grandmother"))
        val r = reconciler.reconcile(mapOf("a" to a, "b" to b), settings)
        val c = r.chapters.single()
        assertTrue(c.uncertain)
        assertEquals(2, c.releases.size)
        assertTrue(c.releases.all { it.confidence < 0.8 })
    }

    @Test
    fun `numbering offset between providers is detected`() {
        val titles = listOf("Arrival", "The Gate", "Red Moon", "Blue Flame", "Last Stand", "Homecoming")
        val a = titles.mapIndexed { i, t -> ch("a", "Chapter ${i + 1}: $t") }
        val b = titles.mapIndexed { i, t -> ch("b", "Chapter ${i + 2}: $t") } // b counts a prologue as ch.1
        val r = reconciler.reconcile(mapOf("a" to a, "b" to b), settings)
        assertTrue(r.numberingOffsets.any { it.first == "b" && it.third == 1 }, "${r.numberingOffsets}")
        r.chapters.flatMap { it.releases }.filter { it.sourceId == "b" }.forEach { assertTrue(it.confidence < 0.8) }
    }

    @Test
    fun `incorrect provider ordering is normalised`() {
        val a = listOf(5, 1, 3, 2, 4).map { ch("a", "Chapter $it") }
        assertEquals((1..5).map { "en:c:$it" }, keys(reconciler.reconcile(mapOf("a" to a), settings)))
    }

    @Test
    fun `empty input is fine`() {
        assertEquals(0, reconciler.reconcile(emptyMap(), settings).chapters.size)
        assertEquals(0, reconciler.reconcile(mapOf("a" to emptyList()), settings).chapters.size)
    }

    @Test
    fun `the provider with unusual numbering is demoted, not the well-aligned ones`() {
        val titles = (1..20).associateWith { "Arc title number $it" }
        fun aligned(id: String) = (1..20).map { ch(id, "Chapter $it", title = titles[it]) }
        // "aa_new" sorts first; before 1.6.6 the alphabetically later ids ("mangadex", "zz") would have been demoted.
        val outlier = (1..20).map { ch("aa_new", "Chapter ${it + 15}", title = titles[it]) }
        val r = reconciler.reconcile(mapOf("aa_new" to outlier, "mangadex" to aligned("mangadex"), "zz" to aligned("zz")), settings)
        assertTrue(r.numberingOffsets.all { it.first == "aa_new" }, "${r.numberingOffsets}")
        val low = r.chapters.flatMap { it.releases }.filter { it.confidence < 0.8 }.map { it.sourceId }.toSet()
        assertEquals(setOf("aa_new"), low)
    }
}
