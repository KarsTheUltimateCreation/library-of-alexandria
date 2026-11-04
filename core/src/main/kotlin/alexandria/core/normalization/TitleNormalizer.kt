package alexandria.core.normalization

import java.text.Normalizer

/** Title canonicalization and similarity. Pure and deterministic. */
object TitleNormalizer {

    /**
     * Words that mark a *different edition or adaptation* of a work. A candidate that carries one of these
     * when the reference title does not is almost certainly a different entry ("Solo Leveling Novel").
     */
    val EDITION_QUALIFIERS: Map<Regex, String> = linkedMapOf(
        Regex("""\b(?:light\s*novel|web\s*novel|novel|ln|wn)\b""") to "novel",
        Regex("""\bvolume\s*version\b|\(volume\)""") to "volume-version",
        Regex("""\b(?:fan\s*)?colou?red(?:\s*edition)?\b|\bfull\s*colou?r\b""") to "colored",
        Regex("""\bdoujin(?:shi)?\b""") to "doujinshi",
        Regex("""\banthology\b""") to "anthology",
        Regex("""\bpilot\b""") to "pilot",
        Regex("""\bone[\s-]?shot\b""") to "oneshot",
        Regex("""\b(?:remake|reboot)\b""") to "remake",
        Regex("""\b(?:side\s*stor(?:y|ies)|gaiden|spin[\s-]?off)\b""") to "spinoff",
        Regex("""\b(?:season|part)\s*\d+\b|\b\d+(?:st|nd|rd|th)\s*season\b""") to "season",
        Regex("""\b(?:raw|raws)\b""") to "raw",
        Regex("""\b(?:official)\b""") to "official",
    )

    /** Qualifiers that only describe the release, not a different work. */
    private val NEUTRAL_QUALIFIERS = setOf("official", "raw")

    fun normalize(title: String): String {
        val nfkc = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase()
        val noMarks = Normalizer.normalize(nfkc, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        return noMarks
            .replace("&", " and ")
            .replace(Regex("['’`´]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Normalized title without spaces; robust against "Re:Zero" vs "Re Zero" vs "ReZero". */
    fun compact(title: String): String = normalize(title).replace(" ", "")

    fun qualifiers(title: String): Set<String> {
        val t = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase()
        return EDITION_QUALIFIERS.filter { (re, _) -> re.containsMatchIn(t) }.values.toSet()
    }

    /**
     * Qualifiers present in [candidate] but absent from every title of the reference work.
     * "official"/"raw" are ignored because they describe a release rather than a different work.
     */
    fun extraQualifiers(candidate: String, referenceTitles: Collection<String>): Set<String> {
        val reference = referenceTitles.flatMap { qualifiers(it) }.toSet()
        return qualifiers(candidate) - reference - NEUTRAL_QUALIFIERS
    }

    /** Similarity in [0, 1]. 1.0 means equal after normalization. */
    fun similarity(a: String, b: String): Double {
        val ca = compact(a)
        val cb = compact(b)
        if (ca.isEmpty() || cb.isEmpty()) return 0.0
        if (ca == cb) return 1.0
        val edit = 1.0 - levenshtein(ca, cb).toDouble() / maxOf(ca.length, cb.length)
        val ta = normalize(a).split(' ').filter { it.isNotEmpty() }.toSet()
        val tb = normalize(b).split(' ').filter { it.isNotEmpty() }.toSet()
        val jaccard = if (ta.isEmpty() || tb.isEmpty()) 0.0 else (ta intersect tb).size.toDouble() / (ta union tb).size
        // Token-sort ratio handles reordered words ("Level Up Solo" vs "Solo Level Up").
        val sortedA = ta.sorted().joinToString("")
        val sortedB = tb.sorted().joinToString("")
        val sorted = if (sortedA.isEmpty() || sortedB.isEmpty()) 0.0 else 1.0 - levenshtein(sortedA, sortedB).toDouble() / maxOf(sortedA.length, sortedB.length)
        return maxOf(edit, sorted * 0.98, jaccard * 0.95)
    }

    /** Best pairwise similarity between two title sets, with the pair that produced it. */
    fun bestMatch(a: Collection<String>, b: Collection<String>): Triple<Double, String?, String?> {
        var best = 0.0
        var bestA: String? = null
        var bestB: String? = null
        for (x in a) for (y in b) {
            val s = similarity(x, y)
            if (s > best) {
                best = s; bestA = x; bestB = y
            }
            if (best == 1.0) return Triple(best, bestA, bestB)
        }
        return Triple(best, bestA, bestB)
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = curr; curr = t
        }
        return prev[b.length]
    }
}
