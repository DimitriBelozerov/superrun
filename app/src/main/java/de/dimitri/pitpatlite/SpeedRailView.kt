package de.dimitri.pitpatlite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Horizontal speed rail (pattern "Schiene"): drag the knob, tenths everywhere, snaps to full km/h.
 * On a snap the owner may [lock] the rail while the belt catches up; [unlock] resumes the drag if the
 * finger is still down.
 */
class SpeedRailView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        /** Live value while dragging (tenths). */
        fun onValueChanged(value: Double)
        /** Finger reached a full km/h. */
        fun onSnapped(full: Double)
        /** Finger lifted while not locked. */
        fun onReleased(value: Double)
    }

    var listener: Listener? = null
    var minKph = 0.5
        set(v) { field = v; value = value }
    var maxKph = 6.0
        set(v) { field = v; value = value }
    var value: Double = 2.0
        set(v) { field = (v * 10).roundToInt() / 10.0; field = field.coerceIn(minKph, maxKph); invalidate() }
    var locked = false
        private set
    val isDragging: Boolean get() = pressed && dragging

    private var pressed = false
    private var dragging = false
    private var downX = 0f
    private var lastX = 0f
    private var lastSnapFull = -1

    private val density = resources.displayMetrics.density
    private val padH = 24 * density
    private val knobW = 40 * density
    private val deadzone = 8 * density

    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorSurfaceVariant) }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorOutline); strokeWidth = 2 * density }
    private val fullTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant); strokeWidth = 2 * density }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant); textSize = 11 * density; textAlign = Paint.Align.CENTER
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val accent = themeColor(com.google.android.material.R.attr.colorPrimary)
    private val onAccent = themeColor(com.google.android.material.R.attr.colorOnPrimary)
    private val lockColor = context.getColor(R.color.lock)
    private val onLock = context.getColor(R.color.on_lock)
    private val rect = RectF()

    fun lock() {
        locked = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    fun unlock() {
        if (!locked) return
        locked = false
        if (pressed && dragging) applyX(lastX)
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                dragging = false
                downX = e.x
                lastX = e.x
                lastSnapFull = if (value % 1.0 == 0.0) value.toInt() else -1
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                if (!dragging && abs(e.x - downX) < deadzone) return true
                dragging = true
                if (!locked) applyX(e.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val wasDrag = dragging && !locked && e.actionMasked == MotionEvent.ACTION_UP
                pressed = false
                dragging = false
                if (wasDrag) listener?.onReleased(value)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun applyX(x: Float) {
        val span = width - 2 * padH
        if (span <= 0) return
        val raw = (minKph + ((x - padH) / span) * (maxKph - minKph)).coerceIn(minKph, maxKph)
        var v = (raw * 10).roundToInt() / 10.0
        val near = raw.roundToInt().toDouble()
        if (abs(raw - near) < SNAP_WINDOW && near >= minKph && near <= maxKph) v = near
        val full = if (v % 1.0 == 0.0) v.toInt() else -1
        if (v == value) return
        value = v
        listener?.onValueChanged(value)
        if (full >= 0 && full != lastSnapFull) {
            lastSnapFull = full
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            listener?.onSnapped(value)
        } else if (full < 0) {
            lastSnapFull = -1
        }
    }

    private fun xFor(kph: Double): Float = padH + ((kph - minKph) / (maxKph - minKph)).toFloat() * (width - 2 * padH)

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val railTop = 22 * density
        rect.set(0f, railTop, width.toFloat(), h)
        c.drawRoundRect(rect, 14 * density, 14 * density, railPaint)

        var k = Math.ceil(minKph * 2) / 2.0
        while (k <= maxKph + 1e-9) {
            val x = xFor(k)
            val full = k % 1.0 == 0.0
            val len = if (full) 20 * density else 10 * density
            c.drawLine(x, h - 8 * density, x, h - 8 * density - len, if (full) fullTickPaint else tickPaint)
            if (full) c.drawText(k.toInt().toString(), x, railTop - 7 * density, labelPaint)
            k += 0.5
        }

        val kx = xFor(value)
        knobPaint.color = if (locked) lockColor else accent
        knobTextPaint.color = if (locked) onLock else onAccent
        rect.set(kx - knobW / 2, railTop + 6 * density, kx + knobW / 2, h - 6 * density)
        c.drawRoundRect(rect, 10 * density, 10 * density, knobPaint)
        val text = if (value % 1.0 == 0.0) value.toInt().toString() else Fmt.kph(value)
        c.drawText(text, kx, rect.centerY() + knobTextPaint.textSize * 0.36f, knobTextPaint)
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }

    companion object {
        private const val SNAP_WINDOW = 0.15
    }
}
