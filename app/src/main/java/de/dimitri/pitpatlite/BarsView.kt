package de.dimitri.pitpatlite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/** Minimal bar chart: one bar per value, optional labels below, optional highlighted bar. */
class BarsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var values: FloatArray = FloatArray(0)
        set(v) { field = v; invalidate() }
    var labels: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var highlight = -1
        set(v) { field = v; invalidate() }
    /** Fixed scale; null = max of values. */
    var maxValue: Float? = null
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorPrimary) }
    private val barDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorPrimary); alpha = 110 }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = themeColor(com.google.android.material.R.attr.colorOutline); alpha = 90; strokeWidth = 1 * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant); textSize = 10 * d; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val n = values.size
        val w = width.toFloat()
        val labelH = if (labels.isEmpty()) 0f else 14 * d
        val chartH = height - labelH
        c.drawLine(0f, chartH, w, chartH, base)
        if (n == 0) return
        val gap = if (n > 60) 0f else minOf(6 * d, w / n * 0.3f)
        val bw = (w - gap * (n - 1)) / n
        val max = (maxValue ?: values.max()).takeIf { it > 0f } ?: 1f
        val radius = minOf(3 * d, bw / 2)
        for (i in 0 until n) {
            val h = (values[i] / max).coerceIn(0f, 1f) * (chartH - 2 * d)
            val x = i * (bw + gap)
            rect.set(x, chartH - h, x + bw, chartH)
            c.drawRoundRect(rect, radius, radius, if (highlight < 0 || i == highlight) bar else barDim)
            if (i < labels.size) c.drawText(labels[i], x + bw / 2, height - 3 * d, label)
        }
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }
}
