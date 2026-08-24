package io.github.gitgeshizzle.karoob54.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit-tests the beam-code -> compact label mapping used by the light-mode field. */
class LightModeTest {

    @Test
    fun presets_map_to_compact_labels() {
        assertEquals("OFF", LightMode.label("0"))
        assertEquals("LO·ECO", LightMode.label("1"))
        assertEquals("LO·STD", LightMode.label("2"))
        assertEquals("HI·ECO", LightMode.label("3"))
        assertEquals("HI·STD", LightMode.label("4"))
        assertEquals("HI·PWR", LightMode.label("5"))
        assertEquals("DAY", LightMode.label("6"))
    }

    @Test
    fun adaptive_max_steps_map_to_low_and_high_levels() {
        assertEquals("LO·M1", LightMode.label("A"))
        assertEquals("LO·M3", LightMode.label("C"))
        assertEquals("HI·M1", LightMode.label("H"))
        assertEquals("HI·M5", LightMode.label("L"))
    }

    @Test
    fun null_or_blank_shows_dash() {
        assertEquals(LightMode.DASH, LightMode.label(null))
        assertEquals(LightMode.DASH, LightMode.label(""))
    }

    @Test
    fun unmapped_codes_are_shown_raw_uppercased() {
        // Standby/charge states we deliberately don't alias — surfaced raw for a first ride.
        assertEquals("R", LightMode.label("r"))
        assertEquals("S", LightMode.label("s"))
    }

    @Test
    fun dimmable_only_for_modes_brighter_than_lowest() {
        // Brighter presets and MAX steps -> dim on pause.
        assertTrue(LightMode.isDimmable("2"))
        assertTrue(LightMode.isDimmable("5"))
        assertTrue(LightMode.isDimmable("H"))
        // Off, DRL and the lowest on-stage itself are left alone.
        assertFalse(LightMode.isDimmable("0"))
        assertFalse(LightMode.isDimmable("6"))
        assertFalse(LightMode.isDimmable(LightMode.LOWEST_ON))
        assertFalse(LightMode.isDimmable(null))
    }
}
