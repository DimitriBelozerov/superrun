package de.dimitri.pitpatlite

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Single-connection BLE client for a PitPat-T01 (FBA0). All listener callbacks run on the main thread. */
@SuppressLint("MissingPermission")
class TreadmillBle(private val context: Context) {

    var listener: Listener? = null
        set(v) {
            field = v
            v?.onConnectionChanged(state, null)
            lastStatus?.let { v?.onStatus(it) }
        }
    var lastStatus: PitPatProtocol.Status? = null
        private set

    enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    interface Listener {
        fun onConnectionChanged(state: ConnState, message: String?)
        fun onStatus(status: PitPatProtocol.Status)
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeBusy = false
    private var reconnectAttempts = 0
    private var userDisconnect = false
    private var discoveryStarted = false
    private var notifyRequested = false

    var state: ConnState = ConnState.DISCONNECTED
        private set
    var address: String? = null
        private set

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    private fun setState(s: ConnState, msg: String? = null) {
        state = s
        if (s != ConnState.CONNECTED) lastStatus = null
        main.post { listener?.onConnectionChanged(s, msg) }
    }

    /** Connect directly to a known address, or scan for a treadmill. */
    fun connect(knownAddress: String?) {
        userDisconnect = false
        reconnectAttempts = 0
        if (knownAddress != null) connectTo(adapter.getRemoteDevice(knownAddress)) else scan()
    }

    fun disconnect() {
        userDisconnect = true
        stopScan()
        main.removeCallbacksAndMessages(null)
        closeGatt()
        setState(ConnState.DISCONNECTED)
    }

    fun send(packet: ByteArray) {
        if (state != ConnState.CONNECTED) return
        main.post {
            writeQueue.addLast(packet)
            drainQueue()
        }
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            setState(ConnState.DISCONNECTED, context.getString(R.string.bt_off)); return
        }
        setState(ConnState.SCANNING)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = (result.device.name ?: result.scanRecord?.deviceName ?: "").lowercase()
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                if (PitPatProtocol.NAME_HINTS.any { name.contains(it) } || uuids.contains(PitPatProtocol.SERVICE_UUID)) {
                    stopScan()
                    connectTo(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                setState(ConnState.DISCONNECTED, "Scan-Fehler $errorCode")
            }
        }
        scanCallback = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        main.postDelayed({
            if (scanCallback === cb) {
                stopScan()
                setState(ConnState.DISCONNECTED, context.getString(R.string.not_found))
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        scanCallback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCallback = null
    }

    private fun connectTo(device: BluetoothDevice) {
        closeGatt()
        address = device.address
        discoveryStarted = false
        notifyRequested = false
        setState(ConnState.CONNECTING)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        writeQueue.clear()
        writeBusy = false
        writeChar = null
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
    }

    private fun scheduleReconnect() {
        if (userDisconnect) return
        val addr = address
        if (addr == null || reconnectAttempts >= MAX_RECONNECTS) {
            setState(ConnState.DISCONNECTED, if (addr == null) null else "Verbindung verloren")
            return
        }
        reconnectAttempts++
        setState(ConnState.CONNECTING, "Verbindung verloren – neuer Versuch $reconnectAttempts")
        main.postDelayed({ if (!userDisconnect) connectTo(adapter.getRemoteDevice(addr)) }, RECONNECT_DELAY_MS)
    }

    private fun drainQueue() {
        if (writeBusy) return
        val g = gatt ?: return
        val c = writeChar ?: return
        val packet = writeQueue.removeFirstOrNull() ?: return
        writeBusy = true
        Log.d(TAG, "write: ${PitPatProtocol.toHex(packet)}")
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = packet
                g.writeCharacteristic(c)
            }
        }
        if (!ok) {
            Log.w(TAG, "write failed: ${PitPatProtocol.toHex(packet)}")
            writeBusy = false
            main.postDelayed({ drainQueue() }, 100)
        }
    }

    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(c, true)) return false
        val desc = c.getDescriptor(PitPatProtocol.CCCD_UUID) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(desc)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "conn state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // Status packets are 48+ bytes; default MTU 23 would truncate notifications.
                if (!g.requestMtu(MTU)) startDiscovery(g)
            } else {
                main.post {
                    closeGatt()
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // Android may report this twice (our request + the peer's exchange); discover only once.
            Log.d(TAG, "mtu=$mtu status=$status")
            startDiscovery(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (notifyRequested) return
            notifyRequested = true
            val service = g.getService(PitPatProtocol.SERVICE_UUID)
            val w = service?.getCharacteristic(PitPatProtocol.WRITE_UUID)
            val n = service?.getCharacteristic(PitPatProtocol.NOTIFY_UUID)
            if (service == null || w == null || n == null) {
                main.post {
                    userDisconnect = true
                    closeGatt()
                    setState(ConnState.DISCONNECTED, context.getString(R.string.no_service))
                }
                return
            }
            writeChar = w
            if (!enableNotifications(g, n)) {
                main.post { closeGatt(); scheduleReconnect() }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            reconnectAttempts = 0
            setState(ConnState.CONNECTED)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "write done status=$status")
            main.post {
                writeBusy = false
                drainQueue()
            }
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) c.value?.let { handleNotification(it) }
        }
    }

    private fun startDiscovery(g: BluetoothGatt) {
        if (discoveryStarted) return
        discoveryStarted = true
        g.discoverServices()
    }

    private fun handleNotification(value: ByteArray) {
        val status = PitPatProtocol.parseStatus(value) ?: run {
            Log.w(TAG, "short packet (${value.size}): ${PitPatProtocol.toHex(value)}")
            return
        }
        main.post {
            lastStatus = status
            listener?.onStatus(status)
        }
    }

    companion object {
        private const val TAG = "TreadmillBle"
        private const val MTU = 185
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECTS = 5
    }
}
