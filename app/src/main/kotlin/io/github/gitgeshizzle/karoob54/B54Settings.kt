package io.github.gitgeshizzle.karoob54

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Persisted user settings for the light automations, shared between the settings activity
 * (writer) and the extension service (reader) — same process, so a SharedPreferences change
 * listener carries updates across.
 *
 * - [pauseDim]: dim the light to its lowest on-stage while the ride is paused, restore on resume.
 * - [autoLight]: keep the light's native ambient autolight on.
 *
 * Both default to off — the extension stays passive (read-only) until the user opts in.
 */
class B54Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var pauseDim: Boolean
        get() = prefs.getBoolean(KEY_PAUSE_DIM, false)
        set(value) { prefs.edit().putBoolean(KEY_PAUSE_DIM, value).apply() }

    var autoLight: Boolean
        get() = prefs.getBoolean(KEY_AUTOLIGHT, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTOLIGHT, value).apply() }

    fun pauseDimFlow(): Flow<Boolean> = booleanFlow(KEY_PAUSE_DIM)

    fun autoLightFlow(): Flow<Boolean> = booleanFlow(KEY_AUTOLIGHT)

    /** Emits the current value of [key] and every subsequent change. */
    private fun booleanFlow(key: String): Flow<Boolean> = callbackFlow {
        trySend(prefs.getBoolean(key, false))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) trySend(prefs.getBoolean(key, false))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val PREFS = "b54_settings"
        const val KEY_PAUSE_DIM = "auto_pause_dim"
        const val KEY_AUTOLIGHT = "auto_light"
    }
}
