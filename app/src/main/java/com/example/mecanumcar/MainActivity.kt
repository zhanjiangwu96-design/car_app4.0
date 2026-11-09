package com.example.mecanumcar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * 麦轮小车蓝牙遥控 (KT6368A BLE)
 * 每 50ms 发送一帧 8 字节: DD 77 X Y A B C D
 *   X: 左 -100(0x9C) .. 右 +100(0x64)    Y: 下 -100(0x9C) .. 上 +100(0x64)
 *   A/B/C/D: 按下 1 / 松开 0;  A 字节的 bit1 (值 2) = 巡线开 (App 的"巡线"按键)
 */
class MainActivity : Activity() {

    companion object {
        const val TARGET_NAME = "w"          // 小车蓝牙名称
        const val SEND_INTERVAL_MS = 50L
        const val REQ_PERM = 1
    }

    private lateinit var car: BleCar
    private lateinit var control: ControlView
    private lateinit var statusTv: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (!car.ready && control.lineOn) control.setLineOn(false)   // 断开后巡线键自动复位
            car.send(buildFrame())
            handler.postDelayed(this, SEND_INTERVAL_MS)
        }
    }

    private fun buildFrame(): ByteArray {
        val p = control.pressed
        return byteArrayOf(
            0xDD.toByte(), 0x77.toByte(),
            control.joyX.toByte(), control.joyY.toByte(),
            ((if (p[0]) 1 else 0) or (if (control.lineOn) 2 else 0)).toByte(), (if (p[1]) 1 else 0).toByte(),
            (if (p[2]) 1 else 0).toByte(), (if (p[3]) 1 else 0).toByte()
        )
    }

    private fun zeroFrame(): ByteArray =
        byteArrayOf(0xDD.toByte(), 0x77.toByte(), 0, 0, 0, 0, 0, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        car = BleCar(this, TARGET_NAME,
            onStatus = { statusTv.text = it },
            onNeedPick = { pickDevice(it) })

        control = ControlView(this)
        val root = FrameLayout(this)
        root.addView(control, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(24, 16, 24, 16)

        val btnSearch = Button(this)
        btnSearch.text = "搜索"
        btnSearch.setOnClickListener { startSearch() }
        val btnConnect = Button(this)
        btnConnect.text = "连接"
        btnConnect.setOnClickListener { startConnect() }
        val btnDisc = Button(this)
        btnDisc.text = "断开"
        btnDisc.setOnClickListener { car.disconnect() }
        statusTv = TextView(this)
        statusTv.setTextColor(Color.WHITE)
        statusTv.textSize = 14f
        statusTv.text = "未连接"
        statusTv.setPadding(24, 0, 0, 0)

        bar.addView(btnSearch)
        bar.addView(btnConnect)
        bar.addView(btnDisc)
        bar.addView(statusTv)
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START))

        setContentView(root)
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        car.send(zeroFrame())          // 离开界面先发停车帧, 再断开 (小车 0.5s 收不到帧也会自动停)
        car.disconnect()
    }

    // ---------------- 权限 / 连接 ----------------
    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private var pendingAction = 0          // 0 = 连接(自动找名为 w 的设备), 1 = 搜索

    private fun missingPermissions() = neededPermissions().filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun startConnect() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            pendingAction = 0
            requestPermissions(missing.toTypedArray(), REQ_PERM)
            return
        }
        car.startScan()
    }

    private fun startSearch() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            pendingAction = 1
            requestPermissions(missing.toTypedArray(), REQ_PERM)
            return
        }
        showSearchDialog()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (pendingAction == 1) showSearchDialog() else car.startScan()
            } else {
                Toast.makeText(this, "需要蓝牙权限才能连接小车", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 搜索蓝牙设备: 实时列出附近设备, 输入名称可筛选 (不区分大小写, 包含即可), 点一项就连接 */
    private fun showSearchDialog() {
        val all = ArrayList<FoundDevice>()
        val shown = ArrayList<FoundDevice>()
        val rows = ArrayList<String>()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)

        val et = EditText(this)
        et.hint = "输入名称筛选 (留空显示全部)"
        et.setSingleLine(true)
        val tv = TextView(this)
        tv.textSize = 12f
        tv.setPadding(0, dp(6), 0, dp(6))
        tv.text = "正在搜索 ..."
        val lv = ListView(this)
        lv.adapter = adapter

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))

        var scanning = true
        fun refresh() {
            val key = et.text.toString().trim().lowercase()
            shown.clear()
            rows.clear()
            for (d in all) {
                if (key.isEmpty() || d.name.lowercase().contains(key)) {
                    shown.add(d)
                    rows.add((if (d.name.isEmpty()) "未命名" else d.name) + "    " + d.rssi + " dBm\n" + d.device.address)
                }
            }
            adapter.notifyDataSetChanged()
            tv.text = (if (scanning) "正在搜索 ...  " else "搜索结束  ") + "共 " + all.size + " 个, 显示 " + shown.size + " 个"
        }

        fun rescan() {
            scanning = true
            all.clear()
            refresh()
            car.startListScan(
                { list ->
                    all.clear()
                    all.addAll(list)
                    refresh()
                },
                {
                    scanning = false
                    refresh()
                }
            )
        }

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refresh()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("搜索蓝牙设备")
            .setView(box)
            .setPositiveButton("重新扫描", null)
            .setNegativeButton("关闭", null)
            .create()

        lv.setOnItemClickListener { _, _, pos, _ ->
            if (pos < shown.size) {
                val d = shown[pos].device
                dialog.dismiss()
                car.connect(d)
            }
        }
        dialog.setOnDismissListener { car.stopListScan() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { rescan() }
        rescan()
    }

    private fun pickDevice(list: List<BluetoothDevice>) {
        val items = list.map { "${car.nameOf(it)}   ${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("没找到名为 \"$TARGET_NAME\" 的设备, 请手动选择")
            .setItems(items) { _, i -> car.connect(list[i]) }
            .setNegativeButton("取消", null)
            .show()
    }
}
