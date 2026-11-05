package alexandria.core.config

import alexandria.core.sources.adapters.EHentaiAdapter
import alexandria.core.sources.adapters.Hentai20Adapter
import alexandria.core.sources.adapters.Hentai2ReadAdapter
import alexandria.core.sources.adapters.HitomiAdapter
import alexandria.core.sources.adapters.NHentaiAdapter
import alexandria.core.sources.adapters.PururinAdapter

/**
 * Which providers the user's source choices and the 18+ switch allow. Pure, so the extension's settings screen and the
 * engine agree and it can be tested on the JVM.
 *
 * The 18+ switch is a *selected-provider* control: it hides exactly [EXPLICIT_SOURCES]. Hiperdex, Omega Scans,
 * ManhwaRead and providers with mixed catalogues stay governed by their own source checkbox only (by design), so the
 * switch does not promise that every remaining result is non-adult.
 */
object SourcePolicy {
    /** The providers behind the 18+ switch, and only these. */
    val EXPLICIT_SOURCES: Set<String> = setOf(
        Hentai20Adapter.ID, Hentai2ReadAdapter.ID,
        NHentaiAdapter.ID, HitomiAdapter.ID, EHentaiAdapter.ID, PururinAdapter.ID,
    )

    /** Stored in place of an empty selection: "the user unticked everything" must not read as "all". */
    const val NONE = "__none__"

    /** Providers shipped before the known-providers list was introduced (1.6.4). */
    val LEGACY_SOURCES = setOf("mangadex", "weebcentral", "mangapill", "mangakatana", "mangaread", "mgeko", "atsumaru")

    /**
     * The user's own selection. Never-configured ([stored] null) = all (empty set); an explicitly emptied selection
     * means none. Providers added after the selection was saved ([known] lacks them) are enabled.
     */
    fun selected(stored: Set<String>?, known: Set<String>?, allSources: Collection<String>): Set<String> {
        stored ?: return emptySet()
        return (stored + (allSources - (known ?: LEGACY_SOURCES))).ifEmpty { setOf(NONE) }
    }

    /** Providers hidden by the 18+ switch. */
    fun hidden(adult: Boolean): Set<String> = if (adult) emptySet() else EXPLICIT_SOURCES

    /** [selection] without [hidden] providers; "all" (empty) becomes an explicit list of the rest. */
    fun effective(selection: Set<String>, allSources: Collection<String>, hidden: Set<String>): Set<String> {
        if (hidden.isEmpty()) return selection
        return (selection.ifEmpty { allSources.toSet() } - hidden).ifEmpty { setOf(NONE) }
    }

    /**
     * What to store when the user edits the source list while some providers are not listed ([listed] lacks them):
     * the new choice for the listed ones, and the previous choice, untouched, for the others.
     */
    fun saveEdit(chosen: Set<String>, previous: Set<String>, allSources: Collection<String>, listed: Collection<String>): Set<String> {
        val unlisted = allSources.toSet() - listed.toSet()
        val kept = previous.ifEmpty { allSources.toSet() } intersect unlisted
        return (chosen intersect listed.toSet()) + kept
    }

    /** The preferred source to use: none for "auto", or while that provider is hidden (the choice itself is kept). */
    fun preferred(stored: String?, hidden: Set<String>): String? = stored?.takeUnless { it == "auto" || it.isBlank() || it in hidden }

    /** Whether [sourceId] may be contacted under [settings] (the effective enabled set; empty = all). */
    fun allows(settings: AggregatorSettings, sourceId: String): Boolean =
        settings.enabledSources.isEmpty() || sourceId in settings.enabledSources
}
