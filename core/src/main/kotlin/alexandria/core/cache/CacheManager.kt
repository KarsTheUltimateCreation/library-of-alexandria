package alexandria.core.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** Minimal persistent key/value store. The extension backs it with the host's SharedPreferences. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

class InMemoryStore : KeyValueStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

@Serializable
private data class Envelope(val t: Long, val v: String)

/**
 * Two-level cache: an in-memory map in front of a persistent [KeyValueStore], with per-entry TTLs and a
 * bounded number of persisted entries (oldest evicted first) so the host's preference file stays small.
 */
class CacheManager(
    private val store: KeyValueStore,
    private val json: Json = DEFAULT_JSON,
    private val maxPersistedEntries: Int = 400,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val memory = ConcurrentHashMap<String, Envelope>()

    fun <T> get(key: String, serializer: KSerializer<T>, maxAgeMillis: Long): T? {
        val env = memory[key] ?: store.get(PREFIX + key)?.let { raw ->
            runCatching { json.decodeFromString(Envelope.serializer(), raw) }.getOrNull()
        }?.also { memory[key] = it } ?: return null
        if (clock() - env.t > maxAgeMillis) return null
        return runCatching { json.decodeFromString(serializer, env.v) }.getOrNull()
    }

    fun <T> put(key: String, serializer: KSerializer<T>, value: T, persist: Boolean = true) {
        val env = Envelope(clock(), json.encodeToString(serializer, value))
        memory[key] = env
        if (memory.size > maxPersistedEntries * 2) trimMemory()
        if (persist) {
            store.put(PREFIX + key, json.encodeToString(Envelope.serializer(), env))
            evictIfNeeded()
        }
    }

    fun remove(key: String) {
        memory.remove(key)
        store.remove(PREFIX + key)
    }

    /** Removes entries whose key starts with [prefix] (or everything when null). */
    fun clear(prefix: String? = null) {
        memory.keys.filter { prefix == null || it.startsWith(prefix) }.forEach { memory.remove(it) }
        store.keys().filter { it.startsWith(PREFIX + (prefix ?: "")) }.forEach { store.remove(it) }
    }

    fun persistedCount(): Int = store.keys().count { it.startsWith(PREFIX) }

    private fun trimMemory() {
        memory.entries.sortedBy { it.value.t }.take(memory.size - maxPersistedEntries).forEach { memory.remove(it.key) }
    }

    private fun evictIfNeeded() {
        val keys = store.keys().filter { it.startsWith(PREFIX) }
        if (keys.size <= maxPersistedEntries) return
        keys.map { k -> k to (store.get(k)?.let { raw -> runCatching { json.decodeFromString(Envelope.serializer(), raw).t }.getOrNull() } ?: 0L) }
            .sortedBy { it.second }
            .take(keys.size - maxPersistedEntries + maxPersistedEntries / 10)
            .forEach { store.remove(it.first) }
    }

    companion object {
        const val PREFIX = "loa.cache."
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
