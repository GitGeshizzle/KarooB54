package io.github.gitgeshizzle.karoob54.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import io.github.gitgeshizzle.karoob54.B54Protocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Native BLE (android.bluetooth) for the B54.
 *
 * The B54 advertises its name and the NUS service UUID in the scan response, so we scan
 * without a hardware service-UUID filter and match in software. GATT connect, notify on
 * TX, and a 1 s keepalive write on RX. Only read requests ($l) — see [B54Protocol].
 */
@SuppressLint("MissingPermission")
class B54BleManager(private val context: Context) {

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    data class Scanned(val address: String, val name: String?, val serviceUuids: List<String>, val rssi: Int)

    sealed interface Event {
        data object Connected : Event
        data object Disconnected : Event
        data class Message(val text: String) : Event
    }

    /** Unfiltered BLE scan; matching happens in the caller (B54Extension). */
    fun scan(): Flow<Scanned> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Timber.w("No BluetoothLeScanner (Bluetooth off?)")
            close()
            return@callbackFlow
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rec = result.scanRecord
                trySend(
                    Scanned(
                        address = result.device.address,
                        name = rec?.deviceName,
                        serviceUuids = rec?.serviceUuids?.map { it.uuid.toString() } ?: emptyList(),
                        rssi = result.rssi,
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.w("Scan failed: $errorCode")
            }
        }
        // filters = null -> all advertisements (legacy + extended)
        scanner.startScan(null, settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }

    /**
     * Connects to [address], enables TX notifications, sends "$l" once per second
     * (keepalive) and emits incoming messages as [Event.Message]. autoConnect = true lets
     * the stack reconnect automatically when the (frequently disconnecting) B54 returns.
     */
    fun connect(address: String): Flow<Event> = callbackFlow {
        val device = adapter?.getRemoteDevice(address)
        if (device == null) {
            Timber.w("Device $address could not be resolved")
            close()
            return@callbackFlow
        }

        val rxCharRef = java.util.concurrent.atomic.AtomicReference<BluetoothGattCharacteristic?>(null)

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Timber.i("GATT connected, discovering services")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Timber.i("GATT disconnected")
                    rxCharRef.set(null)
                    trySend(Event.Disconnected)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(NUS_SERVICE)
                if (service == null) {
                    Timber.w("NUS service not found")
                    return
                }
                rxCharRef.set(service.getCharacteristic(NUS_RX))
                val tx = service.getCharacteristic(NUS_TX)
                if (tx != null) {
                    gatt.setCharacteristicNotification(tx, true)
                    tx.getDescriptor(CCCD)?.let { cccd ->
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
                trySend(Event.Connected)
            }

            // API < 33
            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == NUS_TX) {
                    @Suppress("DEPRECATION")
                    characteristic.value?.let { emitMessage(it) }
                }
            }

            // API >= 33
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid == NUS_TX) emitMessage(value)
            }

            private fun emitMessage(data: ByteArray) {
                trySend(Event.Message(String(data, Charsets.US_ASCII)))
            }
        }

        val gatt = device.connectGatt(context, true, gattCallback)

        // Keepalive: while connected, write "$l" once per second.
        val keepalive = launch {
            val payload = B54Protocol.REQ_LEVEL.toByteArray(Charsets.US_ASCII)
            while (isActive) {
                val rx = rxCharRef.get()
                if (rx != null) {
                    try {
                        @Suppress("DEPRECATION")
                        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION")
                        rx.value = payload
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(rx)
                    } catch (e: Exception) {
                        Timber.w(e, "Keepalive write failed")
                    }
                }
                delay(1000)
            }
        }

        awaitClose {
            keepalive.cancel()
            gatt.close()
        }
    }

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString(B54Protocol.NUS_SERVICE)
        val NUS_RX: UUID = UUID.fromString(B54Protocol.NUS_RX)
        val NUS_TX: UUID = UUID.fromString(B54Protocol.NUS_TX)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
