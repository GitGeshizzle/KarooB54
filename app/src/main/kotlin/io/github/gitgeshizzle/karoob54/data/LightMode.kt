package io.github.gitgeshizzle.karoob54.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Presentation and shared state for the light-mode data field.
 *
 * The light reports its active beam as a single-character code (reply "$B<code>"); [label]
 * turns it into the compact field text (e.g. "5" -> "HI·PWR"). Beam / high-beam levels 1..5
 * are the stepped brightness of the adaptive mode; a light that only uses the fixed presets
 * simply never reports those codes.
 *
 * [LightModeState] is an app-wide holder the BLE layer updates and the data field observes.
 * The field is not bound to a specific device source (the Karoo view config carries none), so
 * a shared flow — rather than per-device DataPoint routing — is the simplest fit. In practice
 * only one B54 is connected at a time.
 */
object LightMode {
    const val DASH = "—"

    /**
     * Compact label for a beam code. Presets and the adaptive MAX steps are mapped; any other
     * code (standby/charge states) is shown raw so a first ride reveals what the light emits.
     * null (disconnected / unknown) -> [DASH].
     */
    fun label(code: String?): String = when (code?.uppercase()) {
        null, "" -> DASH
        "0" -> "OFF"
        "1" -> "LO·ECO"
        "2" -> "LO·STD"
        "3" -> "HI·ECO"
        "4" -> "HI·STD"
        "5" -> "HI·PWR"
        "6" -> "DAY"
        "A" -> "LO·M1"
        "B" -> "LO·M2"
        "C" -> "LO·M3"
        "H" -> "HI·M1"
        "I" -> "HI·M2"
        "J" -> "HI·M3"
        "K" -> "HI·M4"
        "L" -> "HI·M5"
        else -> code.uppercase()
    }

    /** A stable numeric value for the stream, so Karoo marks the field live. */
    fun index(code: String?): Double = (code?.firstOrNull()?.code ?: 0).toDouble()

    /** The lowest on-stage the pause automation dims to (LO·ECO). */
    const val LOWEST_ON = "1"

    // Modes brighter than [LOWEST_ON]: the standard high stages and the adaptive MAX steps.
    // Off ("0"), DRL ("6") and LO·ECO ("1") itself are already at/below lowest, so not dimmed.
    private val DIMMABLE = setOf("2", "3", "4", "5", "A", "B", "C", "H", "I", "J", "K", "L")

    /** Whether the pause automation should dim from [code] (i.e. it is brighter than lowest). */
    fun isDimmable(code: String?): Boolean = code?.uppercase() in DIMMABLE
}

/** App-wide holder for the active beam code: written by the BLE layer, read by the field. */
object LightModeState {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    fun set(code: String?) {
        _code.value = code
    }
}

/** App-wide holder for the light's 5-char info flags (null until first read / on disconnect). */
object LightInfoState {
    private val _flags = MutableStateFlow<String?>(null)
    val flags: StateFlow<String?> = _flags.asStateFlow()

    fun set(flags: String?) {
        _flags.value = flags
    }
}
