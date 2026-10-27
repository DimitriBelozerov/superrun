package de.dimitri.pitpatlite

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Run(
    val startMs: Long,
    val durationS: Int,
    val distanceKm: Double,
    val steps: Long,
    val calories: Int,
    val goal: String? = null,
    val goalReached: Boolean = false,
) {
    val avgKph: Double get() = if (durationS > 0) distanceKm / (durationS / 3600.0) else 0.0

    fun toJson(): JSONObject = JSONObject()
        .put("start", startMs).put("duration", durationS).put("distance", distanceKm)
        .put("steps", steps).put("calories", calories).put("goalReached", goalReached)
        .apply { goal?.let { put("goal", it) } }

    companion object {
        fun fromJson(o: JSONObject) = Run(
            o.getLong("start"), o.getInt("duration"), o.getDouble("distance"), o.getLong("steps"), o.getInt("calories"),
            if (o.has("goal")) o.getString("goal") else null, o.optBoolean("goalReached", false),
        )
    }
}

/** Runs as a JSON array in one file; newest last. */
class RunStore(private val file: File) {
    fun load(): MutableList<Run> {
        if (!file.exists()) return mutableListOf()
        val arr = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return mutableListOf()
        return (0 until arr.length()).mapNotNull { runCatching { Run.fromJson(arr.getJSONObject(it)) }.getOrNull() }.toMutableList()
    }

    fun add(run: Run) = save(load().apply { add(run) })

    fun delete(startMs: Long) = save(load().apply { removeAll { it.startMs == startMs } })

    private fun save(runs: List<Run>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONArray().apply { runs.forEach { put(it.toJson()) } }.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
