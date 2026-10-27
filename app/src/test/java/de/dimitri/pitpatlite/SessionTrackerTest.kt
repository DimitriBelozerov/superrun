package de.dimitri.pitpatlite

import de.dimitri.pitpatlite.PitPatProtocol.BeltState
import de.dimitri.pitpatlite.PitPatProtocol.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionTrackerTest {

    private fun status(state: BeltState, durationS: Double, km: Double, steps: Long = 0, kcal: Int = 0) = Status(
        state = state, currentKph = 3.0, targetKph = 3.0, maxKph = 6.0, distanceKm = km, incline = 0, targetIncline = 0,
        maxIncline = 0, heartRate = 0, steps = steps, calories = kcal, durationS = durationS, firmware = 37, metric = true,
        buzzerOn = null, checksumOk = true, raw = ByteArray(0),
    )

    @Test
    fun sessionFromStartToStopIsSavedWithPeakValues() {
        val t = SessionTracker(now = { 1000L })
        assertEquals(listOf(SessionTracker.Event.Started), t.onStatus(status(BeltState.STARTING, 0.0, 0.0)))
        assertTrue(t.onStatus(status(BeltState.RUNNING, 30.0, 0.05, 40, 2)).isEmpty())
        assertTrue(t.onStatus(status(BeltState.PAUSED, 30.0, 0.05, 40, 2)).isEmpty())
        assertTrue(t.onStatus(status(BeltState.RUNNING, 600.0, 0.5, 700, 25)).isEmpty())
        val events = t.onStatus(status(BeltState.STOPPED, 0.0, 0.0))
        val run = (events.single() as SessionTracker.Event.Finished).run
        assertEquals(Run(1000L, 600, 0.5, 700, 25), run.copy(samples = emptyList()))
        // one sample at 0 s, 30 s and 600 s (packets are sparse in this test)
        assertEquals(listOf(3.0f, 3.0f, 3.0f), run.samples)
    }

    @Test
    fun samplesEveryFiveSeconds() {
        val t = SessionTracker()
        t.onStatus(status(BeltState.RUNNING, 0.0, 0.0))
        for (sec in 1..20) t.onStatus(status(BeltState.RUNNING, sec.toDouble(), 0.0))
        val run = (t.onStatus(status(BeltState.STOPPED, 20.0, 0.1)).single() as SessionTracker.Event.Finished).run
        assertEquals(5, run.samples.size)
    }

    @Test
    fun recordsBadgesAndBests() {
        val runs = listOf(
            Run(1L, 600, 1.0, 1000, 30),
            Run(2L, 500, 1.5, 900, 40),   // distance record, avg record
            Run(3L, 700, 1.2, 1200, 50),  // duration + steps record
            Run(4L, 100, 0.1, 10, 1),
        )
        val badges = Records.badges(runs)
        assertEquals(null, badges[1L])
        assertEquals(listOf(Records.Kind.DISTANCE, Records.Kind.AVG), badges[2L])
        assertEquals(listOf(Records.Kind.DURATION, Records.Kind.STEPS), badges[3L])
        assertEquals(null, badges[4L])
        val bests = Records.bests(runs)
        assertEquals(2L, bests[Records.Kind.DISTANCE]?.startMs)
        assertEquals(3L, bests[Records.Kind.DURATION]?.startMs)
    }

    @Test
    fun runJsonKeepsSamples() {
        val r = Run(5L, 60, 0.1, 100, 5, samples = listOf(1.5f, 2.0f))
        assertEquals(r, Run.fromJson(r.toJson()))
    }

    @Test
    fun countersThatDoNotResetAreTakenRelativeToStart() {
        val t = SessionTracker()
        t.onStatus(status(BeltState.RUNNING, 1000.0, 2.0, 2000, 80))
        t.onStatus(status(BeltState.RUNNING, 1100.0, 2.1, 2150, 85))
        val run = (t.onStatus(status(BeltState.STOPPED, 1100.0, 2.1, 2150, 85)).single() as SessionTracker.Event.Finished).run
        assertEquals(100, run.durationS)
        assertEquals(0.1, run.distanceKm, 1e-9)
        assertEquals(150L, run.steps)
        assertEquals(5, run.calories)
    }

    @Test
    fun shortSessionsAreDropped() {
        val t = SessionTracker()
        t.onStatus(status(BeltState.RUNNING, 0.0, 0.0))
        t.onStatus(status(BeltState.RUNNING, 5.0, 0.0))
        assertTrue(t.onStatus(status(BeltState.STOPPED, 5.0, 0.0)).isEmpty())
    }

    @Test
    fun goalFiresOnceWhenReached() {
        val t = SessionTracker()
        t.goal = Goal(Goal.Type.DISTANCE, 1.0)
        t.onStatus(status(BeltState.RUNNING, 0.0, 0.0))
        assertTrue(t.onStatus(status(BeltState.RUNNING, 100.0, 0.5)).isEmpty())
        assertEquals(listOf(SessionTracker.Event.GoalNear), t.onStatus(status(BeltState.RUNNING, 190.0, 0.96)))
        assertTrue(t.onStatus(status(BeltState.RUNNING, 195.0, 0.98)).isEmpty())
        assertEquals(listOf(SessionTracker.Event.GoalReached), t.onStatus(status(BeltState.RUNNING, 200.0, 1.0)))
        assertTrue(t.onStatus(status(BeltState.RUNNING, 201.0, 1.01)).isEmpty())
        val run = (t.onStatus(status(BeltState.STOPPED, 0.0, 0.0)).single() as SessionTracker.Event.Finished).run
        assertEquals("1 km", run.goal)
        assertTrue(run.goalReached)
    }

    @Test
    fun timeGoalWarnsThirtySecondsBefore() {
        val t = SessionTracker()
        t.goal = Goal(Goal.Type.TIME, 1.0)
        t.onStatus(status(BeltState.RUNNING, 0.0, 0.0))
        assertTrue(t.onStatus(status(BeltState.RUNNING, 29.0, 0.0)).isEmpty())
        assertEquals(listOf(SessionTracker.Event.GoalNear), t.onStatus(status(BeltState.RUNNING, 30.0, 0.0)))
        assertEquals(listOf(SessionTracker.Event.GoalReached), t.onStatus(status(BeltState.RUNNING, 60.0, 0.0)))
    }

    @Test
    fun runStoreRoundTrip() {
        val f = File.createTempFile("runs", ".json")
        val store = RunStore(f)
        store.add(Run(1L, 600, 0.5, 700, 25, "30 min", true))
        store.add(Run(2L, 60, 0.1, 100, 5))
        assertEquals(2, store.load().size)
        store.delete(1L)
        assertEquals(listOf(Run(2L, 60, 0.1, 100, 5)), store.load())
        f.delete()
    }

    @Test
    fun goalJsonRoundTripAndLabels() {
        val goals = listOf(Goal(Goal.Type.TIME, 30.0, 4.5, custom = true), Goal(Goal.Type.DISTANCE, 2.5, custom = true))
        assertEquals(goals, Goal.listFromJson(Goal.listToJson(goals)))
        assertEquals("30 min · 4,5", goals[0].label)
        assertEquals("2,5 km", goals[1].label)
        assertEquals(0.5, goals[0].progress(900.0, 0.0), 1e-9)
        assertEquals("15:00 von 30:00", goals[0].progressText(900.0, 0.0))
    }
}
