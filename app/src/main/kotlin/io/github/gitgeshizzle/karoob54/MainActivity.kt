package io.github.gitgeshizzle.karoob54

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Settings / permissions screen for the extension.
 *
 * A Karoo extension is a service without UI, so there is nowhere for Android to ask for the
 * BLE runtime permissions. This activity requests them (opened via the "open" bonus action or
 * the permission notification) and hosts the light-automation toggles, persisted in
 * [B54Settings] and applied by the running extension service.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var settings: B54Settings

    private val blePermissions: Array<String>
        get() = B54Permissions.required(Build.VERSION.SDK_INT).toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = B54Settings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PAD, PAD, PAD, PAD)
        }

        status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(0, 0, 0, PAD)
        }
        root.addView(status)

        root.addView(sectionTitle(getString(R.string.settings_automation_title)))
        root.addView(
            toggle(
                getString(R.string.settings_pause_dim_title),
                getString(R.string.settings_pause_dim_summary),
                settings.pauseDim,
            ) { settings.pauseDim = it },
        )
        root.addView(
            toggle(
                getString(R.string.settings_autolight_title),
                getString(R.string.settings_autolight_summary),
                settings.autoLight,
            ) { settings.autoLight = it },
        )

        setContentView(ScrollView(this).apply { addView(root) })

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

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, PAD, 0, PAD / 3)
    }

    private fun toggle(
        title: String,
        summary: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): ViewGroup {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, PAD / 3, 0, PAD / 3)
        }
        container.addView(
            Switch(this).apply {
                this.text = title
                textSize = 16f
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            },
        )
        container.addView(
            TextView(this).apply {
                this.text = summary
                textSize = 13f
            },
        )
        return container
    }

    companion object {
        private const val REQUEST_CODE = 1
        private const val PAD = 48
    }
}
