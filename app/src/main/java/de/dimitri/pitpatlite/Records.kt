package de.dimitri.pitpatlite

/** Personal bests over the run history. */
object Records {
    enum class Kind(val label: String) { DISTANCE("Strecke"), DURATION("Zeit"), AVG("Schnitt"), STEPS("Schritte") }

    private fun value(kind: Kind, r: Run): Double = when (kind) {
        Kind.DISTANCE -> r.distanceKm
        Kind.DURATION -> r.durationS.toDouble()
        Kind.AVG -> r.avgKph
        Kind.STEPS -> r.steps.toDouble()
    }

    /** Overall best run per kind. */
    fun bests(runs: List<Run>): Map<Kind, Run> =
        Kind.entries.mapNotNull { k -> runs.filter { value(k, it) > 0 }.maxByOrNull { value(k, it) }?.let { k to it } }.toMap()

    /** For each run (by startMs): the kinds where it beat every earlier run. The first run sets no records. */
    fun badges(runs: List<Run>): Map<Long, List<Kind>> {
        val best = mutableMapOf<Kind, Double>()
        val out = mutableMapOf<Long, List<Kind>>()
        runs.sortedBy { it.startMs }.forEachIndexed { i, r ->
            val won = Kind.entries.filter { k ->
                val v = value(k, r)
                val beat = v > 0 && v > (best[k] ?: 0.0)
                if (v > (best[k] ?: 0.0)) best[k] = v
                beat && i > 0
            }
            if (won.isNotEmpty()) out[r.startMs] = won
        }
        return out
    }
}
