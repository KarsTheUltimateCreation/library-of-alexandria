package eu.kanade.tachiyomi.extension.all.libraryofalexandria

import alexandria.core.config.AggregatorSettings
import alexandria.core.config.MetadataPreference
import alexandria.core.config.QualityMode
import alexandria.core.config.SourcePolicy
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

/**
 * Native source settings, kept to the choices that actually change what users see. Everything else (deduplication,
 * missing-chapter recovery, fallback, timeouts, concurrency, caching) is always on and tunes itself. Explicit-content
 * providers sit behind the 18+ switch (off by default). Only controls present in extensions-lib 1.6 are used. Values
 * are read on every call, so changes apply immediately.
 */
object Settings {

    enum class Action { CLEAR_METADATA, CLEAR_LINKS, CLEAR_ALL }

    private const val COVER_HQ = "cover_hq"
    private const val QUALITY_LEVEL = "quality_level"
    private const val SPECIALS = "agg_specials"
    private const val ALT_TL = "agg_alt_tl"
    private const val PREFERRED_SOURCE = "agg_preferred_source"
    /**
     * External metadata (1.6.7+). A new key on purpose: earlier versions had no "off" choice, so their stored value
     * (`meta_source`) says nothing about whether the user wants these services; everyone starts provider-only.
     */
    private const val META_MODE = "meta_mode"
    private const val META_OFF = "OFF"
    private const val COMPLETENESS = "meta_completeness"
    private const val ENABLED = "src_enabled"
    /** Provider ids that existed when the user last saved [ENABLED]; providers added later start enabled. */
    private const val KNOWN = "src_known"
    private const val LINKS = "src_manual_links"
    /**
     * Shows the explicit-content providers in [ADULT_SOURCES]. Off: they are never searched or read from. Exactly these
     * six: Hiperdex, Omega Scans, ManhwaRead and mixed providers follow their own source checkbox (see [SourcePolicy]).
     */
    private const val ADULT = "src_adult"
    val ADULT_SOURCES = SourcePolicy.EXPLICIT_SOURCES
    private const val DEEP = "src_deep_discovery"
    private const val CACHE_ACTION = "perf_cache_action"

    // Settings from 1.6.4 and earlier, read once as defaults for the merged choices above.
    private const val OLD_QUALITY = "quality_opt"
    private const val OLD_QUALITY_MODE = "quality_mode"

    private const val QUALITY_OFF = "OFF"

    fun read(p: SharedPreferences, allSources: List<String>): AggregatorSettings {
        val d = AggregatorSettings()
        fun b(k: String, def: Boolean) = runCatching { p.getBoolean(k, def) }.getOrDefault(def)
        fun s(k: String, def: String) = runCatching { p.getString(k, def) }.getOrNull() ?: def
        val quality = s(QUALITY_LEVEL, legacyQuality(p))
        val metaSource = s(META_MODE, META_OFF)
        val adult = b(ADULT, false)
        val hidden = SourcePolicy.hidden(adult)
        return d.copy(
            highQualityCovers = b(COVER_HQ, d.highQualityCovers),
            qualityOptimization = quality != QUALITY_OFF,
            qualityMode = enumOr(quality, QualityMode.BEST),
            includeSpecials = b(SPECIALS, d.includeSpecials),
            showAlternativeTranslations = b(ALT_TL, d.showAlternativeTranslations),
            preferredSource = SourcePolicy.preferred(s(PREFERRED_SOURCE, "auto"), hidden),
            languages = listOf("en"), // the extension is English-only
            mangabaka = metaSource == "AUTO" || metaSource == "MANGABAKA",
            anilist = metaSource == "AUTO" || metaSource == "ANILIST",
            metadataPreference = enumOr(metaSource, MetadataPreference.AUTO),
            completenessChecking = b(COMPLETENESS, d.completenessChecking),
            enabledSources = SourcePolicy.effective(enabledSources(p, allSources), allSources, hidden),
            manualLinks = AggregatorSettings.parseManualLinks(s(LINKS, "")),
            deepDiscovery = b(DEEP, d.deepDiscovery),
        )
    }

    private fun legacyQuality(p: SharedPreferences): String = runCatching {
        if (!p.getBoolean(OLD_QUALITY, true)) QUALITY_OFF else p.getString(OLD_QUALITY_MODE, "BEST") ?: "BEST"
    }.getOrDefault("BEST")

    /**
     * Never-configured = all providers; an explicitly emptied selection must mean none, not all. Providers added
     * after the selection was saved are enabled, so a saved list from an older version does not hide them.
     */
    private fun enabledSources(p: SharedPreferences, allSources: List<String>): Set<String> = SourcePolicy.selected(
        stored = runCatching { p.getStringSet(ENABLED, null) }.getOrNull()?.toSet(),
        known = runCatching { p.getStringSet(KNOWN, null) }.getOrNull()?.toSet(),
        allSources = allSources,
    )

    private inline fun <reified E : Enum<E>> enumOr(v: String, def: E): E = runCatching { enumValueOf<E>(v.uppercase()) }.getOrDefault(def)

