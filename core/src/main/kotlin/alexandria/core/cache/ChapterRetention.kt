package alexandria.core.cache

import alexandria.core.reconciliation.Release

/**
 * Which chapters the host already has should survive a refresh during an outage.
 *
 * A chapter missing from the fresh list is kept only when its stored releases show it came *solely* from providers
 * that are unavailable right now: their chapters are missing because of the outage, not because they are gone.
 * Everything else that dropped out — chapters of providers that answered, rows written under an older chapter
 * numbering (e.g. "S2 · Chapter 26" from versions before 1.6.4), chapters without a release record — is not kept,
 * so stale rows cannot linger forever just because some other provider happened to fail.
 */
object ChapterRetention {
    fun <C> keep(
        existing: List<C>,
        freshUrls: Set<String>,
        unavailable: Set<String>,
        urlOf: (C) -> String,
        releasesOf: (C) -> List<Release>?,
    ): List<C> {
        if (unavailable.isEmpty()) return emptyList()
        return existing.filter { c ->
            if (urlOf(c) in freshUrls) return@filter false
            val sources = releasesOf(c).orEmpty().map { it.sourceId }.toSet()
            sources.isNotEmpty() && sources.all { it in unavailable }
        }
    }
}
