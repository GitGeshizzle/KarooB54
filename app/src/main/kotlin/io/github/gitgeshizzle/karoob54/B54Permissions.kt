package io.github.gitgeshizzle.karoob54

import android.Manifest
import android.os.Build

/**
 * The runtime BLE permissions the extension needs, by platform level.
 *
 * Pure mapping (takes the SDK level as a parameter) so the easy-to-get-wrong SDK branching
 * is unit-tested (B54PermissionsTest) rather than only exercised on a device.
 */
object B54Permissions {

    /** Permission required to run a BLE scan. */
    fun scan(sdkInt: Int): String =
        if (sdkInt >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    /** All permissions the settings screen requests up front. */
    fun required(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
