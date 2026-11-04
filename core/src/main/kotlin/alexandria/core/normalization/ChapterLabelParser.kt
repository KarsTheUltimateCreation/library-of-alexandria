package alexandria.core.normalization

import java.text.Normalizer

/**
 * Deterministic parser turning provider chapter labels into [ChapterIdentity].
 *
 * Design rules:
 *  - Never collapse numbers through floating point ("10.1" and "10.10" stay distinct).
 *  - Words in a chapter *title* never change the chapter kind: "Chapter 10: The Extra" is chapter 10.
 *  - When nothing reliable can be extracted the label text itself becomes the identity, so the chapter
 *    is kept (and merged only with an identically-labelled release) rather than guessed.
 */
object ChapterLabelParser {

    private val TAG = Regex("""[\[(（【]([^\[\]()（）【】]{1,40})[\])）】]""")
    private val SEPARATOR = Regex("""\s*(?::|：|\s-\s|\s–\s|\s—\s|\s\|\s)\s*""")

    private val SEASON_EPISODE = Regex("""\bs(\d{1,2})\s*e(\d{1,5}(?:[.,]\d{1,4})?)\b""")
    private val SEASON = Regex("""\b(?:season|s)\s*\.?\s*(\d{1,2})\b""")
    private val ARC_PART = Regex("""\bpart\s*\.?\s*(\d{1,2})\b(?=.*\b(?:chapter|chap|ch|episode|ep)\b)""")
    private val VOLUME = Regex("""\b(?:volume|vol)\s*\.?\s*(\d{1,4}(?:\.\d{1,2})?)\b""")
    private val CHAPTER = Regex("""\b(?:chapter|chap|ch|episode|ep|ep\.|#)\s*\.?\s*#?\s*(\d{1,6}(?:[.,]\d{1,4})?)(?![\d])""")
    private val HASH_NUMBER = Regex("""#\s*(\d{1,6}(?:[.,]\d{1,4})?)""")
    private val CJK_CHAPTER = Regex("""第\s*(\d{1,6}(?:\.\d{1,4})?)\s*[话話章回]""")
    private val KOREAN_CHAPTER = Regex("""(\d{1,6}(?:\.\d{1,4})?)\s*화""")
    private val LEADING_NUMBER = Regex("""^\s*(\d{1,6}(?:[.,]\d{1,4})?)(?![\d])""")
    private val ANY_NUMBER = Regex("""(?<![\d.])(\d{1,6}(?:[.,]\d{1,4})?)(?![\d])""")
    private val PART = Regex("""\b(?:part|pt)\s*\.?\s*(\d{1,2})\b""")
    private val PART_FRACTION = Regex("""\(\s*(\d{1,2})\s*/\s*\d{1,2}\s*\)""")

    private val CHAPTER_THEN_NUMBERED_KIND = Regex(
        """\b(?:chapter|chap|ch|episode|ep)\s*\.?\s*#?\s*\d{1,6}(?:[.,]\d{1,4})?\s*[-–—.]?\s*""" +
            """(?:side\s*stor(?:y|ies)|gaiden|spin[\s-]?off|extras?|bonus|omake|special|sp)\s*\.?\s*#?\s*\d{1,4}\b""",
    )

    private val KIND_WORDS: List<Pair<Regex, ChapterKind>> = listOf(
        Regex("""\b(?:side\s*story|side\s*stories|gaiden|spin[\s-]?off)\b""") to ChapterKind.SIDE_STORY,
        Regex("""\bprologue\b""") to ChapterKind.PROLOGUE,
        Regex("""\bepilogue\b""") to ChapterKind.EPILOGUE,
        Regex("""\bone[\s-]?shot\b""") to ChapterKind.ONESHOT,
        Regex("""\b(?:extra|extras|bonus|omake)\b""") to ChapterKind.EXTRA,
        Regex("""\b(?:special|sp)\b""") to ChapterKind.SPECIAL,
        Regex("""\b(?:notice|announcement|hiatus|q\s*&\s*a)\b""") to ChapterKind.NOTICE,
    )

    private val LANGUAGES = mapOf(
        "english" to "en", "eng" to "en", "en" to "en",
        "spanish" to "es", "español" to "es", "espanol" to "es", "es" to "es",
        "portuguese" to "pt-br", "português" to "pt-br", "pt-br" to "pt-br", "ptbr" to "pt-br",
        "french" to "fr", "français" to "fr", "fr" to "fr",
        "indonesian" to "id", "indo" to "id",
        "vietnamese" to "vi", "russian" to "ru", "german" to "de", "italian" to "it",
        "turkish" to "tr", "polish" to "pl", "arabic" to "ar", "thai" to "th",
        "korean" to "ko", "japanese" to "ja", "chinese" to "zh",
    )

