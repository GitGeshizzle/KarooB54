package io.github.gitgeshizzle.karoob54.ble

import io.github.gitgeshizzle.karoob54.data.BatteryDataType
import io.github.gitgeshizzle.karoob54.data.RuntimeDataType
import io.github.gitgeshizzle.karoob54.data.VoltageDataType
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnDataPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-tests the decoded-message -> DeviceEvent mapping, including the stateful beam-mode
 * gating that previously could only be checked by riding with the light. Pure JVM.
 */
class B54EventMapperTest {

    private val extension = "b54"
    private val sourceId = "b54light-AA:BB:CC:DD:EE:FF"

    private fun mapper() = B54EventMapper(extension, sourceId)

    private fun OnDataPoint.value(): Double = dataPoint.values.getValue(DataType.Field.SINGLE)

    @Test
    fun battery_emits_datapoint_and_status() {
        val events = mapper().map("\$L25600") // 25600 / 256 = 100 %
        assertEquals(2, events.size)
        val dp = events[0] as OnDataPoint
        assertEquals(DataType.dataTypeId(extension, BatteryDataType.TYPE_ID), dp.dataPoint.dataTypeId)
        assertEquals(sourceId, dp.dataPoint.sourceId)
        assertEquals(100.0, dp.value(), 1e-6)
        assertTrue(events[1] is OnBatteryStatus)
    }

    @Test
    fun battery_is_clamped_to_100() {
        val dp = mapper().map("\$L26000")[0] as OnDataPoint // 26000 / 256 = 101.56 %
        assertEquals(100.0, dp.value(), 1e-6)
    }

    @Test
    fun runtime_is_ignored_until_its_mode_is_active() {
        val m = mapper()
        // No active beam yet -> runtime reply is dropped.
        assertTrue(m.map("\$S200000555").isEmpty())

        // Mode 2 becomes active -> runtime for mode 2 is now emitted (in ms).
        assertTrue(m.map("\$B2").isEmpty())
        val events = m.map("\$S200000555")
        assertEquals(1, events.size)
        val dp = events[0] as OnDataPoint
        assertEquals(DataType.dataTypeId(extension, RuntimeDataType.TYPE_ID), dp.dataPoint.dataTypeId)
        assertEquals(555 * 60_000.0, dp.value(), 1e-6)

        // A runtime for a different (non-active) mode is still dropped.
        assertTrue(m.map("\$S300000999").isEmpty())
    }

    @Test
    fun voltage_passes_through() {
        val dp = mapper().map("\$Q12282")[0] as OnDataPoint
        assertEquals(DataType.dataTypeId(extension, VoltageDataType.TYPE_ID), dp.dataPoint.dataTypeId)
        assertEquals(12.282, dp.value(), 1e-6)
    }

    @Test
    fun unknown_message_emits_nothing() {
        assertTrue(mapper().map("garbage").isEmpty())
    }
}
