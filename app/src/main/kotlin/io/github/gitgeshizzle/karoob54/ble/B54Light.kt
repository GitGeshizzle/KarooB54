package io.github.gitgeshizzle.karoob54.ble

import io.github.gitgeshizzle.karoob54.B54Protocol
import io.github.gitgeshizzle.karoob54.data.BatteryDataType
import io.github.gitgeshizzle.karoob54.data.CyclesDataType
import io.github.gitgeshizzle.karoob54.data.RuntimeDataType
import io.github.gitgeshizzle.karoob54.data.TemperatureDataType
import io.github.gitgeshizzle.karoob54.data.VoltageDataType
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

    /** Last reported active beam mode — decides which $S value is the remaining runtime. */
    @Volatile
    private var activeBeam: String? = null

    val source: Device = Device(
        extension,
        "$PREFIX-$address",
        listOf(
            DataType.dataTypeId(extension, BatteryDataType.TYPE_ID),
            DataType.dataTypeId(extension, RuntimeDataType.TYPE_ID),
            DataType.dataTypeId(extension, VoltageDataType.TYPE_ID),
            DataType.dataTypeId(extension, TemperatureDataType.TYPE_ID),
            DataType.dataTypeId(extension, CyclesDataType.TYPE_ID),
        ),
        "B54 ${name ?: address.takeLast(5)}",
    )

    fun connect(emitter: Emitter<DeviceEvent>) {
        val job = scope.launch {
            bleManager.connect(address).collect { event ->
                when (event) {
                    is B54BleManager.Event.Connected ->
                        emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                    is B54BleManager.Event.Disconnected ->
                        emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                    is B54BleManager.Event.Message ->
                        toEvents(event.text).forEach { emitter.onNext(it) }
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun toEvents(message: String): List<DeviceEvent> {
        return when (val reading = B54Protocol.decode(message)) {
            null -> emptyList()
            is B54Protocol.Reading.Battery -> {
                val pct = reading.percent.coerceIn(0.0, 100.0)
                listOf(
                    dataPoint(BatteryDataType.TYPE_ID, pct),
                    OnBatteryStatus(BatteryStatus.fromPercentage(pct.roundToInt())),
                )
            }
            is B54Protocol.Reading.BeamMode -> {
                activeBeam = reading.code
                emptyList()
            }
            is B54Protocol.Reading.RuntimeForMode ->
                if (reading.modeCode == activeBeam) {
                    listOf(dataPoint(RuntimeDataType.TYPE_ID, reading.minutes.toDouble()))
                } else {
                    emptyList()
                }
            is B54Protocol.Reading.Voltage -> listOf(dataPoint(VoltageDataType.TYPE_ID, reading.volts))
            is B54Protocol.Reading.Temperature -> listOf(dataPoint(TemperatureDataType.TYPE_ID, reading.celsius.toDouble()))
            is B54Protocol.Reading.Cycles -> listOf(dataPoint(CyclesDataType.TYPE_ID, reading.count.toDouble()))
        }
    }

    private fun dataPoint(typeId: String, value: Double): OnDataPoint {
        return OnDataPoint(
            DataPoint(
                DataType.dataTypeId(extension, typeId),
                values = mapOf(DataType.Field.SINGLE to value),
                sourceId = source.uid,
            ),
        )
    }

    companion object {
        const val PREFIX = "b54light"
    }
}
