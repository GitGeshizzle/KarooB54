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
import android.bluetooth.le.ScanFilter
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

    /** Commands queued from outside (e.g. automations), drained one per keepalive tick. */
    private val outbound = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    /**
     * Queue an ASCII command to send to the connected light. It goes out on the next keepalive
     * tick — one write per tick, so it never collides with the keepalive on the single-op GATT
     * stack (latency is up to ~1 s, fine for the automations that use it).
     */
    fun send(command: String) {
        outbound.add(command.toByteArray(Charsets.US_ASCII))
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
        // BALANCED (not LOW_LATENCY): when the Karoo holds several other sensors, an aggressive
        // scan is one of the worst radio-coexistence offenders — it starves the other links'
        // radio time. BALANCED still finds the light within a few seconds.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
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
     * Scan-then-connect so the link establishes even when the Karoo's radio is shared with
     * several other sensors. A real drop retries quickly (BALANCED scan); once the light proves
     * absent (e.g. switched off) it backs off to a low-power scan with a growing gap, so hours
     * without the light barely touch the battery — and it reconnects on its own when the light
     * returns. Once connected it requests CONNECTION_PRIORITY_HIGH so the keepalive keeps beating
     * the light's ~1-2 s watchdog even on a busy radio.
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
        val infoPayload = B54Protocol.REQ_INFO.toByteArray(Charsets.US_ASCII)
        outbound.clear() // drop any commands left over from a previous connection
        // Set once the CCCD write is confirmed; gates keepalive writes so the first one can't
        // collide with the still-pending descriptor write.
        val rxCharRef = java.util.concurrent.atomic.AtomicReference<BluetoothGattCharacteristic?>(null)
        // Discovered in onServicesDiscovered, promoted to rxCharRef in onDescriptorWrite.
        val pendingRxRef = java.util.concurrent.atomic.AtomicReference<BluetoothGattCharacteristic?>(null)
        val gattRef = java.util.concurrent.atomic.AtomicReference<BluetoothGatt?>(null)
        val scanCbRef = java.util.concurrent.atomic.AtomicReference<ScanCallback?>(null)
        val scanTimeoutRef = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        // True once the current link reached "connected"; distinguishes a real drop (retry fast)
        // from a connect attempt that never landed (back off, go low-power).
        val wasConnected = java.util.concurrent.atomic.AtomicBoolean(false)
        val attempt = java.util.concurrent.atomic.AtomicInteger(0)

        // Write WITH response: a busy stack can't silently drop it (unlike no-response).
        // Payloads are reads ($l/$b/$i) or the two gated control writes ($B/$I) — see B54Protocol.
        fun write(bytes: ByteArray): Boolean {
            val gatt = gattRef.get() ?: return false
            val rx = rxCharRef.get() ?: return false
            return try {
                @Suppress("DEPRECATION")
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                rx.value = bytes
                @Suppress("DEPRECATION")
                val ok = gatt.writeCharacteristic(rx)
                // A false return = the stack was busy and dropped this write (e.g. the previous
                // with-response write isn't acked yet). A dropped keepalive is what trips the
                // light's watchdog, so surface it — it tells a watchdog drop from "light off".
                if (!ok) Timber.w("write dropped (GATT busy): ${String(bytes, Charsets.US_ASCII)}")
                ok
            } catch (e: Exception) {
                Timber.w(e, "RX write failed")
                false
            }
        }

        // Forward-declared so onConnectionStateChange can trigger a reconnect.
        val openConnection = java.util.concurrent.atomic.AtomicReference<() -> Unit>(null)

        // A scan round found nothing (light absent / switched off). Count it and retry with a
        // growing gap (up to 30 s) so a long absence barely touches the radio; the next
        // openConnection scans low-power. It reconnects on its own once the light is back.
        fun scheduleAfterMiss() {
            if (!active.get()) return
            val n = attempt.incrementAndGet()
            val backoff = minOf(1000L shl n.coerceAtMost(5), MAX_BACKOFF_MS) // 2,4,8,16,30,30…s
            Timber.i("scan miss #$n, retry in ${backoff}ms (low power)")
            launch {
                delay(backoff)
                if (active.get()) openConnection.get()?.invoke()
            }
        }

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Ask the controller to give this link a short connection interval, so our
                    // ~1 s keepalive reliably lands within the light's ~1-2 s watchdog even when
                    // the radio is shared with several other sensors.
                    val ok = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    Timber.i("GATT connected (status=${statusName(status)}); priority HIGH requested=$ok; discovering services")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Timber.i("GATT disconnected (status=${statusName(status)})")
                    val dropped = wasConnected.getAndSet(false)
                    rxCharRef.set(null)
                    pendingRxRef.set(null)
                    gatt.close()
                    gattRef.compareAndSet(gatt, null)
                    trySend(Event.Disconnected)
                    if (active.get()) {
                        if (dropped) {
                            // A real link drop: the light may still be right here (brief glitch /
                            // watchdog), so retry quickly and responsively (attempt was reset on
                            // connect, so this scans balanced). If it's actually gone, the scan
                            // window will elapse and scheduleAfterMiss() ramps down to low-power.
                            launch {
                                delay(RECONNECT_DELAY_MS)
                                if (active.get()) openConnection.get()?.invoke()
                            }
                        } else {
                            // A connect attempt that never landed — back off (low-power).
                            scheduleAfterMiss()
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
                    wasConnected.set(true)
                    rxCharRef.set(rx)
                    trySend(Event.Connected)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCCD) {
                    attempt.set(0)
                    wasConnected.set(true)
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

        // Scan-then-connect: when the radio is shared with several other sensors, a blind
        // connectGatt (direct or autoConnect) fails to grab a connect slot and just hangs. The
        // light still advertises and a scan finds it in ~1 s, so we scan by address and issue a
        // *direct* connect the instant we see an advertisement — it lands in the window the light
        // is actually listening. Then priority HIGH keeps it.
        openConnection.set {
            wasConnected.set(false)
            gattRef.getAndSet(null)?.close()
            scanTimeoutRef.getAndSet(null)?.cancel()
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                Timber.w("No scanner; cannot connect")
                return@set
            }
            // First attempt scans BALANCED for a quick connect. Once the light has proven absent
            // (attempt > 0 after scan misses) we drop to LOW_POWER — a much lower radio duty
            // cycle — so hours of "light switched off" don't drain the Karoo battery.
            val lowPower = attempt.get() > 0
            val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
            val scanSettings = ScanSettings.Builder()
                .setScanMode(if (lowPower) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_BALANCED)
                .build()
            val scanCb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val cb = scanCbRef.getAndSet(null) ?: return // already handled this round
                    scanTimeoutRef.getAndSet(null)?.cancel()
                    scanner.stopScan(cb)
                    Timber.i("scan hit $address (rssi=${result.rssi}) -> direct connect")
                    gattRef.set(device.connectGatt(context, false, gattCallback))
                }

                override fun onScanFailed(errorCode: Int) {
                    Timber.w("Connect-scan failed: $errorCode")
                }
            }
            scanCbRef.getAndSet(scanCb)?.let { runCatching { scanner.stopScan(it) } }
            Timber.i("scanning for $address (${if (lowPower) "low-power" else "balanced"}), attempt=${attempt.get()}")
            scanner.startScan(filters, scanSettings, scanCb)
            // Bounded scan window: a BLE scan left running for minutes goes stale/throttled and
            // stops delivering hits (seen in the field — a fresh scan connected instantly where a
            // long-running one never did). If nothing is found in the window, stop and retry via
            // scheduleAfterMiss(), which both refreshes the scan and backs the radio off.
            scanTimeoutRef.set(
                launch {
                    delay(SCAN_WINDOW_MS)
                    if (scanCbRef.compareAndSet(scanCb, null)) {
                        runCatching { scanner.stopScan(scanCb) }
                        scheduleAfterMiss()
                    }
                },
            )
        }
        openConnection.get()?.invoke()

        // Keepalive: one write per second (write() no-ops until rxCharRef is set, so nothing
        // races the descriptor write). A queued command (from send()) takes the tick; otherwise
        // mostly "$l" (keepalive + fresh battery), with "$b" (~every 3rd tick) and "$i" (~every
        // 6th) mixed in to refresh the beam mode and info flags. One write per tick — no
        // back-to-back writes that could collide on the single-op stack.
        val keepalive = launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                val queued = outbound.poll()
                write(
                    when {
                        queued != null -> queued
                        tick % 6 == 5 -> infoPayload
                        tick % 3 == 2 -> beamPayload
                        else -> levelPayload
                    },
                )
                tick++
            }
        }

        awaitClose {
            active.set(false)
            keepalive.cancel()
            scanTimeoutRef.getAndSet(null)?.cancel()
            outbound.clear()
            scanCbRef.getAndSet(null)?.let { cb -> runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) } }
            gattRef.getAndSet(null)?.close()
        }
    }

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString(B54Protocol.NUS_SERVICE)
        val NUS_RX: UUID = UUID.fromString(B54Protocol.NUS_RX)
        val NUS_TX: UUID = UUID.fromString(B54Protocol.NUS_TX)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Delay before retrying after a real link drop (short — the light is likely still here). */
        private const val RECONNECT_DELAY_MS = 1_000L

        /** How long one scan round runs before being refreshed/backed off. */
        private const val SCAN_WINDOW_MS = 20_000L

        /** Cap for the between-rounds backoff while the light stays absent. */
        private const val MAX_BACKOFF_MS = 30_000L

        /** Human-readable GATT status for logs — the codes that pin down on-bike failures. */
        private fun statusName(status: Int): String = when (status) {
            0 -> "SUCCESS(0)"
            8 -> "CONN_TIMEOUT(8)" // supervision timeout — radio lost the link (contention)
            19 -> "TERMINATE_PEER_USER(19)" // the light dropped us (its watchdog)
            22 -> "TERMINATE_LOCAL_HOST(22)"
            62 -> "CONN_FAIL_ESTABLISH(62)"
            133 -> "GATT_ERROR(133)" // generic — usually failed to establish under load
            else -> "status=$status"
        }
    }
}
