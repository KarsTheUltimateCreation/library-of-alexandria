package alexandria.core.normalization

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChapterLabelParserTest {

    private fun key(label: String, number: String? = null, volume: String? = null) =
        ChapterLabelParser.parse(label, number, volume).identity.key()

    @Test
    fun `equivalent labels share a key`() {
        val labels = listOf(
            "Chapter 10", "Ch. 010", "Ch.10", "Episode 10", "Ep. 10", "chapter 10: Awakening",
            "Chapter 10 [English]", "Chapter 10 - Reupload", "Vol.2 Ch.10", "Volume 2 Chapter 10",
            "#10", "10 - Awakening", "第10話", "10화", "CHAPTER 10 (Official)",
        )
        labels.forEach { assertEquals("c:10", key(it), "label '$it'") }
    }

    @Test
    fun `distinct chapters do not collide`() {
        val keys = listOf(
            "Chapter 10", "Chapter 10.1", "Chapter 10.5", "Chapter 10 Part 1", "Chapter 10 Part 2",
            "Chapter 10.10", "Chapter 10 Extra", "Season 2 Chapter 10", "Side Story 10",
        ).map { key(it) }
        assertEquals(keys.size, keys.toSet().size, "keys: $keys")
    }

    @Test
    fun `decimal labels are lossless`() {
        assertEquals("c:12.1", key("Chapter 12.1"))
        assertEquals("c:12.10", key("Chapter 12.10"))
        assertNotEquals(key("Chapter 12.1"), key("Chapter 12.10"))
        // "10.0" is how some APIs write a whole chapter.
        assertEquals("c:10", key("Ch. 10", number = "10.0"))
    }

    @Test
    fun `fraction ordering treats suffix as sub-chapter index`() {
        val sorted = listOf("10.10", "10.2", "10.1", "10", "9.5", "11").map { ChapterNumber.parse(it)!! }.sorted().map { it.toString() }
        assertEquals(listOf("9.5", "10", "10.1", "10.2", "10.10", "11"), sorted)
    }

    @Test
    fun `volume is informational and only keyed on demand`() {
        val p = ChapterLabelParser.parse("Vol.3 Ch.25 - The Gate")
        assertEquals("3", p.identity.volume)
        assertEquals("c:25", p.identity.key())
        assertEquals("v3:c:25", p.identity.key(includeVolume = true))
        assertEquals("The Gate", p.title)
    }

    @Test
    fun `volume alone is not mistaken for a chapter number`() {
        val p = ChapterLabelParser.parse("Volume 3")
        assertTrue(p.identity.number == null || p.identity.kind == ChapterKind.UNKNOWN, "got ${p.identity}")
    }

    @Test
    fun `seasons and arcs are part of identity`() {
        assertEquals("s2:c:10", key("Season 2 Chapter 10"))
        assertEquals("s2:c:10", key("S2 Episode 10"))
        assertEquals("s2:c:10", key("S2E10"))
        assertEquals("s3:c:5", key("Part 3 Chapter 5"))
    }

    @Test
    fun `parts and split chapters`() {
        assertEquals("c:10:p1", key("Chapter 10 Part 1"))
        assertEquals("c:10:p2", key("Chapter 10 (2/2)"))
        assertEquals("c:10:p2", key("Ch. 10 Pt. 2"))
    }

    @Test
    fun `special chapter kinds`() {
        assertEquals("x:10", key("Chapter 10 Extra"))
        assertEquals("x:10", key("Chapter 10 [Bonus]"))
        assertEquals("side:3", key("Side Story 3"))
        assertEquals(ChapterKind.PROLOGUE, ChapterLabelParser.parse("Prologue").identity.kind)
        assertEquals(ChapterKind.EPILOGUE, ChapterLabelParser.parse("Epilogue").identity.kind)
        assertEquals(ChapterKind.ONESHOT, ChapterLabelParser.parse("Oneshot").identity.kind)
        assertEquals(ChapterKind.NOTICE, ChapterLabelParser.parse("Hiatus Announcement").identity.kind)
    }

    @Test
    fun `chapter number followed by a numbered side story is the chapter`() {
        // Atsumaru writes these without a separator; other providers write "Chapter 200: Side Story 21".
        assertEquals(key("Chapter 200: Side Story 21 [END]"), key("Chapter 200 Side Story 21 { THE END }"))
        assertEquals("c:181", key("Chapter 181 Side Story 2"))
        assertEquals("c:12", key("Ch. 12 - Extra 3"))
        // Still kinds: no chapter number first, or no number of their own.
        assertEquals("side:3", key("Side Story 3"))
        assertEquals("x:10", key("Chapter 10 Extra"))
    }

    @Test
    fun `words inside a chapter title do not change the kind`() {
        assertEquals("c:10", key("Chapter 10: The Extra"))
        assertEquals("c:10", key("Chapter 10 - A Special Day"))
        assertEquals("c:42", key("Chapter 42: Epilogue of the War"))
    }

    @Test
    fun `flags and language tags`() {
        val p = ChapterLabelParser.parse("Chapter 10 [English] (Re-upload)")
        assertEquals("en", p.languageHint)
        assertTrue(LabelFlag.REUPLOAD in p.flags)
        assertTrue(LabelFlag.OFFICIAL in ChapterLabelParser.parse("Chapter 3 (Official)").flags)
    }

    @Test
    fun `structured numbers win over label text`() {
        assertEquals("c:7.5", key("Ch.7.5 - Omake", number = "7.5"))
        assertEquals("c:12", key("Something odd", number = "12"))
    }

    @Test
    fun `unparseable labels keep their own identity`() {
        val a = ChapterLabelParser.parse("The Beginning")
        assertEquals(ChapterKind.UNKNOWN, a.identity.kind)
        assertEquals("t:the-beginning", a.identity.key())
        assertNotEquals(key("The Beginning"), key("The End"))
    }

    @Test
    fun `titles keep original casing`() {
        assertEquals("The Return of the King", ChapterLabelParser.parse("Chapter 5: The Return of the King").title)
    }
}
