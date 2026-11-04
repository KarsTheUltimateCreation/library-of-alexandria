package alexandria.core.sources

import alexandria.core.config.AggregatorSettings
import alexandria.core.ranking.ProviderHealth
import alexandria.core.sources.adapters.MangaDexAdapter
import alexandria.core.sources.adapters.MangaPillAdapter
import alexandria.core.sources.adapters.WeebCentralAdapter

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

    companion object {
        val ID_PATTERN = Regex("^[a-z0-9_]{2,32}$")

        fun defaults(ctx: AdapterContext, health: ProviderHealth) = SourceRegistry(
            listOf(MangaDexAdapter(ctx), WeebCentralAdapter(ctx), MangaPillAdapter(ctx)),
            health,
        )
    }
}
