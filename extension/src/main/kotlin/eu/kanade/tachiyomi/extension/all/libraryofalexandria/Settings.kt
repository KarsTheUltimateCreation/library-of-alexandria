package eu.kanade.tachiyomi.extension.all.libraryofalexandria

import alexandria.core.config.AggregatorSettings
import alexandria.core.config.MetadataPreference
import alexandria.core.config.QualityMode
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

/**
 * Native source settings, kept to the choices that actually change what users see. Everything else (deduplication,
 * missing-chapter recovery, fallback, timeouts, concurrency, caching) is always on and tunes itself; 18+ content is
 * always included. Only controls present in extensions-lib 1.6 are used. Values are read on every call, so changes
 * apply immediately.
 */
object Settings {

    enum class Action { CLEAR_METADATA, CLEAR_LINKS, CLEAR_ALL }

    private const val COVER_HQ = "cover_hq"
    private const val QUALITY_LEVEL = "quality_level"
    private const val SPECIALS = "agg_specials"
    private const val ALT_TL = "agg_alt_tl"
    private const val PREFERRED_SOURCE = "agg_preferred_source"
    private const val META_SOURCE = "meta_source"
    private const val COMPLETENESS = "meta_completeness"
    private const val ENABLED = "src_enabled"
    /** Provider ids that existed when the user last saved [ENABLED]; providers added later start enabled. */
    private const val KNOWN = "src_known"
    /** Providers shipped before [KNOWN] was introduced (1.6.4). */
    private val LEGACY_SOURCES = setOf("mangadex", "weebcentral", "mangapill", "mangakatana", "mangaread", "mgeko", "atsumaru")
    private const val LINKS = "src_manual_links"
    private const val CACHE_ACTION = "perf_cache_action"
    private const val NONE = "__none__"

    // Settings from 1.6.4 and earlier, read once as defaults for the merged choices above.
    private const val OLD_QUALITY = "quality_opt"
    private const val OLD_QUALITY_MODE = "quality_mode"
    private const val OLD_ANILIST = "meta_anilist"
    private const val OLD_MANGABAKA = "meta_mangabaka"

    private const val QUALITY_OFF = "OFF"

    fun read(p: SharedPreferences, allSources: List<String>): AggregatorSettings {
        val d = AggregatorSettings()
        fun b(k: String, def: Boolean) = runCatching { p.getBoolean(k, def) }.getOrDefault(def)
        fun s(k: String, def: String) = runCatching { p.getString(k, def) }.getOrNull() ?: def
        val quality = s(QUALITY_LEVEL, legacyQuality(p))
        val metaSource = s(META_SOURCE, legacyMetaSource(p))
        return d.copy(
            highQualityCovers = b(COVER_HQ, d.highQualityCovers),
            qualityOptimization = quality != QUALITY_OFF,
            qualityMode = enumOr(quality, QualityMode.BEST),
            includeSpecials = b(SPECIALS, d.includeSpecials),
            showAlternativeTranslations = b(ALT_TL, d.showAlternativeTranslations),
            preferredSource = s(PREFERRED_SOURCE, "auto").takeUnless { it == "auto" || it.isBlank() },
            languages = listOf("en"), // the extension is English-only
            mangabaka = metaSource != "ANILIST",
            anilist = metaSource != "MANGABAKA",
            metadataPreference = enumOr(metaSource, MetadataPreference.AUTO),
            completenessChecking = b(COMPLETENESS, d.completenessChecking),
            enabledSources = enabledSources(p, allSources),
            manualLinks = AggregatorSettings.parseManualLinks(s(LINKS, "")),
        )
    }

    private fun legacyQuality(p: SharedPreferences): String = runCatching {
        if (!p.getBoolean(OLD_QUALITY, true)) QUALITY_OFF else p.getString(OLD_QUALITY_MODE, "BEST") ?: "BEST"
    }.getOrDefault("BEST")

    private fun legacyMetaSource(p: SharedPreferences): String = runCatching {
        val mb = p.getBoolean(OLD_MANGABAKA, true)
        val al = p.getBoolean(OLD_ANILIST, true)
        when {
            mb && !al -> "MANGABAKA"
            al && !mb -> "ANILIST"
            else -> "AUTO"
        }
    }.getOrDefault("AUTO")

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

    fun build(screen: PreferenceScreen, p: SharedPreferences, sources: List<Pair<String, String>>, onAction: (Action) -> Unit) {
        val ctx = screen.context

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

        switch(COVER_HQ, "High-quality covers", "Pick the best artwork from MangaBaka, AniList and the linked sources.", true)

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
            META_SOURCE, "Metadata",
            listOf(
                Triple("AUTO", "MangaBaka + AniList", ""),
                Triple("MANGABAKA", "MangaBaka only", ""),
                Triple("ANILIST", "AniList only", ""),
            ),
            legacyMetaSource(p),
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
                val current = enabledSources(p, sources.map { it.first }).ifEmpty { sources.map { it.first }.toSet() }
                setDefaultValue(sources.map { it.first }.toSet())
                if (p.contains(ENABLED)) values = current
                setOnPreferenceChangeListener { _, _ ->
                    p.edit().putStringSet(KNOWN, sources.map { it.first }.toSet()).apply()
                    true
                }
            },
        )
        screen.addPreference(
            EditTextPreference(ctx).apply {
                key = LINKS
                title = "Manual series links"
                val help = "Fix a wrong match: <series> = <provider>:<id>, <provider>:- ; entries separated by ';'. " +
                    "Example: mb:3397 = weebcentral:01J76XYCPSY3C4BNPBRY8JMCBE"
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
