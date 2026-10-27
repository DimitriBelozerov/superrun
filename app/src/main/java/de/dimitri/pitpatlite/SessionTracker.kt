package de.dimitri.pitpatlite

import de.dimitri.pitpatlite.PitPatProtocol.BeltState
import de.dimitri.pitpatlite.PitPatProtocol.Status

/**
 * Turns the treadmill status stream into workout sessions.
 * A session starts when the belt starts moving and ends on STOPPED. Counters are taken relative to the
 * first moving packet; if the treadmill resets its counters at start, the smaller values win (absolute).
 */
class SessionTracker(private val now: () -> Long = System::currentTimeMillis) {

    sealed class Event {
        object Started : Event()
        /** Goal is [WARN_SECONDS] s / [WARN_KM] km away; fired once. */
        object GoalNear : Event()
        object GoalReached : Event()
        data class Finished(val run: Run) : Event()
    }

    data class Progress(val durationS: Double, val distanceKm: Double, val steps: Long, val calories: Int)

    var goal: Goal? = null

    val active: Boolean get() = startMs != null
    var progress: Progress = Progress(0.0, 0.0, 0, 0)
        private set

    private var startMs: Long? = null
    private var base: Status? = null
    private var peak: Progress = Progress(0.0, 0.0, 0, 0)
    private var goalFired = false
    private var nearFired = false
    private val samples = mutableListOf<Float>()
    private var nextSampleAt = 0.0

    fun onStatus(s: Status): List<Event> {
        val events = mutableListOf<Event>()
        val moving = s.state == BeltState.RUNNING || s.state == BeltState.STARTING

        if (startMs == null && moving) {
            startMs = now()
            base = s
            peak = Progress(0.0, 0.0, 0, 0)
            goalFired = false
            nearFired = false
            samples.clear()
            nextSampleAt = 0.0
            events += Event.Started
        }
        val start = startMs ?: return events

        progress = relative(s, base!!)
        if (progress.durationS >= peak.durationS) peak = progress
        if (progress.durationS >= nextSampleAt) {
            samples += s.currentKph.toFloat()
            nextSampleAt = progress.durationS + Run.SAMPLE_SECONDS
        }

        val g = goal
        if (g != null && s.state == BeltState.RUNNING) {
            val near = when (g.type) {
                Goal.Type.TIME -> g.value * 60 - progress.durationS <= WARN_SECONDS
                Goal.Type.DISTANCE -> g.value - progress.distanceKm <= WARN_KM
            }
            if (near && !nearFired && !goalFired) {
                nearFired = true
                events += Event.GoalNear
            }
            if (!goalFired && g.progress(progress.durationS, progress.distanceKm) >= 1.0) {
                goalFired = true
                events += Event.GoalReached
            }
        }

        if (s.state == BeltState.STOPPED) {
            startMs = null
            if (peak.durationS >= MIN_RUN_SECONDS) {
                events += Event.Finished(
                    Run(start, peak.durationS.toInt(), peak.distanceKm, peak.steps, peak.calories, g?.label, goalFired, samples.toList())
                )
            }
        }
        return events
    }

    private fun relative(s: Status, b: Status) = Progress(
        durationS = rel(s.durationS, b.durationS),
        distanceKm = rel(s.distanceKm, b.distanceKm),
        steps = if (s.steps < b.steps) s.steps else s.steps - b.steps,
        calories = if (s.calories < b.calories) s.calories else s.calories - b.calories,
    )

    private fun rel(v: Double, b: Double) = if (v < b) v else v - b

    companion object {
        const val MIN_RUN_SECONDS = 10.0
        const val WARN_SECONDS = 30.0
        const val WARN_KM = 0.05
    }
}
