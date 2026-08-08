package io.github.gitgeshizzle.karoob54

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Small settings / permissions screen for the extension.
 *
 * A Karoo extension is a service without UI, so there is nowhere for Android to ask for
 * the BLE runtime permissions. This activity requests them — opened via the extension's
 * "open" bonus action (or the permission notification). Grant once, then the sensor scan
 * works.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    private val blePermissions: Array<String>
        get() = B54Permissions.required(Build.VERSION.SDK_INT).toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        setContentView(status)

        if (!hasBlePermissions()) {
            requestPermissions(blePermissions, REQUEST_CODE)
        }
        updateStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
    }

    private fun hasBlePermissions(): Boolean = blePermissions.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateStatus() {
        status.text = getString(
            if (hasBlePermissions()) R.string.perm_granted else R.string.perm_needed,
        )
    }

    companion object {
        private const val REQUEST_CODE = 1
    }
}
