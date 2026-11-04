package alexandria.core.ranking

import alexandria.core.cache.KeyValueStore
import alexandria.core.net.ErrorKind
import alexandria.core.net.RequestBudget
import alexandria.core.net.classify
import alexandria.core.net.withProviderTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
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
 * Tracks request outcomes per provider (persisted, decayed) and uses them to keep requests fast:
 *  - a circuit breaker: after 3 consecutive failures a provider is skipped for a cool-down that doubles with every
 *    further failure (2, 4, 8 ... up to 30 minutes), so a site that is down stops costing a timeout on every call;
 *  - adaptive timeouts ([timeoutFor]): a provider that usually answers in 800 ms is given a few seconds, not the
 *    full maximum, so one stalled request no longer holds up a whole search or chapter list.
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
        // A 404 for a single chapter says nothing about the provider's health, and neither does time spent waiting
        // locally for a rate-limit token or a requested cool-down (no request reached the provider).
        if (kind == ErrorKind.NOT_FOUND || kind == ErrorKind.QUEUED) return
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

    /** True while a provider is in cool-down after ≥3 consecutive failures (2 minutes, doubling, at most 30). */
    fun isCircuitOpen(source: String): Boolean {
        val s = stats[source] ?: return false
        if (s.consecutiveFailures < 3) return false
        val coolDown = (2 * 60_000L shl (s.consecutiveFailures - 3).coerceAtMost(4)).coerceAtMost(30 * 60_000L)
        return clock() - s.lastFailureAt < coolDown
    }

    /**
     * Timeout for one call to [source]: about five times its usual latency plus a margin, between [MIN_TIMEOUT_MS]
     * and [maxMs]. Providers without enough history (or that recently timed out) get [maxMs].
     */
    fun timeoutFor(source: String, maxMs: Long): Long {
        val s = stats[source] ?: return maxMs
        if (s.successes < 3 || s.avgLatencyMs <= 0 || s.consecutiveFailures > 0) return maxMs
        return (s.avgLatencyMs * 5 + 2_000).coerceIn(MIN_TIMEOUT_MS.coerceAtMost(maxMs), maxMs)
    }

    /**
     * Runs one provider operation under an overall [ceilingMs] (which also covers local queueing) with a per-request
     * network timeout adapted to the provider's latency ([timeoutFor]), and records the outcome. Latency excludes
     * time spent waiting for rate-limit tokens, and a deadline spent purely queueing is not held against the provider.
     * Rethrows failures; a provider's own timeout surfaces as [alexandria.core.net.ProviderTimeoutException].
     */
    suspend fun <T> call(source: String, ceilingMs: Long, block: suspend () -> T): T {
        val started = clock()
        var queued = 0L
        return try {
            withProviderTimeout(source, ceilingMs, timeoutFor(source, ceilingMs)) {
                block().also { queued = currentCoroutineContext()[RequestBudget]?.queueWaitMs?.get() ?: 0L }
            }.also { recordSuccess(source, (clock() - started - queued).coerceAtLeast(1)) }
        } catch (e: CancellationException) {
            throw e // the caller went away: not the provider's fault
        } catch (e: Exception) {
            recordFailure(source, classify(e))
            throw e
        }
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
        const val MIN_TIMEOUT_MS = 6_000L
    }
}
