package alexandria.core.config

enum class QualityMode { BEST, BALANCED, DATA_SAVER }

enum class MetadataPreference { AUTO, MANGABAKA, ANILIST }

enum class CoverProvider { AUTO, MANGABAKA, ANILIST, MANGADEX, PRIMARY_SOURCE }

/**
 * Snapshot of every user-configurable behaviour. The extension rebuilds it from the host's source
 * preferences on each call, so changes apply without restarting the app.
 */
data class AggregatorSettings(
    // Covers
    val highQualityCovers: Boolean = true,
    val coverProvider: CoverProvider = CoverProvider.AUTO,
    val preferOriginalArtwork: Boolean = true,
    val coverFallback: Boolean = true,

    // Chapter image quality
    val qualityOptimization: Boolean = true,
    val qualityMode: QualityMode = QualityMode.BEST,
    val preferOriginalResolution: Boolean = true,
    val qualitySwitching: Boolean = true,

    // Aggregation
    val deduplicate: Boolean = true,
    val missingChapterRecovery: Boolean = true,
    val sourceFallback: Boolean = true,
    val includeSpecials: Boolean = true,
    val showAlternativeTranslations: Boolean = false,
    val preferOfficial: Boolean = true,
    /** Ordered provider ids; earlier is preferred. Unlisted enabled providers follow in registry order. */
    val sourceOrder: List<String> = emptyList(),
    /** Preferred source for every chapter, or null for automatic. */
    val preferredSource: String? = null,
    /** Ordered languages, first is primary. Empty = no filtering. */
    val languages: List<String> = listOf("en"),

    // Metadata
    val anilist: Boolean = true,
    val mangabaka: Boolean = true,
    val metadataPreference: MetadataPreference = MetadataPreference.AUTO,
    val completenessChecking: Boolean = true,

    // Sources
    val enabledSources: Set<String> = emptySet(),
    /** Manual links: canonical series key -> list of "sourceId:seriesId" (or "sourceId:-" to unlink). */
    val manualLinks: Map<String, List<String>> = emptyMap(),
    /**
     * Search every relevant provider when linking a series (the user asked for thorough discovery). Off: the most
     * promising healthy providers are searched first and the rest only when coverage is missing or sources fail.
     */
    val deepDiscovery: Boolean = false,

    // Performance (not user-facing: timeouts adapt to each provider's measured latency, see ProviderHealth)
    val maxConcurrentSearches: Int = 6,
    /** Upper bound for one provider call; healthy, fast providers get a much shorter adaptive timeout. */
    val requestTimeoutSeconds: Int = 20,
    val metadataCacheHours: Int = 72,
    val chapterCacheMinutes: Int = 30,
    val cacheCovers: Boolean = true,
) {
    fun sourceRank(sourceId: String): Int {
        if (preferredSource == sourceId) return -1
        val i = sourceOrder.indexOf(sourceId)
        return if (i >= 0) i else sourceOrder.size + 1
    }

    fun languageRank(language: String): Int {
        if (languages.isEmpty()) return 0
        val i = languages.indexOfFirst { it.equals(language, ignoreCase = true) || language.lowercase().startsWith(it.lowercase() + "-") }
        return if (i >= 0) i else Int.MAX_VALUE
    }

    fun acceptsLanguage(language: String): Boolean = languages.isEmpty() || languageRank(language) != Int.MAX_VALUE

    /**
     * Identifies every input of per-chapter source selection. Cached chapter choices and series preferences are
     * stored under it, so changing the preferred source, reading quality, provider order or enabled providers
     * makes the next opening choose again instead of reusing a decision made under other settings.
     */
    fun selectionFingerprint(): String = listOf(
        SELECTION_VERSION, preferredSource, qualityOptimization, qualitySwitching, qualityMode, preferOriginalResolution,
        preferOfficial, sourceOrder, enabledSources.sorted(), languages,
    ).joinToString("|").let { Integer.toHexString(it.hashCode()) }

    companion object {
        /** Bump when the selection algorithm changes so decisions made by the old one are not reused. */
        const val SELECTION_VERSION = 2

        /** Parses "mb:123 = mangadex:abc, weebcentral:-; al:5 = mangapill:12/x" style manual links. */
        fun parseManualLinks(text: String?): Map<String, List<String>> {
            if (text.isNullOrBlank()) return emptyMap()
            return text.split(';', '\n')
                .mapNotNull { line ->
                    val parts = line.split('=', limit = 2)
                    if (parts.size != 2) return@mapNotNull null
                    val key = parts[0].trim()
                    val targets = parts[1].split(',').map { it.trim() }.filter { it.contains(':') }
                    if (key.isEmpty() || targets.isEmpty()) null else key to targets
                }
                .toMap()
        }
    }
}
