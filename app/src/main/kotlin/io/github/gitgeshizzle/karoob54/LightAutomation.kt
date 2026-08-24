package io.github.gitgeshizzle.karoob54

import io.github.gitgeshizzle.karoob54.ble.B54BleManager
import io.github.gitgeshizzle.karoob54.data.LightInfoState
import io.github.gitgeshizzle.karoob54.data.LightMode
import io.github.gitgeshizzle.karoob54.data.LightModeState
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.RideState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * User-opt-in light automations. Both write control commands to the light via
 * [B54BleManager.send] (the only place beam/info sets originate), gated by [B54Settings].
 *
 * - Pause dimming: on ride pause, dim a bright mode to the lowest on-stage; restore the
 *   previous mode on resume. Off/DRL/already-lowest modes are left alone.
 * - Autolight: keep the light's native ambient autolight flag matching the setting.
 *
 * All commands are idempotent and only sent on an actual change, so nothing is written while
 * the automations are off or already in the desired state.
 */
class LightAutomation(
    private val karooSystem: KarooSystemService,
    private val bleManager: B54BleManager,
    private val settings: B54Settings,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var rideConsumerId: String? = null

    /** Mode captured when we dimmed for a pause, to restore on resume; null if we didn't dim. */
    @Volatile
    private var dimmedFrom: String? = null

    fun start() {
        rideConsumerId = karooSystem.addConsumer<RideState> { state ->
            when (state) {
                is RideState.Paused -> onPause()
                is RideState.Recording -> onResume()
                is RideState.Idle -> dimmedFrom = null // ride ended; don't auto-restore
            }
        }

        // Autolight: whenever the setting or the light's flags change, bring the native
        // autolight flag in line with the setting. Sending updates LightInfoState via the next
        // $i reply, which re-evaluates to a no-op, so this converges without a write loop.
        scope.launch {
            combine(settings.autoLightFlow(), LightInfoState.flags) { desired, flags ->
                desired to flags
            }.distinctUntilChanged().collect { (desired, flags) ->
                if (flags != null && B54Protocol.isAutolightOn(flags) != desired) {
                    Timber.i("Autolight -> $desired")
                    bleManager.send(B54Protocol.setInfo(B54Protocol.withAutolight(flags, desired)))
                }
            }
        }
    }

    private fun onPause() {
        if (!settings.pauseDim) return
        val current = LightModeState.code.value
        if (!LightMode.isDimmable(current)) return
        dimmedFrom = current
        Timber.i("Pause: dim from $current to ${LightMode.LOWEST_ON}")
        bleManager.send(B54Protocol.setBeam(LightMode.LOWEST_ON))
    }

    private fun onResume() {
        val restore = dimmedFrom ?: return
        dimmedFrom = null
        Timber.i("Resume: restore $restore")
        bleManager.send(B54Protocol.setBeam(restore))
    }

    fun stop() {
        rideConsumerId?.let { karooSystem.removeConsumer(it) }
        rideConsumerId = null
        scope.cancel()
    }
}
