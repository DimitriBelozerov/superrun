package de.dimitri.pitpatlite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/** Goal ring: a track and an arc from 12 o'clock. Progress 0..1. */
class ProgressRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    /** false = only the dim track (no goal). */
    var active = true
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val stroke = 12 * d
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = themeColor(com.google.android.material.R.attr.colorSurfaceVariant); style = Paint.Style.STROKE; strokeWidth = stroke
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.goal); style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val inset = stroke / 2 + 1 * d
        rect.set(inset, inset, width - inset, height - inset)
        c.drawArc(rect, 0f, 360f, false, track)
        if (active && progress > 0f) c.drawArc(rect, -90f, 360f * progress, false, arc)
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }
}