    private val FLAG_WORDS: List<Pair<Regex, LabelFlag>> = listOf(
        Regex("""\b(?:re[\s-]?upload(?:ed)?|fixed|v\d)\b""") to LabelFlag.REUPLOAD,
        Regex("""\bofficial\b""") to LabelFlag.OFFICIAL,
        Regex("""\b(?:fan[\s-]?(?:tl|translation|translated)|scanlation)\b""") to LabelFlag.FAN,
        Regex("""\b(?:colou?red|full\s*colou?r)\b""") to LabelFlag.COLORED,
        Regex("""\braw\b""") to LabelFlag.RAW,
        Regex("""\b(?:preview|teaser)\b""") to LabelFlag.PREVIEW,
    )

    fun parse(label: String, structuredNumber: String? = null, structuredVolume: String? = null): ParsedLabel {
        val nfkc = Normalizer.normalize(label, Normalizer.Form.NFKC).replace(' ', ' ').trim()
        val lower = nfkc.lowercase()

        // 1. Bracketed tags carry language / release information, never numbering.
        val flags = mutableSetOf<LabelFlag>()
        var languageHint: String? = null
        val tagKinds = mutableListOf<ChapterKind>()
        // Where the chapter title starts ("Chapter 568: <title>"): tags inside the title, such as
        // "Ep. 568 (Spin-off #4)", describe the story, not the chapter kind.
        val titleStart = CHAPTER.find(lower)?.let { c -> SEPARATOR.find(lower, c.range.last + 1)?.range?.first } ?: Int.MAX_VALUE
        val withoutTagsOriginal = TAG.replace(nfkc) { m ->
            val inner = m.groupValues[1].trim().lowercase()
            LANGUAGES[inner]?.let { languageHint = it }
            FLAG_WORDS.forEach { (re, flag) -> if (re.containsMatchIn(inner)) flags += flag }
            if (m.range.first < titleStart) KIND_WORDS.forEach { (re, kind) -> if (re.containsMatchIn(inner)) tagKinds += kind }
            val partInTag = PART_FRACTION.find(m.value)
            when {
                partInTag != null -> m.value // keep "(1/2)" for part detection
                LANGUAGES.containsKey(inner) -> " "
                FLAG_WORDS.any { it.first.containsMatchIn(inner) } -> " "
                tagKinds.isNotEmpty() && KIND_WORDS.any { it.first.matches(inner) } -> " "
                else -> m.value
            }
        }.replace(Regex("\\s+"), " ").trim()
        val withoutTags = withoutTagsOriginal.lowercase()

        FLAG_WORDS.forEach { (re, flag) -> if (re.containsMatchIn(withoutTags.substringBefore(':'))) flags += flag }

        // 2. Season / arc.
        var season: Int? = null
        var number: ChapterNumber? = null
        var numberEnd = -1
        SEASON_EPISODE.find(withoutTags)?.let {
            season = it.groupValues[1].toInt()
            number = ChapterNumber.parse(it.groupValues[2].replace(',', '.'))
            numberEnd = it.range.last + 1
        }
        if (season == null) {
            // A season prefixes the chapter number ("Season 2 Chapter 10", "S2 Ep 10"). Mentions after it are part of
            // the title or a note ("Chapter 25: Refining (4) - Season 1 End", "Chapter 26 (S2 Start)").
            val chapterAt = CHAPTER.find(withoutTags)?.range?.first
            val seasonScope = if (chapterAt != null) withoutTags.substring(0, chapterAt) else SEPARATOR.split(withoutTags, 2).first()
            SEASON.find(seasonScope)?.let { season = it.groupValues[1].toInt() }
                ?: ARC_PART.find(withoutTags)?.let { season = it.groupValues[1].toInt() }
        }

        // 3. Volume (informational; only part of the key for volume-reset providers).
        val volume = structuredVolume?.trim()?.takeIf { it.isNotEmpty() }
            ?: VOLUME.find(withoutTags)?.groupValues?.get(1)

        // 4. Chapter number: structured > explicit keyword > CJK forms > leading number > lone number.
        if (structuredNumber != null) {
            ChapterNumber.parse(structuredNumber)?.let { number = it }
        }
        if (number == null) {
            val m = CHAPTER.find(withoutTags) ?: HASH_NUMBER.find(withoutTags)
                ?: CJK_CHAPTER.find(withoutTags) ?: KOREAN_CHAPTER.find(withoutTags)
            if (m != null) {
                number = ChapterNumber.parse(m.groupValues[1].replace(',', '.'))
                numberEnd = m.range.last + 1
            }
        }
        if (number == null) {
            // Strip season/volume tokens so "Vol. 3" is not mistaken for chapter 3.
            val stripped = withoutTags
                .let { SEASON.replace(it, " ") }
                .let { VOLUME.replace(it, " ") }
                .let { PART.replace(it, " ") }
            val m = LEADING_NUMBER.find(stripped)
            if (m != null) {
                number = ChapterNumber.parse(m.groupValues[1].replace(',', '.'))
            } else {
                val all = ANY_NUMBER.findAll(stripped.substringBefore(':')).toList()
                if (all.size == 1) number = ChapterNumber.parse(all[0].groupValues[1].replace(',', '.'))
            }
        }

        // 5. Split head / title. Kind words only count in the head (or tags), never in the title.
        val (head, lowerTitle) = splitHeadAndTitle(withoutTags, numberEnd)
        // Recover original casing for the title when lowercasing preserved the length (almost always).
        val title = if (lowerTitle != null && withoutTagsOriginal.length == withoutTags.length) {
            withoutTagsOriginal.substring(withoutTags.length - lowerTitle.length)
        } else {
            lowerTitle
        }
        var kind = tagKinds.firstOrNull()
        // "Chapter 200 Side Story 21": a kind word with its own number after "Chapter N" names a separate numbering
        // (side story #21 *is* chapter 200), so it is a subtitle, not the kind. "Chapter 10 Extra" stays an extra.
        if (kind == null && !CHAPTER_THEN_NUMBERED_KIND.containsMatchIn(head)) {
            kind = KIND_WORDS.firstOrNull { (re, _) -> re.containsMatchIn(head) }?.second
        }
        // A plain "Chapter 10: Special" title must not flip the kind; but "Special 2" has no chapter keyword.
        val hasChapterKeyword = CHAPTER.containsMatchIn(head) || SEASON_EPISODE.containsMatchIn(head)
        if (kind == ChapterKind.SPECIAL && hasChapterKeyword) kind = null
        if (kind == ChapterKind.NOTICE && hasChapterKeyword && number != null) kind = null
        // "Chapter 1 Prologue - 0th Regression": a numbered chapter that *is* the prologue, not a separate one.
        if ((kind == ChapterKind.PROLOGUE || kind == ChapterKind.EPILOGUE) && tagKinds.isEmpty() && number != null &&
            CHAPTER.find(head)?.let { c -> KIND_WORDS.first { it.second == kind }.first.find(head)?.let { k -> c.range.first < k.range.first } } == true
        ) {
            kind = null
        }

        // 6. Part numbers ("Chapter 10 Part 1", "Chapter 10 (1/2)"). An arc "Part 2 Chapter 10" was consumed as season.
        val part = PART_FRACTION.find(withoutTags)?.groupValues?.get(1)?.toInt()
            ?: PART.find(head)?.takeUnless { ARC_PART.containsMatchIn(withoutTags) }?.groupValues?.get(1)?.toInt()

        val resolvedKind = when {
            kind != null -> kind
            number != null -> ChapterKind.MAIN
            else -> ChapterKind.UNKNOWN
        }

        val identity = ChapterIdentity(
            kind = resolvedKind,
            number = number,
            part = part,
            season = season,
            volume = volume,
            text = if (resolvedKind == ChapterKind.UNKNOWN || (number == null && resolvedKind != ChapterKind.MAIN)) slug(withoutTags) else null,
        )
        return ParsedLabel(identity, title?.let { cleanTitle(it) }?.takeIf { it.isNotEmpty() }, flags, languageHint)
    }

    private fun splitHeadAndTitle(text: String, numberEnd: Int): Pair<String, String?> {
        val searchFrom = if (numberEnd in 0..text.length) numberEnd else 0
        val sep = SEPARATOR.find(text, searchFrom) ?: SEPARATOR.find(text)
        return if (sep != null && sep.range.first >= 1) {
            text.substring(0, sep.range.first) to text.substring(sep.range.last + 1)
        } else {
            text to null
        }
    }

    private fun cleanTitle(t: String): String = t.trim().trim('-', ':', '|', '–', '—', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    fun slug(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
        .trim('-')
        .take(80)
}
