package io.github.gitgeshizzle.karoob54.ble

import io.github.gitgeshizzle.karoob54.B54Protocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit-tests the B54 scan-match rule (name prefix or NUS service UUID). Pure JVM. */
class B54ScanFilterTest {

    private val nus = B54Protocol.NUS_SERVICE

    @Test
    fun matches_known_name_prefixes() {
        assertTrue(isB54ScanResult("B54-1234", emptyList(), nus))
        assertTrue(isB54ScanResult("sn0042", emptyList(), nus))
    }

    @Test
    fun matches_nus_service_uuid_case_insensitively() {
        assertTrue(isB54ScanResult("Anything", listOf(nus.uppercase()), nus))
        assertTrue(isB54ScanResult(null, listOf(nus), nus))
    }

    @Test
    fun rejects_unrelated_devices() {
        assertFalse(isB54ScanResult(null, emptyList(), nus))
        assertFalse(isB54ScanResult("Garmin HRM", listOf("0000180d-0000-1000-8000-00805f9b34fb"), nus))
    }
}
