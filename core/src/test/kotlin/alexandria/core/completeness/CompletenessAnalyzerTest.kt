package alexandria.core.completeness

import alexandria.core.config.AggregatorSettings
import alexandria.core.model.PublicationStatus
import alexandria.core.model.SourceChapter
import alexandria.core.reconciliation.ChapterReconciler
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletenessAnalyzerTest {
    private val settings = AggregatorSettings(languages = listOf("en"))

    private fun reconcile(vararg sources: Pair<String, List<String>>) = ChapterReconciler().reconcile(
        sources.associate { (s, labels) -> s to labels.map { SourceChapter(s, "x", "$s-$it", it) } },
        settings,
    )

    @Test
    fun `numbering gap with no provider is only possible, never fabricated`() {
        val r = reconcile("a" to listOf("Chapter 1", "Chapter 2", "Chapter 5"))
        val report = CompletenessAnalyzer().analyze(r, null, PublicationStatus.ONGOING, "en")
        val g = report.gaps.single { it.type == GapType.POSSIBLE_MISSING }
        assertEquals(3L to 4L, g.from to g.to)
        assertEquals(3, r.chapters.size) // nothing invented
    }

    @Test
    fun `split chapters do not count as gaps`() {
        val r = reconcile("a" to listOf("Chapter 1", "Chapter 2 Part 1", "Chapter 2 Part 2", "Chapter 3.1", "Chapter 3.2", "Chapter 4.5", "Chapter 5"))
        val report = CompletenessAnalyzer().analyze(r, null, PublicationStatus.ONGOING, "en")
        val possible = report.gaps.filter { it.type == GapType.POSSIBLE_MISSING }
        // 2 (parts) and 3 (3.1/3.2) are split chapters, not gaps; a lone 4.5 does not prove chapter 4 exists.
        assertEquals(listOf(4L to 4L), possible.map { it.from to it.to }, "${report.gaps}")
    }

    @Test
    fun `original count above translations is untranslated, not missing`() {
        val r = reconcile("a" to (1..135).map { "Chapter $it" })
        val report = CompletenessAnalyzer().analyze(r, "150", PublicationStatus.ONGOING, "en")
        val g = report.gaps.single { it.type == GapType.UNTRANSLATED }
        assertEquals(136L to 150L, g.from to g.to)
        assertTrue(report.gaps.none { it.type == GapType.POSSIBLE_MISSING })
    }

    @Test
    fun `series numbered from zero is complete when counts match`() {
        val r = reconcile("a" to (0..200).map { "Chapter $it" })
        val report = CompletenessAnalyzer().analyze(r, "201", PublicationStatus.COMPLETED, "en")
        assertTrue(report.gaps.none { it.type == GapType.UNTRANSLATED }, "${report.gaps}")
    }

    @Test
    fun `more translated chapters than original count signals numbering difference`() {
        val r = reconcile("a" to (1..240).map { "Chapter $it" })
        val report = CompletenessAnalyzer().analyze(r, "201", PublicationStatus.COMPLETED, "en")
        assertTrue(report.gaps.any { it.type == GapType.NUMBERING_DIFFERENCE }, "${report.gaps}")
    }

    @Test
    fun `huge numbering jump is not reported as hundreds of missing chapters`() {
        val r = reconcile("a" to listOf("Chapter 1", "Chapter 2", "Chapter 500"))
        val report = CompletenessAnalyzer().analyze(r, null, PublicationStatus.ONGOING, "en")
        assertTrue(report.gaps.none { it.type == GapType.POSSIBLE_MISSING })
        assertTrue(report.gaps.any { it.type == GapType.NUMBERING_DIFFERENCE })
    }

    @Test
    fun `missing metadata is handled`() {
        val r = reconcile("a" to listOf("Chapter 1", "Chapter 2"))
        val report = CompletenessAnalyzer().analyze(r, null, PublicationStatus.UNKNOWN, null)
        assertTrue(report.gaps.isEmpty())
        assertEquals("a", report.primarySource)
    }

    @Test
    fun `recovered counts are attributed`() {
        val r = reconcile("a" to (1..10).filter { it != 4 && it != 7 }.map { "Chapter $it" }, "b" to listOf("Chapter 4", "Chapter 7"))
        val report = CompletenessAnalyzer().analyze(r, null, PublicationStatus.ONGOING, "en")
        assertEquals(mapOf("b" to 2), report.recoveredBySource)
        assertTrue(report.summaryLines().first().startsWith("2 chapter(s) not on a"))
    }
}