    fun build(screen: PreferenceScreen, p: SharedPreferences, allSources: List<Pair<String, String>>, onAction: (Action) -> Unit) {
        val ctx = screen.context
        val adult = runCatching { p.getBoolean(ADULT, false) }.getOrDefault(false)
        // Explicit providers are not listed while 18+ is off; their saved on/off choices are kept for when it is on.
        val sources = if (adult) allSources else allSources.filter { it.first !in ADULT_SOURCES }
        val allIds = allSources.map { it.first }

        fun switch(key: String, title: String, summary: String, def: Boolean) = screen.addPreference(
            SwitchPreferenceCompat(ctx).apply {
                this.key = key; this.title = title; this.summary = summary; setDefaultValue(def)
            },
        )

        /** A list whose summary shows the current choice and its explanation. */
        fun list(key: String, title: String, entries: List<Triple<String, String, String>>, def: String) = screen.addPreference(
            ListPreference(ctx).apply {
                this.key = key
                this.title = title
                this.entries = entries.map { it.second }.toTypedArray()
                entryValues = entries.map { it.first }.toTypedArray()
                setDefaultValue(def)
                fun describe(v: Any?) = entries.firstOrNull { it.first == v }?.let { e -> listOf(e.second, e.third).filter { it.isNotEmpty() }.joinToString(" — ") } ?: v.toString()
                summary = describe(p.getString(key, def))
                setOnPreferenceChangeListener { pref, value ->
                    pref.summary = describe(value)
                    true
                }
            },
        )

        switch(COVER_HQ, "High-quality covers", "Compare the linked sources' covers (and the metadata service's, when enabled) and pick the best.", true)

        list(
            QUALITY_LEVEL, "Reading quality",
            listOf(
                Triple("BEST", "Best", "compare sources per chapter, pick the sharpest complete release"),
                Triple("BALANCED", "Balanced", "lighter comparison"),
                Triple("DATA_SAVER", "Data saver", "smaller images where available"),
                Triple(QUALITY_OFF, "Fastest", "open the first source that works, no comparison"),
            ),
            legacyQuality(p),
        )

        list(
            PREFERRED_SOURCE, "Preferred source",
            listOf(Triple("auto", "Automatic", "best source per chapter")) + sources.map { Triple(it.first, it.second, "") },
            "auto",
        )
        switch(SPECIALS, "Special chapters", "Include extras, side stories and notices.", true)
        switch(ALT_TL, "Separate translations", "List each translation group's release as its own chapter.", false)

        list(
            META_MODE, "External metadata",
            listOf(
                Triple(META_OFF, "Off", "sources only; no requests to metadata services (recommended)"),
                Triple("AUTO", "MangaBaka + AniList", "adds catalogue details, filters and popular lists; rate-limited services"),
                Triple("MANGABAKA", "MangaBaka only", ""),
                Triple("ANILIST", "AniList only", ""),
            ),
            META_OFF,
        )
        switch(COMPLETENESS, "Source report", "End the description with linked sources, recovered and missing chapters.", true)

        screen.addPreference(
            MultiSelectListPreference(ctx).apply {
                key = ENABLED
                title = "Sources"
                summary = "Providers to search and read from."
                entries = sources.map { it.second }.toTypedArray()
                entryValues = sources.map { it.first }.toTypedArray()
                // Show providers added since the selection was saved as enabled, matching read().
                val current = enabledSources(p, allIds).ifEmpty { allIds.toSet() }
                setDefaultValue(allIds.toSet())
                if (p.contains(ENABLED)) values = current.filter { id -> sources.any { it.first == id } }.toSet()
                setOnPreferenceChangeListener { pref, value ->
                    @Suppress("UNCHECKED_CAST")
                    val chosen = (value as? Set<String>).orEmpty()
                    // Keep the choices for providers this list does not show (18+ off).
                    val listed = sources.map { it.first }
                    val saved = SourcePolicy.saveEdit(chosen, enabledSources(p, allIds), allIds, listed)
                    (pref as MultiSelectListPreference).values = chosen
                    p.edit().putStringSet(ENABLED, saved).putStringSet(KNOWN, allIds.toSet()).apply()
                    false // saved above, together with the unlisted choices
                }
            },
        )
        switch(
            ADULT, "18+ content",
            "Include explicit providers (Hentai20, Hentai2Read, nhentai, Hitomi, E-Hentai, Pururin). Reopen settings to see them in the source lists.",
            false,
        )
        switch(
            DEEP, "Search every source",
            "Look for each series on all enabled sources when it is opened (slower). Off: the most promising sources first, the rest when chapters are missing or a source fails.",
            false,
        )
        screen.addPreference(
            EditTextPreference(ctx).apply {
                key = LINKS
                title = "Manual series links"
                val help = "Link or unlink sources by hand: <series> = <provider>:<id>, <provider>:- ; entries separated by ';'. " +
                    "<series> is the entry's id (p:<provider>:<id>, or mb:/al: for older entries). " +
                    "Example: p:mangapill:723/one-piece = weebcentral:01J76XYCPSY3C4BNPBRY8JMCBE, mangadex:-"
                fun describe(v: String?) = if (v.isNullOrBlank()) help else "$help\nCurrent: $v"
                summary = describe(p.getString(LINKS, ""))
                dialogTitle = title
                setDefaultValue("")
                setOnPreferenceChangeListener { pref, value ->
                    pref.summary = describe(value?.toString())
                    true
                }
            },
        )

        screen.addPreference(
            ListPreference(ctx).apply {
                key = CACHE_ACTION
                title = "Clear cache"
                summary = "Fixes stale covers, details or wrong matches."
                entries = arrayOf("Covers and details", "Source links and chapter choices", "Everything")
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
