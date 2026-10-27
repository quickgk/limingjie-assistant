package com.landosol.toolbox.labyrinth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidLabyrinthStrategySettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("labyrinth-strategy", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val current = MutableStateFlow(
        migrateResponseIntervalDefault(LabyrinthStrategySettingsCodec.decode(preferences.getString("settings.v1", null))),
    )
    val state = current.asStateFlow()

    suspend fun save(settings: LabyrinthStrategySettings) = mutex.withLock {
        val encoded = LabyrinthStrategySettingsCodec.encode(settings)
        withContext(Dispatchers.IO) {
            check(preferences.edit().putString("settings.v1", encoded).commit()) { "策略保存失败，请检查存储空间" }
            current.value = settings
        }
    }

    /**
     * One-shot migration for the response-interval default switch from 300 ms to 500 ms.
     *
     * The codec persists every field (`encodeDefaults = true`), so an existing store always
     * carries an explicit `responseIntervalMillis`. Changing the constant alone therefore leaves
     * upgraded installs on the old tier. Run this exactly once, guarded by its own flag, so a
     * player who later picks 300 ms on purpose keeps that choice across restarts.
     */
    private fun migrateResponseIntervalDefault(settings: LabyrinthStrategySettings): LabyrinthStrategySettings {
        if (preferences.getBoolean(KEY_RESPONSE_DEFAULT_MIGRATED, false)) return settings
        val stored = preferences.getString("settings.v1", null)
        if (stored == null) {
            // Nothing persisted yet: the constructor default already is the standard tier.
            preferences.edit().putBoolean(KEY_RESPONSE_DEFAULT_MIGRATED, true).commit()
            return settings
        }
        val migrated = settings.copy(responseIntervalMillis = DEFAULT_RESPONSE_INTERVAL_MILLIS)
        val written = preferences.edit()
            .putString("settings.v1", LabyrinthStrategySettingsCodec.encode(migrated))
            .putBoolean(KEY_RESPONSE_DEFAULT_MIGRATED, true)
            .commit()
        // Keep the pre-migration value if the write fails so the migration can retry next start.
        return if (written) migrated else settings
    }

    private companion object {
        const val KEY_RESPONSE_DEFAULT_MIGRATED = "settings.response-interval-default.migrated"
    }
}
