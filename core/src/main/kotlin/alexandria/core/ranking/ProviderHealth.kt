package alexandria.core.ranking

import alexandria.core.cache.KeyValueStore
import alexandria.core.net.ErrorKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class HealthStats(
    val successes: Int = 0,
    val failures: Int = 0,
    val timeouts: Int = 0,
    val avgLatencyMs: Long = 0,
    val lastFailureAt: Long = 0,
    val consecutiveFailures: Int = 0,
) {
    /** Laplace-smoothed success rate so a new provider starts at a neutral 0.5–0.8 instead of 0 or 1. */
    val successRate: Double get() = (successes + 4.0) / (successes + failures + 5.0)
}

/**
 * Tracks request outcomes per provider (persisted, decayed) and implements a small circuit breaker:
 * after repeated consecutive failures a provider is skipped for searches for a cool-down period.
 */
class ProviderHealth(private val store: KeyValueStore?, private val clock: () -> Long = System::currentTimeMillis) {
    private val stats = ConcurrentHashMap<String, HealthStats>()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var dirty = 0

    init {
        store?.get(KEY)?.let { raw ->
            runCatching { json.decodeFromString<Map<String, HealthStats>>(raw) }.getOrNull()?.let { stats.putAll(it) }
        }
    }

    fun get(source: String): HealthStats = stats[source] ?: HealthStats()

    fun recordSuccess(source: String, latencyMs: Long) {
        stats.compute(source) { _, s0 ->
            val s = decay(s0 ?: HealthStats())
            val avg = if (s.avgLatencyMs == 0L) latencyMs else (s.avgLatencyMs * 7 + latencyMs) / 8
            s.copy(successes = s.successes + 1, avgLatencyMs = avg, consecutiveFailures = 0)
        }
        maybePersist()
    }

    fun recordFailure(source: String, kind: ErrorKind) {
        // A 404 for a single chapter says nothing about the provider's health.
        if (kind == ErrorKind.NOT_FOUND) return
        stats.compute(source) { _, s0 ->
            val s = decay(s0 ?: HealthStats())
            s.copy(
                failures = s.failures + 1,
                timeouts = s.timeouts + if (kind == ErrorKind.TIMEOUT) 1 else 0,
                lastFailureAt = clock(),
                consecutiveFailures = s.consecutiveFailures + 1,
            )
        }
        maybePersist()
    }

    /** True while a provider is in cool-down after ≥4 consecutive failures (10 minutes). */
    fun isCircuitOpen(source: String): Boolean {
        val s = stats[source] ?: return false
        return s.consecutiveFailures >= 4 && clock() - s.lastFailureAt < 10 * 60_000L
    }

    fun reset() {
        stats.clear()
        store?.remove(KEY)
    }

    fun flush() {
        store?.put(KEY, json.encodeToString(MapSerializer(String.serializer(), HealthStats.serializer()), stats.toMap()))
        dirty = 0
    }

    /** Keeps history bounded so old outages fade out. */
    private fun decay(s: HealthStats): HealthStats =
        if (s.successes + s.failures > 200) s.copy(successes = s.successes / 2, failures = s.failures / 2, timeouts = s.timeouts / 2) else s

    private fun maybePersist() {
        if (++dirty >= 10) flush()
    }

    companion object {
        const val KEY = "loa.health"
    }
}
