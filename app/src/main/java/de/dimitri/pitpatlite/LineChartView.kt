package de.dimitri.pitpatlite

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Smooth line chart with gradient fill. Few points (<= 14): dots + labels, optionally selectable by tap.
 * Many points: a plain curve with an end dot.
 */
class LineChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var values: FloatArray = FloatArray(0)
        set(v) { field = v; invalidate() }
    var labels: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    /** Second label row (e.g. month at a month start); empty strings draw nothing. */
    var subLabels: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var selected = -1
        set(v) { field = v; invalidate() }
    var selectable = false
    var onSelect: ((Int) -> Unit)? = null
    var valueText: (Float) -> String = { "%.1f".format(it) }
    var maxValue: Float? = null
        set(v) { field = v; invalidate() }
    var accent: Int = themeColor(com.google.android.material.R.attr.colorPrimary)
        set(v) { field = v; line.color = v; dotStroke.color = v; guide.color = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorOutline); alpha = 70; strokeWidth = 1 * d }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; alpha = 160; strokeWidth = 1.5f * d }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorSurface) }
    private val dotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant); textSize = 11 * d; textAlign = Paint.Align.CENTER }
    private val valueLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorOnSurface); textSize = 12 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private val area = Path()
    private var downX = 0f

    /** Dots + labels per point; default for short series, forced for scrollable day charts. */
    var dotsAlways = false
        set(v) { field = v; invalidate() }
    private val dotsVisible get() = dotsAlways || values.size <= 14
    private val padX get() = if (dotsVisible) 14 * d else 2 * d
    private val topPad get() = if (dotsVisible) 20 * d else 4 * d
    private val labelH get() = (if (labels.isEmpty()) 0f else 16 * d) + (if (subLabels.any { it.isNotEmpty() }) 13 * d else 0f)

    private fun x(i: Int): Float {
        val n = values.size
        return if (n <= 1) width / 2f else padX + i * (width - 2 * padX) / (n - 1)
    }

    private fun y(v: Float, max: Float): Float {
        val chartH = height - labelH
        return topPad + (1f - (v / max).coerceIn(0f, 1f)) * (chartH - topPad - 2 * d)
    }

    override fun onDraw(c: Canvas) {
        val n = values.size
        val baseY = height - labelH
        c.drawLine(0f, baseY, width.toFloat(), baseY, grid)
        if (n == 0) return
        val max = (maxValue ?: values.max()).takeIf { it > 0f } ?: 1f

        path.reset()
        path.moveTo(x(0), y(values[0], max))
        for (i in 0 until n - 1) {
            val x0 = x(i); val y0 = y(values[i], max)
            val x1 = x(i + 1); val y1 = y(values[i + 1], max)
            val cx = (x0 + x1) / 2
            path.cubicTo(cx, y0, cx, y1, x1, y1)
        }
        area.set(path)
        area.lineTo(x(n - 1), baseY)
        area.lineTo(x(0), baseY)
        area.close()
        fill.shader = LinearGradient(0f, topPad, 0f, baseY, (accent and 0xFFFFFF) or (0x66 shl 24), accent and 0xFFFFFF, Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        c.drawPath(path, line)

        if (dotsVisible) {
            for (i in 0 until n) {
                val px = x(i); val py = y(values[i], max)
                val r = if (i == selected) 6 * d else 4 * d
                if (i == selected) c.drawLine(px, py, px, baseY, guide)
                c.drawCircle(px, py, r, dotFill)
                c.drawCircle(px, py, r, dotStroke)
                val sub = subLabels.getOrNull(i).orEmpty()
                val subH = if (subLabels.any { it.isNotEmpty() }) 13 * d else 0f
                if (i < labels.size) c.drawText(labels[i], px, height - 3 * d - subH, label)
                if (sub.isNotEmpty()) c.drawText(sub, px, height - 3 * d, label)
                if (i == selected) c.drawText(valueText(values[i]), px, py - 10 * d, valueLabel)
            }
        } else {
            val px = x(n - 1); val py = y(values[n - 1], max)
            c.drawCircle(px, py, 3.5f * d, dotFill)
            c.drawCircle(px, py, 3.5f * d, dotStroke)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!selectable || values.isEmpty()) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; return true }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) > 12 * d) return true
                val n = values.size
                val step = if (n <= 1) width.toFloat() else (width - 2 * padX) / (n - 1)
                val i = ((e.x - padX) / step).roundToInt().coerceIn(0, n - 1)
                selected = if (selected == i) -1 else i
                performClick()
                onSelect?.invoke(selected)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }
}
