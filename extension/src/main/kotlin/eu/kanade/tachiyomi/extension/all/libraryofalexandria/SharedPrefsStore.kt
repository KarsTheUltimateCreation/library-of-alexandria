package eu.kanade.tachiyomi.extension.all.libraryofalexandria

import alexandria.core.cache.KeyValueStore
import android.content.SharedPreferences

/** [KeyValueStore] over the host's SharedPreferences implementation. */
class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}
