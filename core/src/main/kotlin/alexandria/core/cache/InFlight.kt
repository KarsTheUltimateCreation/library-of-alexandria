package alexandria.core.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

/**
 * Merges identical concurrent requests: the first caller does the work, later callers with the same key wait for
 * its result. If the first caller is *cancelled* (the user left its screen) the waiting callers do the work
 * themselves instead of failing; an ordinary failure is shared, so a failing provider is not asked twice.
 */
class SingleFlight<K : Any, V> {
    private val running = ConcurrentHashMap<K, CompletableDeferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val mine = CompletableDeferred<V>()
        val existing = running.putIfAbsent(key, mine)
        if (existing != null) {
            try {
                return existing.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // we were cancelled ourselves
            }
            return block()
        }
        return try {
            block().also { mine.complete(it) }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            running.remove(key, mine)
        }
    }

    val size: Int get() = running.size
}

/** Small thread-safe LRU map with an optional per-entry lifetime, for in-memory state that must stay bounded. */
class BoundedCache<K : Any, V : Any>(
    private val capacity: Int,
    private val ttlMillis: Long = Long.MAX_VALUE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry<V>(val value: V, val at: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?) = size > capacity
    }

    operator fun get(key: K): V? = synchronized(map) {
        val e = map[key] ?: return null
        if (clock() - e.at > ttlMillis) {
            map.remove(key)
            null
        } else {
            e.value
        }
    }

    operator fun set(key: K, value: V) {
        synchronized(map) { map[key] = Entry(value, clock()) }
    }

    fun remove(key: K) {
        synchronized(map) { map.remove(key) }
    }

    fun removeIf(predicate: (K) -> Boolean) {
        synchronized(map) { map.keys.filter(predicate).forEach { map.remove(it) } }
    }

    fun clear() = synchronized(map) { map.clear() }

    val size: Int get() = synchronized(map) { map.size }
}
