package io.github.gitgeshizzle.karoob54

import android.Manifest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric (JVM, no device/emulator) test of the settings screen's runtime-permission
 * flow — the part that needs a real Context and cannot be a pure function.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    @Test
    fun requests_ble_permissions_when_missing() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull("expected a runtime permission request", request)
        assertTrue(request.requestedPermissions.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(request.requestedPermissions.contains(Manifest.permission.BLUETOOTH_CONNECT))
    }

    @Test
    fun does_not_request_when_already_granted() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull(shadowOf(activity).lastRequestedPermission)
    }
}
