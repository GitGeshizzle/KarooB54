package io.github.gitgeshizzle.karoob54.ble

import io.github.gitgeshizzle.karoob54.B54Protocol
import io.github.gitgeshizzle.karoob54.data.BatteryDataType
import io.github.gitgeshizzle.karoob54.data.CyclesDataType
import io.github.gitgeshizzle.karoob54.data.RuntimeDataType
import io.github.gitgeshizzle.karoob54.data.TemperatureDataType
import io.github.gitgeshizzle.karoob54.data.VoltageDataType
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnDataPoint
import kotlin.math.roundToInt

/**
 * Turns decoded B54 protocol messages into Karoo [DeviceEvent]s.
 *
 * Stateful: it remembers the active beam mode ($B) so a runtime reply ($S<mode>...) is only
 * emitted when it refers to the currently active mode. Pure logic (no BLE, no Android
 * framework) so the mapping can be unit-tested on the JVM — see B54EventMapperTest.
 */
class B54EventMapper(
    private val extension: String,
    private val sourceId: String,
    /** Invoked with the active beam code whenever a "$B" reply is decoded (default no-op). */
    private val onBeamMode: (String) -> Unit = {},
    /** Invoked with the 5-char info flags whenever a "$I" reply is decoded (default no-op). */
    private val onInfo: (String) -> Unit = {},
) {

    /** Last reported active beam mode — decides which $S value is the remaining runtime. */
    private var activeBeam: String? = null

    fun map(message: String): List<DeviceEvent> {
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
                onBeamMode(reading.code)
                emptyList()
            }
            is B54Protocol.Reading.Info -> {
                onInfo(reading.flags)
                emptyList()
            }
            is B54Protocol.Reading.RuntimeForMode ->
                if (reading.modeCode == activeBeam) {
                    // Emit as milliseconds so Karoo's native H:MM formatter renders it.
                    listOf(dataPoint(RuntimeDataType.TYPE_ID, reading.minutes * 60_000.0))
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
                sourceId = sourceId,
            ),
        )
    }
}
