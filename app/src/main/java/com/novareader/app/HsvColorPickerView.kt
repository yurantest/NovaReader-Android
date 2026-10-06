package com.novareader.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Полноценный HSV-микшер цвета — hue-полоса сверху + saturation/value
 * квадрат снизу, тот же принцип, что QColorDialog на десктопе, а не
 * фиксированный набор свотчей.
 */
class HsvColorPickerView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var hue = 0f
    var sat = 0f
    var value = 1f
        private set

    var onColorChanged: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val hueBarHeight = 32 * density
    private val gap = 12 * density

    private var svBitmap: Bitmap? = null
    private val huePaint = Paint()
    private val cursorPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        isAntiAlias = true
    }

    fun setColorHex(hex: String) {
        try {
            val c = Color.parseColor(hex)
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hue = hsv[0]
            sat = hsv[1]
            value = hsv[2]
            svBitmap = null
            invalidate()
        } catch (_: IllegalArgumentException) {
        }
    }

    fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))
    fun currentHex(): String = String.format("#%06X", 0xFFFFFF and currentColor())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        svBitmap = null
    }

    private fun svRectTop() = hueBarHeight + gap
    private fun svSize() = (height - svRectTop()).coerceAtLeast(1f)

    private fun buildSvBitmap(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size.coerceAtLeast(1), size.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        for (x in 0 until bmp.width) {
            val s = x / bmp.width.toFloat()
            val colors = intArrayOf(
                Color.HSVToColor(floatArrayOf(hue, s, 1f)),
                Color.HSVToColor(floatArrayOf(hue, s, 0f))
            )
            paint.shader = LinearGradient(0f, 0f, 0f, bmp.height.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
            canvas.drawRect(x.toFloat(), 0f, x + 1f, bmp.height.toFloat(), paint)
        }
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f || height <= 0f) return

        val hueColors = IntArray(361) { i -> Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f)) }
        huePaint.shader = LinearGradient(0f, 0f, w, 0f, hueColors, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, hueBarHeight, huePaint)

        val hueX = (hue / 360f) * w
        canvas.drawCircle(hueX, hueBarHeight / 2f, hueBarHeight / 2f - 3 * density, cursorPaint)

        val svTop = svRectTop()
        val size = w.toInt()
        var bmp = svBitmap
        if (bmp == null || bmp.width != size) {
            bmp = buildSvBitmap(size)
            svBitmap = bmp
        }
        canvas.drawBitmap(bmp, 0f, svTop, null)

        val cx = sat * w
        val cy = svTop + (1f - value) * svSize()
        canvas.drawCircle(cx, cy, 8 * density, cursorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        if (w <= 0f) return true
        when {
            event.y <= hueBarHeight -> {
                hue = (event.x / w * 360f).coerceIn(0f, 360f)
                svBitmap = null
            }
            else -> {
                val svTop = svRectTop()
                sat = ((event.x) / w).coerceIn(0f, 1f)
                value = (1f - (event.y - svTop) / svSize()).coerceIn(0f, 1f)
            }
        }
        invalidate()
        onColorChanged?.invoke(currentColor())
        return true
    }
}
