package alexandria.core.sources

import alexandria.core.config.AggregatorSettings
import alexandria.core.ranking.ProviderHealth
import alexandria.core.sources.adapters.AtsumaruAdapter
import alexandria.core.sources.adapters.EHentaiAdapter
import alexandria.core.sources.adapters.Hentai20Adapter
import alexandria.core.sources.adapters.Hentai2ReadAdapter
import alexandria.core.sources.adapters.HitomiAdapter
import alexandria.core.sources.adapters.NHentaiAdapter
import alexandria.core.sources.adapters.PururinAdapter
import alexandria.core.sources.adapters.MangaDexAdapter
import alexandria.core.sources.adapters.MangaKatanaAdapter
import alexandria.core.sources.adapters.MangaPillAdapter
import alexandria.core.sources.adapters.MangaReadAdapter
import alexandria.core.sources.adapters.MgekoAdapter
import alexandria.core.sources.adapters.WeebCentralAdapter
import alexandria.core.sources.adapters.HiperdexAdapter
import alexandria.core.sources.adapters.ManhwaReadAdapter
import alexandria.core.sources.adapters.OmegaScansAdapter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The bundled providers, their capabilities and current health. Adding a provider = implement
 * [SourceAdapter] and register it in [defaults] (see docs/ADDING_SOURCES.md).
 */
class SourceRegistry(private val adapters: List<SourceAdapter>, val health: ProviderHealth) {

    init {
        require(adapters.map { it.id }.toSet().size == adapters.size) { "duplicate adapter ids" }
        adapters.forEach { require(ID_PATTERN.matches(it.id)) { "invalid adapter id ${it.id}" } }
    }

    val all: List<SourceAdapter> get() = adapters

    operator fun get(id: String): SourceAdapter? = adapters.firstOrNull { it.id == id }

    /** Image host -> provider id, learned from cover URLs and seeded from [SourceAdapter.imageHosts]. */
    private val imageHostOwners = ConcurrentHashMap<String, String>().apply {
        adapters.forEach { a -> a.imageHosts.forEach { h -> putIfAbsent(h.lowercase(), a.id) } }
    }

    /** Remembers which provider serves images from [url]'s host so cover requests get its headers. */
    fun noteImage(url: String?, sourceId: String) {
        val host = url?.toHttpUrlOrNull()?.host?.lowercase() ?: return
        if (imageHostOwners.size < 500) imageHostOwners.putIfAbsent(host, sourceId)
    }

    /** The provider whose images live on [host] (exact host first, then a listed parent domain). */
    fun imageOwner(host: String): SourceAdapter? {
        val h = host.lowercase()
        imageHostOwners[h]?.let { return get(it) }
        return imageHostOwners.entries.firstOrNull { (k, _) -> h.endsWith(".$k") }?.let { get(it.value) }
    }

    /**
     * Enabled adapters in user order. Disabled providers never participate in search or fallback.
     * An empty enabled set in settings means "all".
     */
    fun enabled(settings: AggregatorSettings, includeUnhealthy: Boolean = true): List<SourceAdapter> =
        adapters
            .filter { settings.enabledSources.isEmpty() || it.id in settings.enabledSources }
            .filter { a -> a.languages == null || settings.languages.isEmpty() || a.languages!!.any { settings.acceptsLanguage(it) } }
            .filter { includeUnhealthy || !health.isCircuitOpen(it.id) }
            .sortedBy { settings.sourceRank(it.id) * 1000 + adapters.indexOf(it) }

    /**
     * Providers worth searching to link a catalogue series: series providers only (gallery sites hold one-shots),
     * and adult providers only for adult series. Searching fewer, relevant providers is what keeps opening a
     * series fast and avoids timeouts from providers that could never carry it.
     */
    fun forLinking(settings: AggregatorSettings, adultSeries: Boolean): List<SourceAdapter> =
        enabled(settings, includeUnhealthy = false).filter { it.role == ProviderRole.SERIES && (adultSeries || !it.adult) }

    /** Providers whose own listings make good browse pages (popular / latest) when the catalogue is unavailable. */
    fun forBrowsing(settings: AggregatorSettings): List<SourceAdapter> =
        enabled(settings, includeUnhealthy = false).filter { it.role == ProviderRole.SERIES && it.standaloneResults && !it.adult }

    companion object {
        val ID_PATTERN = Regex("^[a-z0-9_]{2,32}$")

        fun defaults(ctx: AdapterContext, health: ProviderHealth) = SourceRegistry(
            listOf(MangaDexAdapter(ctx), WeebCentralAdapter(ctx), MangaPillAdapter(ctx), MangaKatanaAdapter(ctx), MangaReadAdapter(ctx),
                MgekoAdapter(ctx), AtsumaruAdapter(ctx),
                // Adult series providers: searched to link a catalogue series only when it is adult content.
                HiperdexAdapter(ctx), OmegaScansAdapter(ctx), ManhwaReadAdapter(ctx), Hentai20Adapter(ctx), Hentai2ReadAdapter(ctx),
                // Gallery sites (one-shots, doujinshi): standalone search results only.
                NHentaiAdapter(ctx), HitomiAdapter(ctx), EHentaiAdapter(ctx), PururinAdapter(ctx)),
            health,
        )
    }
}
