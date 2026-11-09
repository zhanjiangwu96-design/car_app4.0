package com.example.mecanumcar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/** 搜索列表里的一项: 设备、广播名称(可能为空)、信号强度 */
class FoundDevice(val device: BluetoothDevice, val name: String, var rssi: Int)

/**
 * KT6368A (BLE 透传) 连接与发送。
 * 流程: 扫描 -> 名称等于 targetName 的设备自动连接 -> 发现服务 -> 自动选一个可写特征 -> send()
 * 所有回调到 onStatus / onNeedPick 都在主线程。
 */
@SuppressLint("MissingPermission")
class BleCar(
    private val ctx: Context,
    private val targetName: String,
    private val onStatus: (String) -> Unit,
    private val onNeedPick: (List<BluetoothDevice>) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanning = false

    private val found = LinkedHashMap<String, BluetoothDevice>()
    private val names = HashMap<String, String>()

    @Volatile
    var ready = false
        private set

    private fun say(msg: String) {
        main.post { onStatus(msg) }
    }

    fun nameOf(d: BluetoothDevice): String = names[d.address] ?: "未命名"

    // ---------------- 扫描 ----------------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val n = result.scanRecord?.deviceName ?: try { d.name } catch (e: SecurityException) { null }
            if (n.isNullOrEmpty()) return
            names[d.address] = n
            found[d.address] = d
            if (n == targetName) {
                main.post {
                    if (scanning) {
                        stopScan()
                        connect(d)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            say("扫描失败, 错误码 $errorCode")
        }
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            val list = found.values.toList()
            if (list.isEmpty()) say("没有扫描到蓝牙设备") else onNeedPick(list)
        }
    }

    fun startScan() {
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            say("请先打开手机蓝牙")
            return
        }
        disconnect()
        found.clear()
        names.clear()
        scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            say("无法获取蓝牙扫描器")
            return
        }
        scanning = true
        say("正在扫描 \"$targetName\" ...")
        try {
            scanner?.startScan(scanCb)
        } catch (e: SecurityException) {
            scanning = false
            say("没有蓝牙权限")
            return
        }
        main.postDelayed(scanTimeout, 10000)
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (scanning) {
            scanning = false
            try {
                scanner?.stopScan(scanCb)
            } catch (e: Exception) {
            }
        }
    }

    // ---------------- 搜索列表 (不自动连接, 可按名称筛选) ----------------
    private val listFound = LinkedHashMap<String, FoundDevice>()
    private var listCb: ((List<FoundDevice>) -> Unit)? = null
    private var listDone: (() -> Unit)? = null
    private var listScanner: BluetoothLeScanner? = null
    private var listScanning = false
    private var listPending = false

    private val listScanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val n = result.scanRecord?.deviceName ?: (try { d.name } catch (e: SecurityException) { null }) ?: ""
            val old = listFound[d.address]
            if (old == null) {
                listFound[d.address] = FoundDevice(d, n, result.rssi)
            } else {
                old.rssi = result.rssi
                if (old.name.isEmpty() && n.isNotEmpty()) listFound[d.address] = FoundDevice(d, n, result.rssi)
            }
            pushList()
        }

        override fun onScanFailed(errorCode: Int) {
            say("扫描失败, 错误码 $errorCode")
        }
    }

    private val listTimeout = Runnable {
        stopListScan()
        listDone?.invoke()
    }

    /** 把当前结果推给界面 (最多每 300ms 一次): 有名称的在前, 信号强的在前 */
    private fun pushList() {
        if (listPending) return
        listPending = true
        main.postDelayed({
            listPending = false
            val cb = listCb
            if (cb != null) {
                cb(listFound.values.sortedWith(compareBy<FoundDevice>({ it.name.isEmpty() }, { -it.rssi })))
            }
        }, 300)
    }

    /** 开始搜索, 持续 30 秒; cb 会在主线程里不断收到最新的设备列表, 结束时调用 onDone */
    fun startListScan(cb: (List<FoundDevice>) -> Unit, onDone: () -> Unit) {
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            say("请先打开手机蓝牙")
            onDone()
            return
        }
        stopListScan()
        listFound.clear()
        listCb = cb
        listDone = onDone
        val sc = ad.bluetoothLeScanner
        if (sc == null) {
            say("无法获取蓝牙扫描器")
            onDone()
            return
        }
        val st = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            sc.startScan(null, st, listScanCb)
            listScanner = sc
            listScanning = true
        } catch (e: SecurityException) {
            say("没有蓝牙权限")
            onDone()
            return
        }
        main.postDelayed(listTimeout, 30000)
    }

    fun stopListScan() {
        main.removeCallbacks(listTimeout)
        if (listScanning) {
            listScanning = false
            try {
                listScanner?.stopScan(listScanCb)
            } catch (e: Exception) {
            }
        }
    }

    // ---------------- 连接 ----------------
    fun connect(d: BluetoothDevice) {
        stopScan()
        stopListScan()
        say("正在连接 ${nameOf(d)} ...")
        gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        stopListScan()
        ready = false
        writeChar = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
        }
        gatt = null
        say("未连接")
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                say("已连接, 正在发现服务 ...")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                writeChar = null
                try {
                    g.close()
                } catch (e: Exception) {
                }
                if (gatt === g) gatt = null
                say("连接已断开")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val c = pickWriteChar(g)
            if (c == null) {
                say("没有找到可写特征, 无法发送")
                return
            }
            writeChar = c
            ready = true
            say("已连接, 写入特征 ${c.uuid.toString().substring(4, 8)} (服务 ${c.service.uuid.toString().substring(4, 8)})")
        }
    }

    /** 跳过通用的 1800/1801 服务, 优先"无响应写", 其次普通写 */
    private fun pickWriteChar(g: BluetoothGatt): BluetoothGattCharacteristic? {
        var normal: BluetoothGattCharacteristic? = null
        for (s in g.services) {
            val u = s.uuid.toString().lowercase()
            if (u.startsWith("00001800") || u.startsWith("00001801")) continue
            for (c in s.characteristics) {
                val p = c.properties
                if ((p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) return c
                if (normal == null && (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) normal = c
            }
        }
        return normal
    }

    // ---------------- 发送 ----------------
    @Suppress("DEPRECATION")
    fun send(data: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return
        if (!ready) return
        val type = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, data, type)
            } else {
                c.writeType = type
                c.value = data
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
        }
    }
}
