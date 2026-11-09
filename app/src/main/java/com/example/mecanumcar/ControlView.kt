package com.example.mecanumcar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** 左: 摇杆 (X/Y = -100..100, 右/上为正)   右: A B C D 四个按键。支持多点触控。 */
class ControlView(ctx: Context) : View(ctx) {

    var joyX = 0
        private set
    var joyY = 0
        private set
    val pressed = BooleanArray(4)           // A B C D

    private var joyId = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val labels = arrayOf("A", "B", "C", "D")
    private val hints = arrayOf("左转", "右转", "加速", "减速")
    private val bx = floatArrayOf(0.90f, 0.77f, 0.80f, 0.91f)
    private val by = floatArrayOf(0.72f, 0.82f, 0.56f, 0.38f)
    private val br = floatArrayOf(0.10f, 0.085f, 0.085f, 0.085f)   // 相对高度

    private fun joyCx() = width * 0.22f
    private fun joyCy() = height * 0.62f
    private fun joyR() = min(width, height) * 0.30f

    private fun updateJoy(x: Float, y: Float) {
        val r = joyR()
        var dx = (x - joyCx()) / r
        var dy = (y - joyCy()) / r
        val d = hypot(dx, dy)
        if (d > 1f) {
            dx /= d
            dy /= d
        }
        joyX = (dx * 100f).roundToInt()
        joyY = (-dy * 100f).roundToInt()
    }

    private fun hit(b: Int, x: Float, y: Float): Boolean {
        val cx = bx[b] * width
        val cy = by[b] * height
        return hypot(x - cx, y - cy) <= br[b] * height * 1.15f
    }

    private fun resetAll() {
        joyId = -1
        joyX = 0
        joyY = 0
        for (i in 0..3) pressed[i] = false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val act = e.actionMasked
        if (act == MotionEvent.ACTION_CANCEL || act == MotionEvent.ACTION_UP) {
            resetAll()
            invalidate()
            return true
        }
        val upIdx = if (act == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        val newBtn = BooleanArray(4)
        var joyFound = false

        for (i in 0 until e.pointerCount) {
            if (i == upIdx) continue
            val id = e.getPointerId(i)
            val x = e.getX(i)
            val y = e.getY(i)
            if (id == joyId) {
                updateJoy(x, y)
                joyFound = true
                continue
            }
            if (joyId == -1 && x < width * 0.5f) {
                joyId = id
                updateJoy(x, y)
                joyFound = true
                continue
            }
            for (b in 0..3) if (hit(b, x, y)) newBtn[b] = true
        }
        if (!joyFound) {
            joyId = -1
            joyX = 0
            joyY = 0
        }
        for (b in 0..3) pressed[b] = newBtn[b]
        invalidate()
        return true
    }

    private fun drawCenteredText(c: Canvas, s: String, cx: Float, cy: Float) {
        c.drawText(s, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF0B1B2E.toInt())
        paint.textAlign = Paint.Align.CENTER

        // 摇杆底盘
        val cx = joyCx()
        val cy = joyCy()
        val r = joyR()
        paint.style = Paint.Style.FILL
        paint.color = 0x22FFFFFF
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = 0x88FFFFFF.toInt()
        c.drawCircle(cx, cy, r, paint)

        // 摇杆头
        val kx = cx + joyX / 100f * r
        val ky = cy - joyY / 100f * r
        paint.style = Paint.Style.FILL
        paint.color = if (joyId != -1) 0xFF4FC3F7.toInt() else 0xFF2A6F97.toInt()
        c.drawCircle(kx, ky, r * 0.32f, paint)

        // 四个按键
        for (b in 0..3) {
            val bxp = bx[b] * width
            val byp = by[b] * height
            val rad = br[b] * height
            paint.style = Paint.Style.FILL
            paint.color = if (pressed[b]) 0xFF4FC3F7.toInt() else 0x44FFFFFF
            c.drawCircle(bxp, byp, rad, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = 0x99FFFFFF.toInt()
            c.drawCircle(bxp, byp, rad, paint)

            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            paint.textSize = rad * 0.8f
            drawCenteredText(c, labels[b], bxp, byp - rad * 0.12f)
            paint.textSize = rad * 0.30f
            paint.color = 0xCCFFFFFF.toInt()
            drawCenteredText(c, hints[b], bxp, byp + rad * 0.55f)
        }

        // 调试信息
        paint.color = 0xFFB0BEC5.toInt()
        paint.textSize = height * 0.045f
        drawCenteredText(c, "X=$joyX  Y=$joyY", width * 0.5f, height * 0.94f)
    }
}
