package io.github.gitgeshizzle.karoob54

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit-tests the SDK-dependent permission selection. Pure JVM (constants are inlined). */
class B54PermissionsTest {

    @Test
    fun scan_permission_by_sdk() {
        assertEquals(Manifest.permission.BLUETOOTH_SCAN, B54Permissions.scan(Build.VERSION_CODES.S))
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION, B54Permissions.scan(Build.VERSION_CODES.R))
    }

    @Test
    fun required_permissions_by_sdk() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            B54Permissions.required(Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            B54Permissions.required(Build.VERSION_CODES.M),
        )
    }
}
