package io.github.gitgeshizzle.karoob54

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the decoder against representative protocol messages.
 * Pure JVM test: ./gradlew :app:test
 */
class B54ProtocolTest {

    @Test
    fun battery_level_is_raw_over_256() {
        val r = B54Protocol.decode("\$L23600") as B54Protocol.Reading.Battery
        // 23600 / 256 = 92.1875 %
        assertEquals(92.1875, r.percent, 1e-6)
    }

    @Test
    fun full_battery_is_100_percent() {
        val r = B54Protocol.decode("\$L25600") as B54Protocol.Reading.Battery
        assertEquals(100.0, r.percent, 1e-6)
    }

    @Test
    fun voltage_in_millivolts() {
        val r = B54Protocol.decode("\$Q12282") as B54Protocol.Reading.Voltage
        assertEquals(12.282, r.volts, 1e-6)
    }

    @Test
    fun temperature_handles_sign() {
        val r = B54Protocol.decode("\$G+029") as B54Protocol.Reading.Temperature
        assertEquals(29, r.celsius)
    }

    @Test
    fun negative_temperature() {
        val r = B54Protocol.decode("\$G-005") as B54Protocol.Reading.Temperature
        assertEquals(-5, r.celsius)
    }

    @Test
    fun charge_cycles() {
        val r = B54Protocol.decode("\$Y0008") as B54Protocol.Reading.Cycles
        assertEquals(8, r.count)
    }

    @Test
    fun beam_mode() {
        val r = B54Protocol.decode("\$B2") as B54Protocol.Reading.BeamMode
        assertEquals("2", r.code)
    }

    @Test
    fun runtime_for_mode_in_minutes() {
        val r = B54Protocol.decode("\$S200000555") as B54Protocol.Reading.RuntimeForMode
        assertEquals("2", r.modeCode)
        assertEquals(555, r.minutes)
    }

    @Test
    fun unknown_or_short_messages_return_null() {
        assertNull(B54Protocol.decode("\$JD"))
        assertNull(B54Protocol.decode(""))
        assertNull(B54Protocol.decode("garbage"))
    }

    @Test
    fun read_requests_are_lowercase() {
        // Safety anchor: reads are the default and stay lowercase.
        assertEquals("\$l", B54Protocol.REQ_LEVEL)
        assertEquals("\$b", B54Protocol.REQ_BEAM)
        assertEquals("\$i", B54Protocol.REQ_INFO)
        assertTrue(B54Protocol.REQ_LEVEL[1].isLowerCase())
        assertTrue(B54Protocol.REQ_BEAM[1].isLowerCase())
        assertTrue(B54Protocol.REQ_INFO[1].isLowerCase())
    }

    @Test
    fun set_commands_are_the_two_allowed_uppercase_writes() {
        // The only writes we ever build: set beam and set info flags.
        assertEquals("\$B4", B54Protocol.setBeam("4"))
        assertEquals("\$ILChbT", B54Protocol.setInfo("LChbT"))
    }

    @Test
    fun info_flags_decode() {
        val r = B54Protocol.decode("\$ILChbt") as B54Protocol.Reading.Info
        assertEquals("LChbt", r.flags)
    }

    @Test
    fun autolight_flag_helpers() {
        assertFalse(B54Protocol.isAutolightOn("LChbt"))
        assertTrue(B54Protocol.isAutolightOn("LChbT"))
        // Flipping tunnel preserves the other four flags exactly.
        assertEquals("LChbT", B54Protocol.withAutolight("LChbt", true))
        assertEquals("LChbt", B54Protocol.withAutolight("LChbT", false))
    }
}
