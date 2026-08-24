package io.github.gitgeshizzle.karoob54.ble

import io.github.gitgeshizzle.karoob54.data.BatteryDataType
import io.github.gitgeshizzle.karoob54.data.CyclesDataType
import io.github.gitgeshizzle.karoob54.data.LightModeDataType
import io.github.gitgeshizzle.karoob54.data.LightModeState
import io.github.gitgeshizzle.karoob54.data.RuntimeDataType
import io.github.gitgeshizzle.karoob54.data.TemperatureDataType
import io.github.gitgeshizzle.karoob54.data.VoltageDataType
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.OnConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Represents a BLE-connected B54 as a Karoo "device".
 *
 * Uses [B54BleManager] (native Android BLE): connect, TX notify, 1 s "$l" keepalive.
 * Decodes the incoming $.. messages via [B54Protocol] and emits values for our data
 * fields. Read requests only.
 */
class B54Light(
    private val bleManager: B54BleManager,
    private val extension: String,
    private val address: String,
    private val name: String?,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val source: Device = Device(
        extension,
        "$PREFIX-$address",
        listOf(
            DataType.dataTypeId(extension, BatteryDataType.TYPE_ID),
            DataType.dataTypeId(extension, RuntimeDataType.TYPE_ID),
            DataType.dataTypeId(extension, VoltageDataType.TYPE_ID),
            DataType.dataTypeId(extension, TemperatureDataType.TYPE_ID),
            DataType.dataTypeId(extension, CyclesDataType.TYPE_ID),
            DataType.dataTypeId(extension, LightModeDataType.TYPE_ID),
        ),
        "B54 ${name ?: address.takeLast(5)}",
    )

    /** Decodes incoming messages into data-field events (holds the beam-mode state). */
    private val eventMapper = B54EventMapper(extension, source.uid) { code ->
        LightModeState.set(code)
    }

    fun connect(emitter: Emitter<DeviceEvent>) {
        val job = scope.launch {
            bleManager.connect(address).collect { event ->
                when (event) {
                    is B54BleManager.Event.Connected ->
                        emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                    is B54BleManager.Event.Disconnected -> {
                        LightModeState.set(null) // clear the light-mode field until reconnected
                        emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                    }
                    is B54BleManager.Event.Message ->
                        eventMapper.map(event.text).forEach { emitter.onNext(it) }
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val PREFIX = "b54light"
    }
}
