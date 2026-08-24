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
 *
 * Connection handling is tuned for the B54, which drops the link readily where a relaxed
 * autoConnect = true is used: direct connect (autoConnect = false) for aggressive connection
 * parameters, an explicit re-connect on every drop rather than trusting the OS auto-reconnect,
 * keepalive writes *with response* so a busy stack can't silently drop them, and the first
 * keepalive only after the CCCD write is confirmed (avoids colliding with the still-pending
 * descriptor write — Android allows a single outstanding GATT op).
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
     * (keepalive) and emits incoming messages as [Event.Message].
     *
     * Uses autoConnect = false (direct connect) for aggressive connection parameters, and
     * reconnects itself on every drop with a short backoff — the B54's watchdog drops any
     * client that goes quiet, so a fast, self-driven reconnect keeps the field usable across
     * the frequent disconnects.
     */
    fun connect(address: String): Flow<Event> = callbackFlow {
        val device = adapter?.getRemoteDevice(address)
        if (device == null) {
            Timber.w("Device $address could not be resolved")
            close()
            return@callbackFlow
        }

        val levelPayload = B54Protocol.REQ_LEVEL.toByteArray(Charsets.US_ASCII)
        val beamPayload = B54Protocol.REQ_BEAM.toByteArray(Charsets.US_ASCII)
        // Set once the CCCD write is confirmed; gates keepalive writes so the first one can't
        // collide with the still-pending descriptor write.
        val rxCharRef = java.util.concurrent.atomic.AtomicReference<BluetoothGattCharacteristic?>(null)
        // Discovered in onServicesDiscovered, promoted to rxCharRef in onDescriptorWrite.
        val pendingRxRef = java.util.concurrent.atomic.AtomicReference<BluetoothGattCharacteristic?>(null)
        val gattRef = java.util.concurrent.atomic.AtomicReference<BluetoothGatt?>(null)
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val attempt = java.util.concurrent.atomic.AtomicInteger(0)

        // Write a read request WITH response: a busy stack can't silently drop it (unlike
        // no-response). All payloads are read requests ($l, $b) — never a set/control command.
        fun write(bytes: ByteArray): Boolean {
            val gatt = gattRef.get() ?: return false
            val rx = rxCharRef.get() ?: return false
            return try {
                @Suppress("DEPRECATION")
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                rx.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(rx)
            } catch (e: Exception) {
                Timber.w(e, "RX write failed")
                false
            }
        }

        // Forward-declared so onConnectionStateChange can trigger a reconnect.
        val openConnection = java.util.concurrent.atomic.AtomicReference<() -> Unit>(null)

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Timber.i("GATT connected, discovering services")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Timber.i("GATT disconnected (status=$status)")
                    rxCharRef.set(null)
                    pendingRxRef.set(null)
                    gatt.close()
                    gattRef.compareAndSet(gatt, null)
                    trySend(Event.Disconnected)
                    if (active.get()) {
                        val n = attempt.getAndIncrement()
                        val backoff = minOf(1000L shl n.coerceAtMost(3), 8000L) // 1,2,4,8s cap
                        launch {
                            delay(backoff)
                            if (active.get()) openConnection.get()?.invoke()
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(NUS_SERVICE)
                if (service == null) {
                    Timber.w("NUS service not found")
                    return
                }
                val rx = service.getCharacteristic(NUS_RX)
                pendingRxRef.set(rx)
                val tx = service.getCharacteristic(NUS_TX)
                val cccd = tx?.getDescriptor(CCCD)
                if (tx != null && cccd != null) {
                    gatt.setCharacteristicNotification(tx, true)
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                    // Connected/keepalive proceed from onDescriptorWrite once CCCD is confirmed.
                } else {
                    // No notify path — publish RX and report connected right away.
                    Timber.w("TX/CCCD missing, connecting without notify")
                    attempt.set(0)
                    rxCharRef.set(rx)
                    trySend(Event.Connected)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCCD) {
                    attempt.set(0)
                    rxCharRef.set(pendingRxRef.get())
                    trySend(Event.Connected)
                    write(levelPayload) // first keepalive, now that no descriptor write is pending
                }
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

        openConnection.set {
            gattRef.getAndSet(null)?.close()
            gattRef.set(device.connectGatt(context, false, gattCallback))
        }
        openConnection.get()?.invoke()

        // Keepalive: one read request per second (write() no-ops until rxCharRef is set, so
        // nothing races the descriptor write). Mostly "$l" (keepalive + fresh battery); every
        // 3rd tick "$b" instead, to refresh the active beam mode for the light-mode field.
        // One write per tick — no back-to-back writes that could collide on the single-op stack.
        val keepalive = launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                write(if (tick % 3 == 2) beamPayload else levelPayload)
                tick++
            }
        }

        awaitClose {
            active.set(false)
            keepalive.cancel()
            gattRef.getAndSet(null)?.close()
        }
    }

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString(B54Protocol.NUS_SERVICE)
        val NUS_RX: UUID = UUID.fromString(B54Protocol.NUS_RX)
        val NUS_TX: UUID = UUID.fromString(B54Protocol.NUS_TX)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
