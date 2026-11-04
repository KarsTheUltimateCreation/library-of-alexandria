package eu.kanade.tachiyomi.extension.all.libraryofalexandria

import alexandria.core.config.AggregatorSettings
import alexandria.core.config.CoverProvider
import alexandria.core.config.MetadataPreference
import alexandria.core.config.QualityMode
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

/**
 * Native source settings. Only controls present in extensions-lib 1.6 are used (SwitchPreferenceCompat,
 * ListPreference, MultiSelectListPreference, EditTextPreference); the stubs have no PreferenceCategory, so
 * groups are expressed with title prefixes. Values are read on every call, so changes apply immediately.
 */
object Settings {

    enum class Action { CLEAR_METADATA, CLEAR_LINKS, CLEAR_ALL }

    // Covers
    private const val COVER_HQ = "cover_hq"
    private const val COVER_PROVIDER = "cover_provider"
    private const val COVER_ORIGINAL = "cover_original"
    private const val COVER_FALLBACK = "cover_fallback"

    // Chapter image quality
    private const val QUALITY = "quality_opt"
    private const val QUALITY_MODE = "quality_mode"
    private const val QUALITY_ORIGINAL = "quality_original"
    private const val QUALITY_SWITCH = "quality_switch"

    // Aggregation
    private const val DEDUPE = "agg_dedupe"
    private const val RECOVERY = "agg_recovery"
    private const val FALLBACK = "agg_fallback"
    private const val SPECIALS = "agg_specials"
    private const val ALT_TL = "agg_alt_tl"
    private const val OFFICIAL = "agg_official"
    private const val PREFERRED_SOURCE = "agg_preferred_source"

    // Metadata
    private const val ANILIST = "meta_anilist"
    private const val MANGABAKA = "meta_mangabaka"
    private const val META_PREF = "meta_preferred"
    private const val COMPLETENESS = "meta_completeness"

    // Content
    private const val ADULT = "content_adult"

    // Sources
    private const val ENABLED = "src_enabled"
    /** Provider ids that existed when the user last saved [ENABLED]; providers added later start enabled. */
    private const val KNOWN = "src_known"
    /** Providers shipped before [KNOWN] was introduced (1.6.4). */
    private val LEGACY_SOURCES = setOf("mangadex", "weebcentral", "mangapill", "mangakatana", "mangaread", "mgeko", "atsumaru")
    private const val ORDER = "src_order"
    private const val LINKS = "src_manual_links"

    // Performance
    private const val CONCURRENCY = "perf_concurrency"
    private const val TIMEOUT = "perf_timeout"
    private const val META_CACHE = "perf_meta_cache"
    private const val COVER_CACHE = "perf_cover_cache"
    private const val CACHE_ACTION = "perf_cache_action"
    private const val NONE = "__none__"

    fun read(p: SharedPreferences, allSources: List<String>): AggregatorSettings {
        val d = AggregatorSettings()
        fun b(k: String, def: Boolean) = runCatching { p.getBoolean(k, def) }.getOrDefault(def)
        fun s(k: String, def: String) = runCatching { p.getString(k, def) }.getOrNull() ?: def
        fun list(k: String, def: String) = s(k, def).split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return AggregatorSettings(
            highQualityCovers = b(COVER_HQ, d.highQualityCovers),
            coverProvider = enumOr(s(COVER_PROVIDER, "AUTO"), CoverProvider.AUTO),
            preferOriginalArtwork = b(COVER_ORIGINAL, d.preferOriginalArtwork),
            coverFallback = b(COVER_FALLBACK, d.coverFallback),
            qualityOptimization = b(QUALITY, d.qualityOptimization),
            qualityMode = enumOr(s(QUALITY_MODE, "BEST"), QualityMode.BEST),
            preferOriginalResolution = b(QUALITY_ORIGINAL, d.preferOriginalResolution),
            qualitySwitching = b(QUALITY_SWITCH, d.qualitySwitching),
            deduplicate = b(DEDUPE, d.deduplicate),
            missingChapterRecovery = b(RECOVERY, d.missingChapterRecovery),
            sourceFallback = b(FALLBACK, d.sourceFallback),
            includeSpecials = b(SPECIALS, d.includeSpecials),
            showAlternativeTranslations = b(ALT_TL, d.showAlternativeTranslations),
            preferOfficial = b(OFFICIAL, d.preferOfficial),
            sourceOrder = list(ORDER, ""),
            preferredSource = s(PREFERRED_SOURCE, "auto").takeUnless { it == "auto" || it.isBlank() },
            languages = listOf("en"), // the extension is English-only
            anilist = b(ANILIST, d.anilist),
            mangabaka = b(MANGABAKA, d.mangabaka),
            metadataPreference = enumOr(s(META_PREF, "AUTO"), MetadataPreference.AUTO),
            completenessChecking = b(COMPLETENESS, d.completenessChecking),
            adultContent = b(ADULT, d.adultContent),
            enabledSources = enabledSources(p, allSources),
            manualLinks = AggregatorSettings.parseManualLinks(s(LINKS, "")),
            maxConcurrentSearches = s(CONCURRENCY, "3").toIntOrNull()?.coerceIn(1, 6) ?: 3,
            requestTimeoutSeconds = s(TIMEOUT, "20").toIntOrNull()?.coerceIn(5, 120) ?: 20,
            metadataCacheHours = s(META_CACHE, "72").toIntOrNull()?.coerceIn(1, 24 * 30) ?: 72,
            cacheCovers = b(COVER_CACHE, d.cacheCovers),
        )
    }

