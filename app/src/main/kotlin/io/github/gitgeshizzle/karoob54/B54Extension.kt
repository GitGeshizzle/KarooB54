package io.github.gitgeshizzle.karoob54

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.gitgeshizzle.karoob54.ble.B54BleManager
import io.github.gitgeshizzle.karoob54.ble.B54Light
import io.github.gitgeshizzle.karoob54.data.BatteryDataType
import io.github.gitgeshizzle.karoob54.data.CyclesDataType
import io.github.gitgeshizzle.karoob54.data.RuntimeDataType
import io.github.gitgeshizzle.karoob54.data.TemperatureDataType
import io.github.gitgeshizzle.karoob54.data.VoltageDataType
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.SystemNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Karoo extension for the B54.
 *
 * - declares the data fields (battery, runtime, voltage, temperature, cycles)
 * - scans for the light via the Nordic UART Service
 * - connects on demand and streams the values
 */
class B54Extension : KarooExtension("b54", "0.1.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var bleManager: B54BleManager
    private var serviceJob: Job? = null
    private val devices = ConcurrentHashMap<String, B54Light>()

    override val types by lazy {
        listOf(
            BatteryDataType(extension),
            RuntimeDataType(extension),
            VoltageDataType(extension),
            TemperatureDataType(extension),
            CyclesDataType(extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        karooSystem = KarooSystemService(applicationContext)
        bleManager = B54BleManager(applicationContext)
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.connect { connected ->
                if (connected) {
                    Timber.i("Connected to Karoo system — requesting Bluetooth")
                    // Allows the extension to use BLE
                    karooSystem.dispatch(RequestBluetooth(extension))
                    // Without BLE runtime permissions the scan finds nothing -> notify the user
                    if (!hasScanPermission()) {
                        karooSystem.dispatch(
                            SystemNotification(
                                "b54-permissions",
                                getString(R.string.perm_needed),
                                action = getString(R.string.notif_perm_action),
                                actionIntent = SETTINGS_ACTION,
                            ),
                        )
                    }
                }
            }
        }
    }

    override fun onBonusAction(actionId: String) {
        if (actionId == "open") {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun hasScanPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    override fun startScan(emitter: Emitter<Device>) {
        Timber.d("startScan")
        val seen = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val nus = B54BleManager.NUS_SERVICE.toString()
        val job = CoroutineScope(Dispatchers.IO).launch {
            bleManager.scan().collect { s ->
                val name = s.name
                val isB54 = name != null && (name.startsWith("B54") || name.startsWith("sn")) ||
                    s.serviceUuids.any { it.equals(nus, ignoreCase = true) }
                if (isB54 && seen.add(s.address)) {
                    Timber.i("B54 detected: ${s.address} ($name)")
                    val light = devices.getOrPut(deviceUid(s.address)) { B54Light(bleManager, extension, s.address, name) }
                    emitter.onNext(light.source)
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        Timber.d("connectDevice $uid")
        val address = uid.substringAfter("${B54Light.PREFIX}-")
        devices.getOrPut(uid) { B54Light(bleManager, extension, address, null) }.connect(emitter)
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        serviceJob = null
        if (::karooSystem.isInitialized) {
            karooSystem.dispatch(ReleaseBluetooth(extension))
            karooSystem.disconnect()
        }
        super.onDestroy()
    }

    private fun deviceUid(address: String) = "${B54Light.PREFIX}-$address"

    companion object {
        private const val SETTINGS_ACTION = "io.github.gitgeshizzle.karoob54.SETTINGS"
    }
}
