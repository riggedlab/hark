package io.github.riggedlab.hark

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/** 扫描按键窗口 → 系统绑定 → MTU/服务/加密订阅。控制操作统一在主线程，音频直接交给写入队列。 */
@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val onAudio: (seq: Int, opus: ByteArray) -> Unit,
    private val onBattery: (mv: Int) -> Unit,
    private val onState: (LinkState, String?) -> Unit,
) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("ble", Context.MODE_PRIVATE)
    @Volatile private var gatt: BluetoothGatt? = null
    private enum class Stage { IDLE, CONNECTING, BONDING, MTU, SERVICES, BATTERY, CCC, STREAMING }
    @Volatile private var stage = Stage.IDLE
    private var running = false
    private var scanning = false
    private var receiverRegistered = false
    private var selectedDevice: BluetoothDevice? = null
    private var blockedAddress: String? = null
    private var scanMessage: String? = null
    private var removingAddress: String? = null
    private var repairOnDiscovery = false
    private var authFailures = 0
    private var timeout: Runnable? = null
    private val pendingCcc = ArrayDeque<BluetoothGattCharacteristic>()
    @Volatile private var lastNotifyAt = 0L

    /** 板子安静时也每秒发一个序号包；链路还在却长时间没数据，就当断开重连。 */
    private val stallCheck: Runnable = object : Runnable {
        override fun run() {
            val g = gatt
            if (!running || g == null || stage != Stage.STREAMING) return
            val idle = android.os.SystemClock.elapsedRealtime() - lastNotifyAt
            if (idle < STALL_MS) { main.postDelayed(this, STALL_MS - idle); return }
            android.util.Log.w("BleLink", "no data for ${idle}ms while streaming, reconnecting")
            closeGatt()
            onState(LinkState.RECONNECTING, null)
            arm(1000) { connect(g.device, auto = true) }
        }
    }

    fun start() {
        if (running) return
        running = true
        // 蓝牙广播可能来自独立的高权限进程，需 EXPORTED；此 action 是系统保护广播。
        ContextCompat.registerReceiver(context, bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        val saved = prefs.getString(KEY_ADDR, null)
        val device = saved?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
        if (device != null && device.bondState == BluetoothDevice.BOND_BONDED) {
            connect(device, auto = true)
        } else {
            prefs.edit().remove(KEY_ADDR).apply()
            scan(if (saved != null) "系统配对已失效，请点「重新配对」，再按一下板子上的按键" else null)
        }
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        timeout = null
        removingAddress = null
        stopScan()
        closeGatt()
        if (receiverRegistered) {
            context.unregisterReceiver(bondReceiver)
            receiverRegistered = false
        }
        onState(LinkState.STOPPED, null)
    }

    fun repair() {
        val device = selectedDevice ?: prefs.getString(KEY_ADDR, null)?.let {
            runCatching { adapter.getRemoteDevice(it) }.getOrNull()
        }
        stop()
        prefs.edit().remove(KEY_ADDR).apply()
        blockedAddress = null
        repairOnDiscovery = device == null
        start()
        if (device != null) removeSystemBond(device)
    }

    /** removeBond 是异步操作，等 NONE 后才允许扫描结果进入 createBond。 */
    private fun removeSystemBond(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_NONE) return false
        val requested = runCatching {
            device.javaClass.getMethod("removeBond").invoke(device) == true
        }.getOrDefault(false)
        if (!requested) {
            scanMessage = "请按一下板子上的按键；若仍无法配对，请先在系统蓝牙设置中忘记 Hark DT-1"
            onState(LinkState.SCANNING, scanMessage)
            return false
        }
        if (device.bondState == BluetoothDevice.BOND_NONE) return false
        removingAddress = device.address
        arm(5000) {
            removingAddress = null
            blockedAddress = device.address
            scanMessage = "系统未解除旧配对，请在蓝牙设置中忘记 Hark DT-1，再点「重新配对」"
            onState(LinkState.SCANNING, scanMessage)
        }
        return true
    }

    private fun scan(reason: String? = null) {
        if (!running) return
        scanMessage = reason ?: "请按一下板子上的按键"
        onState(LinkState.SCANNING, scanMessage)
        if (scanning) return
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onState(LinkState.SCANNING, "请打开手机蓝牙，然后按一下板子上的按键")
            arm(5000) { scan(scanMessage) }
            return
        }
        // 不依赖系统对 128-bit service data 的硬件过滤，在回调里检查配对标记。
        // 只在配对时扫描，App 在前台，亮屏下无过滤扫描可用。
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        scanner.startScan(null, settings, scanCb)
    }

    private fun stopScan() {
        if (scanning) runCatching { adapter.bluetoothLeScanner?.stopScan(scanCb) }
        scanning = false
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!running || !scanning || result.device.address == blockedAddress) return@post
                val mark = result.scanRecord?.getServiceData(ParcelUuid(SVC_UUID))
                if (mark?.contentEquals(PAIR_MARKER) != true) return@post
                if (result.device.address == removingAddress) {
                    if (result.device.bondState != BluetoothDevice.BOND_NONE) return@post
                    removingAddress = null
                    clearTimeout()
                }
                if (repairOnDiscovery) {
                    repairOnDiscovery = false
                    selectedDevice = result.device
                    if (removeSystemBond(result.device)) return@post
                }
                stopScan()
                connect(result.device, auto = false)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                if (!running || !scanning) return@post
                scanning = false
                onState(LinkState.SCANNING, "扫描失败 ($errorCode)，5 秒后重试；请按一下板子上的按键")
                arm(5000) { scan(scanMessage) }
            }
        }
    }

    private fun connect(device: BluetoothDevice, auto: Boolean) {
        clearTimeout()
        closeGatt()
        selectedDevice = device
        stage = Stage.CONNECTING
        onState(if (auto) LinkState.RECONNECTING else LinkState.CONNECTING, null)
        gatt = device.connectGatt(context, auto, gattCb, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) pairingProblem("无法建立蓝牙连接，请重新配对")
        else if (!auto) arm(15_000) { pairingProblem("连接超时，请按键打开窗口后重新配对", rescan = true) }
    }

    private fun beginBond(g: BluetoothGatt) {
        stage = Stage.BONDING
        onState(LinkState.PAIRING, "正在配对，请确认系统配对提示")
        arm(60_000) { pairingProblem("配对超时，请按键后点「重新配对」", rescan = true) }
        if (g.device.bondState == BluetoothDevice.BOND_BONDED) bonded(g)
        else if (g.device.bondState != BluetoothDevice.BOND_BONDING && !g.device.createBond()) {
            pairingProblem("无法发起系统配对，请重新配对", rescan = true)
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            if (!running || device.bondState != bond) return // 忽略排队过久的旧广播
            if (device.address == removingAddress && bond == BluetoothDevice.BOND_NONE) {
                removingAddress = null
                clearTimeout()
                return
            }
            val g = gatt ?: return
            if (!running || device.address != g.device.address) return
            when (bond) {
                BluetoothDevice.BOND_BONDED -> if (stage == Stage.BONDING) bonded(g)
                BluetoothDevice.BOND_NONE -> pairingProblem(
                    "配对失败或已被解除；请按键后点「重新配对」", rescan = true)
            }
        }
    }

    private fun bonded(g: BluetoothGatt) {
        if (g.device.bondState != BluetoothDevice.BOND_BONDED) return
        // 未绑定的扫描结果绝不存地址。后续加密/订阅失败仍会清除。
        prefs.edit().putString(KEY_ADDR, g.device.address).apply()
        stage = Stage.MTU
        onState(LinkState.CONNECTING, "已配对，正在建立加密音频连接")
        armHandshake()
        if (!g.requestMtu(247)) pairingProblem("无法协商音频连接，请重新配对")
    }

    private fun armHandshake() = arm(10_000) {
        pairingProblem("加密连接或订阅超时，设备可能已与其它手机配对；请点「重新配对」")
    }

    private fun pairingProblem(reason: String, rescan: Boolean = false) {
        // 走远、信号边缘时半途断开很常见。已配对的设备只管继续等它回来，
        // 只有板子连续拒绝加密（密钥真的对不上）才需要用户重新配对。
        val device = gatt?.device ?: selectedDevice
        if (device != null && device.bondState == BluetoothDevice.BOND_BONDED &&
            device.address == prefs.getString(KEY_ADDR, null) && authFailures < MAX_AUTH_FAILURES) {
            clearTimeout()
            closeGatt()
            onState(LinkState.RECONNECTING, null)
            arm(1000) { connect(device, auto = true) }
            return
        }
        authFailures = 0
        blockedAddress = gatt?.device?.address ?: selectedDevice?.address
        prefs.edit().remove(KEY_ADDR).apply()
        clearTimeout()
        closeGatt()
        if (rescan) scan(reason)
        else onState(LinkState.PAIRING_REQUIRED, reason)
    }

    private fun closeGatt() {
        val old = gatt
        gatt = null
        stage = Stage.IDLE
        pendingCcc.clear()
        old?.disconnect()
        old?.close()
    }

    private fun arm(ms: Long, action: () -> Unit) {
        clearTimeout()
        timeout = Runnable { if (running) action() }.also { main.postDelayed(it, ms) }
    }

    private fun clearTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    private fun dispatch(g: BluetoothGatt, action: () -> Unit) {
        main.post { if (running && g === gatt) action() }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = dispatch(g) {
            android.util.Log.i("BleLink", "gatt state $newState status $status stage $stage")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                beginBond(g)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val established = stage == Stage.STREAMING
                if (status == 5 || status == 15) authFailures++
                if (status == 5 || status == 15 || !established) {
                    pairingProblem("连接或加密失败 ($status)，请按键后点「重新配对」")
                } else {
                    clearTimeout()
                    closeGatt()
                    onState(LinkState.RECONNECTING, null)
                    arm(1000) { connect(g.device, auto = true) }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = dispatch(g) {
            if (stage != Stage.MTU) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < 125) {
                pairingProblem("音频连接协商失败 ($status)，请重新配对")
                return@dispatch
            }
            stage = Stage.SERVICES
            armHandshake()
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            if (!g.discoverServices()) pairingProblem("无法读取设备服务，请重新配对")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = dispatch(g) {
            if (stage != Stage.SERVICES) return@dispatch
            val svc = g.getService(SVC_UUID)
            val battery = svc?.getCharacteristic(BATT_UUID)
            val audio = svc?.getCharacteristic(AUDIO_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || battery == null || audio == null) {
                pairingProblem("无法找到完整的 Hark DT-1 服务，请检查固件后重新配对")
                return@dispatch
            }
            pendingCcc.add(battery)
            pendingCcc.add(audio)
            stage = Stage.BATTERY
            armHandshake()
            if (!g.readCharacteristic(battery)) pairingProblem("无法读取加密电量，请重新配对")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) handleRead(g, c, c.value, status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(g, c, value, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = dispatch(g) {
            if (stage != Stage.CCC || d.uuid != CCC_UUID) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pairingProblem("加密订阅失败 ($status)，设备可能已更换手机；请点「重新配对」")
            } else if (d.characteristic.uuid == AUDIO_UUID) {
                clearTimeout()
                stage = Stage.STREAMING
                authFailures = 0
                lastNotifyAt = android.os.SystemClock.elapsedRealtime()
                main.removeCallbacks(stallCheck)
                main.postDelayed(stallCheck, STALL_MS)
                onState(LinkState.STREAMING, g.device.name)
            } else enableNextNotify(g)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleNotify(g, c.uuid, c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(g, c.uuid, value)
        }
    }

    private fun handleRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray?, status: Int) = dispatch(g) {
        if (stage != Stage.BATTERY || c.uuid != BATT_UUID) return@dispatch
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.size < 2) {
            pairingProblem("电量读取或加密失败 ($status)，请点「重新配对」")
            return@dispatch
        }
        onBattery(u16(value, 0))
        enableNextNotify(g)
    }

    private fun enableNextNotify(g: BluetoothGatt) {
        val c = pendingCcc.removeFirstOrNull() ?: return
        val d = c.getDescriptor(CCC_UUID)
        if (d == null || !g.setCharacteristicNotification(c, true)) {
            pairingProblem("设备不支持音频订阅，请检查固件")
            return
        }
        stage = Stage.CCC
        armHandshake()
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
        if (!ok) pairingProblem("无法写入加密订阅，请点「重新配对」")
    }

    private fun handleNotify(g: BluetoothGatt, uuid: UUID, value: ByteArray?) {
        if (g !== gatt || stage != Stage.STREAMING || value == null) return
        lastNotifyAt = android.os.SystemClock.elapsedRealtime()
        when (uuid) {
            AUDIO_UUID -> if (value.size >= 2) onAudio(u16(value, 0), value.copyOfRange(2, value.size))
            BATT_UUID -> if (value.size >= 2) onBattery(u16(value, 0))
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    companion object {
        private const val KEY_ADDR = "device_address"
        private const val MAX_AUTH_FAILURES = 3
        private const val STALL_MS = 20_000L
        private val PAIR_MARKER = byteArrayOf(1)
        val SVC_UUID: UUID = UUID.fromString("5a1d0001-7e3c-4b8a-9f00-524947000000")
        val AUDIO_UUID: UUID = UUID.fromString("5a1d0002-7e3c-4b8a-9f00-524947000000")
        val BATT_UUID: UUID = UUID.fromString("5a1d0003-7e3c-4b8a-9f00-524947000000")
        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
