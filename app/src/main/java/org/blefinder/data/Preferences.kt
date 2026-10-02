package org.blefinder.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blefinder.core.*

/** Tiny atomic preferences file; structured observations belong in Room. All writes run on IO. */
class Preferences(context: Context) {
    private val file = context.getSharedPreferences("search_preferences", Context.MODE_PRIVATE)
    suspend fun load(): Pair<Settings, Set<String>> = withContext(Dispatchers.IO) {
        val config = file.getString("settings_json", null)?.let {
            runCatching { SearchJson.decodeFromString<Settings>(it).validated() }.getOrNull()
        } ?: Settings()
        config to (file.getStringSet("persistent_addresses", emptySet())?.toSet() ?: emptySet())
    }
    suspend fun settings(settings: Settings) = withContext(Dispatchers.IO) {
        check(file.edit().putString("settings_json", SearchJson.encodeToString(settings)).commit()) { "Unable to save settings" }
    }
    suspend fun mutes(addresses: Set<String>) = withContext(Dispatchers.IO) {
        check(file.edit().putStringSet("persistent_addresses", addresses).commit()) { "Unable to save persistent mutes" }
    }
}
