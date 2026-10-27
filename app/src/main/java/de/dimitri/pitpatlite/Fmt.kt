package de.dimitri.pitpatlite

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** German number/time formatting, one place. */
object Fmt {
    private val de = Locale.GERMANY

    fun kph(v: Double): String = String.format(de, "%.1f", v)
    fun km2(v: Double): String = String.format(de, "%.2f km", v)
    fun km(v: Double): String = (if (v % 1.0 == 0.0) String.format(de, "%.0f", v) else String.format(de, "%.1f", v)) + " km"

    fun clock(seconds: Double): String {
        val s = seconds.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(de, "%d:%02d:%02d", h, m, sec) else String.format(de, "%d:%02d", m, sec)
    }

    fun minutes(seconds: Double): String {
        val m = (seconds / 60).toLong()
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    fun date(ms: Long): String = SimpleDateFormat("EEE, d. MMM HH:mm", de).format(Date(ms))
}