    /**
     * Never-configured = all providers; an explicitly emptied selection must mean none, not all. Providers added
     * after the selection was saved are enabled, so a saved list from an older version does not hide them.
     */
    private fun enabledSources(p: SharedPreferences, allSources: List<String>): Set<String> {
        val stored = runCatching { p.getStringSet(ENABLED, null) }.getOrNull()?.toSet() ?: return emptySet()
        val known = runCatching { p.getStringSet(KNOWN, null) }.getOrNull()?.toSet() ?: LEGACY_SOURCES
        return (stored + (allSources - known)).ifEmpty { setOf(NONE) }
    }

    private inline fun <reified E : Enum<E>> enumOr(v: String, def: E): E = runCatching { enumValueOf<E>(v.uppercase()) }.getOrDefault(def)

    fun build(screen: PreferenceScreen, p: SharedPreferences, sources: List<Pair<String, String>>, adultSources: Set<String>, onAction: (Action) -> Unit) {
        val ctx = screen.context

        fun switch(key: String, title: String, summary: String, def: Boolean) = screen.addPreference(
            SwitchPreferenceCompat(ctx).apply {
                this.key = key; this.title = title; this.summary = summary; setDefaultValue(def)
            },
        )

        fun list(key: String, title: String, entries: List<Pair<String, String>>, def: String, summaryPrefix: String = "") = screen.addPreference(
            ListPreference(ctx).apply {
                this.key = key
                this.title = title
                this.entries = entries.map { it.second }.toTypedArray()
                entryValues = entries.map { it.first }.toTypedArray()
                setDefaultValue(def)
                val current = p.getString(key, def)
                summary = summaryPrefix + (entries.firstOrNull { it.first == current }?.second ?: "")
                setOnPreferenceChangeListener { pref, value ->
                    pref.summary = summaryPrefix + (entries.firstOrNull { it.first == value }?.second ?: value.toString())
                    true
                }
            },
        )

        fun text(key: String, title: String, summary: String, def: String) = screen.addPreference(
            EditTextPreference(ctx).apply {
                this.key = key
                this.title = title
                this.summary = summary + "\nCurrent: " + (p.getString(key, def)?.ifBlank { "(none)" } ?: def)
                dialogTitle = title
                setDefaultValue(def)
                setOnPreferenceChangeListener { pref, value ->
                    pref.summary = summary + "\nCurrent: " + value.toString().ifBlank { "(none)" }
                    true
                }
            },
        )

        // ---- Cover images
        switch(COVER_HQ, "Covers · High-quality covers", "Pick the best authentic artwork from MangaBaka, AniList, MangaDex and linked sources. Off: use the primary source's cover.", true)
        list(
            COVER_PROVIDER, "Covers · Cover provider",
            listOf("AUTO" to "Auto — best available", "MANGABAKA" to "MangaBaka", "ANILIST" to "AniList", "MANGADEX" to "MangaDex", "PRIMARY_SOURCE" to "Primary source"),
            "AUTO",
        )
        switch(COVER_ORIGINAL, "Covers · Prefer original artwork", "Rank original-language covers above translated edition covers.", true)
        switch(COVER_FALLBACK, "Covers · Automatic cover fallback", "If the chosen cover fails to load, try the next candidate.", true)

        // ---- Chapter image quality
        switch(QUALITY, "Quality · Quality optimization", "Compare providers per chapter (page count, image size) before reading.", true)
        list(
            QUALITY_MODE, "Quality · Quality mode",
            listOf("BEST" to "Best available", "BALANCED" to "Balanced", "DATA_SAVER" to "Data saver"),
            "BEST",
        )
        switch(QUALITY_ORIGINAL, "Quality · Prefer original resolution", "Favour higher-resolution pages when chapters are equally complete.", true)
        switch(QUALITY_SWITCH, "Quality · Quality-based source switching", "Allow a different provider per chapter when it has the better release.", true)

        // ---- Chapter aggregation
        switch(DEDUPE, "Chapters · Automatic deduplication", "Merge the same chapter from different providers into one entry (alternatives are kept for fallback).", true)
        switch(RECOVERY, "Chapters · Missing chapter recovery", "Fill chapters missing from the primary provider using other providers.", true)
        switch(FALLBACK, "Chapters · Automatic source fallback", "If a provider fails, load the chapter (or a failing page) from another provider.", true)
        switch(SPECIALS, "Chapters · Include special chapters", "Extras, side stories, specials and notices.", true)
        switch(ALT_TL, "Chapters · Show alternative translations", "List each translation group's release separately.", false)
        switch(OFFICIAL, "Chapters · Prefer official releases", "Rank releases marked official above fan translations.", true)
        list(PREFERRED_SOURCE, "Chapters · Preferred source", listOf("auto" to "Automatic") + sources, "auto")

        // ---- Metadata
        switch(ANILIST, "Metadata · AniList integration", "Titles, status and identity matching from AniList.", true)
        switch(MANGABAKA, "Metadata · MangaBaka integration", "Titles, cover art, chapter counts and cross-database ids from MangaBaka.", true)
        list(META_PREF, "Metadata · Preferred metadata provider", listOf("AUTO" to "Automatic", "MANGABAKA" to "MangaBaka", "ANILIST" to "AniList"), "AUTO")
        switch(COMPLETENESS, "Metadata · Chapter completeness checking", "Report gaps and untranslated chapters in the series description.", true)

        // ---- Content
        switch(
            ADULT, "Content · Show 18+ content",
            "Include pornographic titles (adult manhwa, hentai, doujinshi, erotica) in search, browse and filters, and use the " +
                "adult providers: ${sources.filter { it.first in adultSources }.joinToString { it.second }}.",
            false,
        )

        // ---- Sources
        screen.addPreference(
            MultiSelectListPreference(ctx).apply {
                key = ENABLED
                title = "Sources · Enabled providers"
                summary = "Disabled providers are never searched or used for fallback. Adult providers also need 18+ content enabled."
                entries = sources.map { it.second }.toTypedArray()
                entryValues = sources.map { it.first }.toTypedArray()
                // Show providers added since the selection was saved as enabled, matching read().
                val current = enabledSources(p, sources.map { it.first }).ifEmpty { sources.map { it.first }.toSet() }
                setDefaultValue(sources.map { it.first }.toSet())
                if (p.contains(ENABLED)) values = current
                setOnPreferenceChangeListener { _, _ ->
                    p.edit().putStringSet(KNOWN, sources.map { it.first }.toSet()).apply()
                    true
                }
            },
        )
        text(ORDER, "Sources · Provider order", "Comma-separated provider ids, most preferred first. Available: ${sources.joinToString { it.first }}", sources.joinToString(", ") { it.first })
        text(
            LINKS, "Sources · Manual series links",
            "Override automatic matching per series: <series> = <provider>:<id>, <provider>:- ; separate entries with ';'. Example: mb:3397 = weebcentral:01J76XYCPSY3C4BNPBRY8JMCBE",
            "",
        )

        // ---- Performance
        list(CONCURRENCY, "Performance · Concurrent provider requests", (1..6).map { "$it" to "$it" }, "3")
        list(TIMEOUT, "Performance · Request timeout", listOf("10" to "10 s", "20" to "20 s", "30" to "30 s", "60" to "60 s"), "20")
        list(META_CACHE, "Performance · Metadata cache duration", listOf("6" to "6 hours", "24" to "1 day", "72" to "3 days", "168" to "1 week", "720" to "30 days"), "72")
        switch(COVER_CACHE, "Performance · Cache cover choices", "Remember the chosen cover for 7 days instead of re-evaluating on every refresh.", true)
        screen.addPreference(
            ListPreference(ctx).apply {
                key = CACHE_ACTION
                title = "Performance · Clear cache"
                summary = "Pick what to clear. Takes effect immediately."
                entries = arrayOf("Metadata and covers", "Provider links and chapter choices", "Everything (incl. provider health)")
                entryValues = arrayOf(Action.CLEAR_METADATA.name, Action.CLEAR_LINKS.name, Action.CLEAR_ALL.name)
                setOnPreferenceChangeListener { pref, value ->
                    runCatching { onAction(Action.valueOf(value.toString())) }
                    pref.summary = "Cleared: " + (entries.getOrNull(findIndexOfValue(value.toString())) ?: value)
                    false // nothing to persist
                }
            },
        )
    }
}
