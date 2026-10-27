package de.dimitri.pitpatlite

import org.json.JSONArray
import org.json.JSONObject

/** A workout target: a duration or a distance, optionally with a fixed belt speed. */
data class Goal(val type: Type, val value: Double, val speedKph: Double? = null, val custom: Boolean = false) {
    enum class Type { TIME, DISTANCE }

    val label: String
        get() = when (type) {
            Type.TIME -> "${value.toInt()} min"
            Type.DISTANCE -> Fmt.km(value)
        } + (speedKph?.let { " · ${Fmt.kph(it)}" } ?: "")

    fun progress(durationS: Double, distanceKm: Double): Double = when (type) {
        Type.TIME -> durationS / (value * 60)
        Type.DISTANCE -> distanceKm / value
    }.coerceIn(0.0, 1.0)

    /** "12:30 von 30:00" / "1,20 von 2,00 km" */
    fun progressText(durationS: Double, distanceKm: Double): String = when (type) {
        Type.TIME -> "${Fmt.clock(durationS)} von ${Fmt.clock(value * 60)}"
        Type.DISTANCE -> "${Fmt.km2(distanceKm).removeSuffix(" km")} von ${Fmt.km2(value)}"
    }

    fun toJson(): JSONObject = JSONObject().put("type", type.name).put("value", value).apply { speedKph?.let { put("speed", it) } }

    companion object {
        val BUILTIN = listOf(
            Goal(Type.TIME, 20.0), Goal(Type.TIME, 30.0), Goal(Type.TIME, 45.0),
            Goal(Type.DISTANCE, 2.0), Goal(Type.DISTANCE, 5.0),
        )

        fun fromJson(o: JSONObject, custom: Boolean = true) =
            Goal(Type.valueOf(o.getString("type")), o.getDouble("value"), if (o.has("speed")) o.getDouble("speed") else null, custom)

        fun listFromJson(s: String?): List<Goal> {
            if (s.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(s) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull() }
        }

        fun listToJson(goals: List<Goal>): String = JSONArray().apply { goals.forEach { put(it.toJson()) } }.toString()
    }
}
