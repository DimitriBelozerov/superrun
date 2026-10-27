package de.dimitri.pitpatlite

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.chip.Chip
import de.dimitri.pitpatlite.PitPatProtocol.BeltState
import de.dimitri.pitpatlite.databinding.ActivityMainBinding
import de.dimitri.pitpatlite.databinding.DialogGoalBinding
import java.io.File
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), TreadmillBle.Listener, SpeedRailView.Listener {

    private lateinit var b: ActivityMainBinding
    private val ble: TreadmillBle get() = (application as App).ble
    private val prefs by lazy { getSharedPreferences("pitpat", MODE_PRIVATE) }
    private val runStore by lazy { RunStore(File(filesDir, "runs.json")) }
    private val tracker = SessionTracker()
    private val main = Handler(Looper.getMainLooper())

    private var targetKph = 2.0
    private var targetIncline = 0
    private var maxKph = DEFAULT_MAX_KPH
    private var maxIncline = 0
    private var last: PitPatProtocol.Status? = null
    private var customGoals: List<Goal> = emptyList()
    private var selectedGoal: Goal? = null
    private var permissionAsked = false

    private val rails get() = listOf(b.railPrepare, b.rail)
    private val activeRail: SpeedRailView get() = if (b.screenRun.visibility == View.VISIBLE) b.rail else b.railPrepare

    // Rail lock: belt catching up after a full-km/h snap.
    private var lockTarget = 0.0
    private var lockStartedAt = 0L
    private val lockTick = object : Runnable {
        override fun run() {
            if (!b.rail.locked) return
            val elapsed = SystemClock.elapsedRealtime() - lockStartedAt
            val caughtUp = last?.let { abs(it.currentKph - lockTarget) <= LOCK_TOLERANCE } == true
            if (caughtUp || elapsed >= LOCK_MAX_MS || !isMoving()) {
                endLock()
            } else {
                renderLockDots(elapsed)
                main.postDelayed(this, LOCK_TICK_MS)
            }
        }
    }

    private var inclineSendPending = false
    private val sendInclineRunnable = Runnable {
        inclineSendPending = false
        if (isMoving()) ble.send(PitPatProtocol.setSpeed(targetKph, targetIncline))
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) startConnect() else b.tvConnection.text = getString(R.string.perm_denied)
    }
    private val enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (ble.isBluetoothOn) startConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        targetKph = prefs.getFloat("target", 2.0f).toDouble()
        customGoals = Goal.listFromJson(prefs.getString("goals", null))
        selectedGoal = prefs.getString("goal", null)?.let { s -> runCatching { Goal.fromJson(org.json.JSONObject(s), custom = false) }.getOrNull() }
        tracker.goal = selectedGoal

        rails.forEach { r ->
            r.listener = this
            r.minKph = MIN_KPH
            r.maxKph = maxKph
            r.value = targetKph
        }

        b.tvConnection.setOnClickListener { if (ble.state == TreadmillBle.ConnState.DISCONNECTED) ensurePermissionsThenConnect() }
        b.tvConnection.setOnLongClickListener {
            b.tvDebug.visibility = if (b.tvDebug.visibility == View.VISIBLE) View.GONE else View.VISIBLE; true
        }
        b.btnInclineMinus.setOnClickListener { adjustIncline(-1) }
        b.btnInclinePlus.setOnClickListener { adjustIncline(1) }
        b.btnStart.setOnClickListener { ble.send(PitPatProtocol.start(targetKph, targetIncline)) }
        b.btnPause.setOnClickListener { ble.send(PitPatProtocol.pause()) }
        b.btnStop.setOnClickListener { ble.send(PitPatProtocol.stop()) }
        val openHistory = View.OnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        b.btnHistory.setOnClickListener(openHistory)
        b.cardWeek.setOnClickListener(openHistory)

        buildGoalChips()
        renderTarget()
        renderIncline()
        renderGoal()
        renderStatus(null)
    }

    override fun onStart() {
        super.onStart()
        ble.listener = this
        if (ble.state == TreadmillBle.ConnState.DISCONNECTED) ensurePermissionsThenConnect(auto = true)
    }

    override fun onResume() {
        super.onResume()
        renderWeek()
    }

    override fun onStop() {
        super.onStop()
        if (ble.listener === this) ble.listener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        if (isFinishing) ble.disconnect()
    }

    // --- connection ---

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermissionsThenConnect(auto: Boolean = false) {
        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        when {
            missing.isEmpty() -> startConnect()
            auto && permissionAsked -> b.tvConnection.text = getString(R.string.perm_denied)
            else -> { permissionAsked = true; permissionLauncher.launch(missing.toTypedArray()) }
        }
    }

    private fun startConnect() {
        if (!ble.isBluetoothOn) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        ble.connect(prefs.getString("address", null))
    }

    override fun onConnectionChanged(state: TreadmillBle.ConnState, message: String?) {
        val connected = state == TreadmillBle.ConnState.CONNECTED
        b.tvConnection.text = message ?: when (state) {
            TreadmillBle.ConnState.DISCONNECTED -> getString(R.string.state_disconnected)
            TreadmillBle.ConnState.SCANNING -> getString(R.string.state_scanning)
            TreadmillBle.ConnState.CONNECTING -> getString(R.string.state_connecting)
            TreadmillBle.ConnState.CONNECTED -> getString(R.string.state_dot_connected)
        }
        b.tvConnection.setTextColor(if (connected) themeColor(com.google.android.material.R.attr.colorPrimary) else themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        if (connected) {
            ble.address?.let { prefs.edit().putString("address", it).apply() }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (state == TreadmillBle.ConnState.DISCONNECTED && message == getString(R.string.not_found)) {
                prefs.edit().remove("address").apply()
            }
            last = null
            endLock()
            renderStatus(null)
        }
        b.btnStart.isEnabled = connected
    }

    // --- status ---

    override fun onStatus(status: PitPatProtocol.Status) {
        last = status
        if (status.maxKph > 0.5 && status.maxKph != maxKph) {
            maxKph = status.maxKph
            rails.forEach { it.maxKph = maxKph }
        }
        maxIncline = status.maxIncline
        if (!b.rail.isDragging && !b.rail.locked) {
            if (status.state == BeltState.RUNNING && status.targetKph > 0 && abs(status.targetKph - targetKph) >= 0.05) {
                targetKph = status.targetKph
                rails.forEach { it.value = targetKph }
                renderTarget()
            }
            if (!inclineSendPending) {
                targetIncline = status.targetIncline.coerceIn(0, maxIncline)
                renderIncline()
            }
        }
        for (event in tracker.onStatus(status)) {
            when (event) {
                SessionTracker.Event.Started -> Unit
                SessionTracker.Event.GoalReached -> {
                    vibrate()
                    ble.send(PitPatProtocol.stop())
                    toast(getString(R.string.goal_reached))
                }
                is SessionTracker.Event.Finished -> {
                    runStore.add(event.run)
                    renderWeek()
                    toast(getString(R.string.run_saved, Fmt.km2(event.run.distanceKm), Fmt.clock(event.run.durationS.toDouble())))
                }
            }
        }
        renderStatus(status)
    }

    private fun renderStatus(s: PitPatProtocol.Status?) {
        val running = s != null && s.state != BeltState.STOPPED && s.state != BeltState.UNKNOWN
        if (running != (b.screenRun.visibility == View.VISIBLE)) {
            b.screenRun.visibility = if (running) View.VISIBLE else View.GONE
            b.screenPrepare.visibility = if (running) View.GONE else View.VISIBLE
            rails.forEach { it.value = targetKph }
        }
        renderBigSpeed()
        val stateText = when (s?.state) {
            BeltState.RUNNING -> getString(R.string.belt_running)
            BeltState.PAUSED -> getString(R.string.belt_paused)
            BeltState.STARTING -> getString(R.string.belt_starting)
            else -> getString(R.string.belt_stopped)
        } + if (s?.checksumOk == false) " ⚠" else ""
        b.tvBeltState.text = getString(R.string.unit_state, stateText)
        val p = tracker.progress
        b.tvRunStats.text = getString(R.string.run_stats, Fmt.km2(p.distanceKm), p.steps.toInt(), p.calories)
        b.rowIncline.visibility = if (maxIncline > 0) View.VISIBLE else View.GONE
        b.tvIncline.text = if (s != null && s.incline != targetIncline) "${s.incline}→$targetIncline" else "$targetIncline"
        b.tvDebug.text = s?.let { "fw ${it.firmware}  max ${Fmt.kph(it.maxKph)}  ${PitPatProtocol.toHex(it.raw)}" } ?: ""
        renderGoalProgress()
    }

    /** Big number: belt speed; while dragging or locked the target, in lock color. */
    private fun renderBigSpeed() {
        val handling = b.rail.isDragging || b.rail.locked
        b.tvSpeed.text = Fmt.kph(if (handling) targetKph else last?.currentKph ?: 0.0)
        b.tvSpeed.setTextColor(if (handling) getColor(R.color.lock) else b.tvBeltState.currentTextColor)
    }

    private fun renderWeek() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            val back = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            add(Calendar.DAY_OF_MONTH, -back)
        }
        val runs = runStore.load()
        val week = runs.filter { it.startMs >= cal.timeInMillis }
        b.tvWeek.text = if (week.isEmpty()) getString(R.string.week_empty) else resources.getQuantityString(
            R.plurals.week_summary, week.size, week.size, Fmt.km2(week.sumOf { it.distanceKm }), Fmt.minutes(week.sumOf { it.durationS }.toDouble())
        )
        b.btnHistory.text = if (runs.isEmpty()) getString(R.string.history) else getString(R.string.history_count, runs.size)
    }

    // --- rail ---

    private fun isMoving(): Boolean = last?.state == BeltState.RUNNING || last?.state == BeltState.STARTING

    override fun onValueChanged(value: Double) {
        targetKph = value
        renderTarget()
        renderBigSpeed()
    }

    override fun onSnapped(full: Double) {
        targetKph = full
        prefs.edit().putFloat("target", targetKph.toFloat()).apply()
        if (!isMoving() || activeRail !== b.rail) return
        ble.send(PitPatProtocol.setSpeed(full, targetIncline))
        lockTarget = full
        lockStartedAt = SystemClock.elapsedRealtime()
        b.rail.lock()
        renderLockDots(0)
        b.tvLock.visibility = View.VISIBLE
        main.removeCallbacks(lockTick)
        main.postDelayed(lockTick, LOCK_TICK_MS)
        renderBigSpeed()
    }

    override fun onReleased(value: Double) {
        targetKph = value
        prefs.edit().putFloat("target", targetKph.toFloat()).apply()
        rails.forEach { if (it !== activeRail) it.value = value }
        if (isMoving()) ble.send(PitPatProtocol.setSpeed(value, targetIncline))
        renderBigSpeed()
    }

    private fun endLock() {
        main.removeCallbacks(lockTick)
        b.rail.unlock()
        b.tvLock.visibility = View.INVISIBLE
        renderBigSpeed()
    }

    private fun renderLockDots(elapsedMs: Long) {
        val total = (LOCK_MAX_MS / 1000).toInt()
        val left = (total - (elapsedMs / 1000).toInt()).coerceIn(0, total)
        b.tvLock.text = buildString { repeat(left) { append('●') }; repeat(total - left) { append('○') } }
    }

    private fun setTarget(kph: Double) {
        targetKph = kph.coerceIn(MIN_KPH, maxKph)
        rails.forEach { it.value = targetKph }
        renderTarget()
        prefs.edit().putFloat("target", targetKph.toFloat()).apply()
        if (isMoving()) ble.send(PitPatProtocol.setSpeed(targetKph, targetIncline))
    }

    private fun adjustIncline(delta: Int) {
        targetIncline = (targetIncline + delta).coerceIn(0, maxIncline)
        renderIncline()
        main.removeCallbacks(sendInclineRunnable)
        inclineSendPending = isMoving()
        if (inclineSendPending) main.postDelayed(sendInclineRunnable, INCLINE_DEBOUNCE_MS)
    }

    private fun renderTarget() {
        val g = selectedGoal
        b.btnStart.text = if (g == null) getString(R.string.start_at, Fmt.kph(targetKph)) else getString(R.string.start_goal, g.label, Fmt.kph(targetKph))
    }

    private fun renderIncline() {
        b.tvIncline.text = "$targetIncline"
    }

    // --- goals ---

    private fun buildGoalChips() {
        b.goalChips.removeAllViews()
        addGoalChip(null)
        (Goal.BUILTIN + customGoals).forEach { addGoalChip(it) }
        val add = Chip(this).apply {
            text = getString(R.string.goal_new)
            isCheckable = false
            setOnClickListener { showGoalDialog() }
        }
        b.goalChips.addView(add)
    }

    private fun addGoalChip(goal: Goal?) {
        val chip = Chip(this).apply {
            text = goal?.label ?: getString(R.string.goal_free)
            isCheckable = true
            setOnClickListener { selectGoal(goal) }
            if (goal?.custom == true) setOnLongClickListener { confirmDeleteGoal(goal); true }
        }
        b.goalChips.addView(chip)
    }

    private fun selectGoal(goal: Goal?) {
        selectedGoal = goal
        tracker.goal = goal
        prefs.edit().apply { if (goal == null) remove("goal") else putString("goal", goal.toJson().toString()) }.apply()
        goal?.speedKph?.let { setTarget(it) }
        renderTarget()
        renderGoal()
    }

    private fun renderGoal() {
        val wanted = selectedGoal?.label ?: getString(R.string.goal_free)
        for (i in 0 until b.goalChips.childCount) {
            val c = b.goalChips.getChildAt(i) as Chip
            if (c.isCheckable) c.isChecked = c.text == wanted
        }
        renderGoalProgress()
    }

    /** Goal ring on the run screen: remaining value in the middle, the rest below. */
    private fun renderGoalProgress() {
        val g = selectedGoal
        val p = tracker.progress
        b.ring.active = g != null
        if (g == null) {
            b.ring.progress = 0f
            b.tvGoalMain.text = Fmt.clock(p.durationS)
            b.tvGoalSub.text = Fmt.km2(p.distanceKm)
            b.tvGoalLabel.text = getString(R.string.goal_free)
            return
        }
        b.ring.progress = g.progress(p.durationS, p.distanceKm).toFloat()
        when (g.type) {
            Goal.Type.TIME -> {
                b.tvGoalMain.text = Fmt.clock((g.value * 60 - p.durationS).coerceAtLeast(0.0))
                b.tvGoalSub.text = getString(R.string.goal_remaining_of, Fmt.clock(g.value * 60), Fmt.km2(p.distanceKm))
            }
            Goal.Type.DISTANCE -> {
                b.tvGoalMain.text = Fmt.km2((g.value - p.distanceKm).coerceAtLeast(0.0))
                b.tvGoalSub.text = getString(R.string.goal_remaining_of, Fmt.km2(g.value), Fmt.clock(p.durationS))
            }
        }
        b.tvGoalLabel.text = getString(R.string.goal_label, g.label)
    }

    private fun showGoalDialog() {
        val d = DialogGoalBinding.inflate(layoutInflater)
        d.typeGroup.check(R.id.btnTypeTime)
        AlertDialog.Builder(this)
            .setTitle(R.string.goal_new_title)
            .setView(d.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val type = if (d.typeGroup.checkedButtonId == R.id.btnTypeDistance) Goal.Type.DISTANCE else Goal.Type.TIME
                val value = d.etValue.text.toString().replace(',', '.').toDoubleOrNull()
                if (value == null || value <= 0) { toast(getString(R.string.goal_invalid)); return@setPositiveButton }
                val speed = d.etSpeed.text.toString().replace(',', '.').toDoubleOrNull()?.coerceIn(MIN_KPH, maxKph)
                val goal = Goal(type, value, speed, custom = true)
                customGoals = customGoals + goal
                prefs.edit().putString("goals", Goal.listToJson(customGoals)).apply()
                buildGoalChips()
                selectGoal(goal)
            }
            .show()
    }

    private fun confirmDeleteGoal(goal: Goal) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.goal_delete, goal.label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                customGoals = customGoals - goal
                prefs.edit().putString("goals", Goal.listToJson(customGoals)).apply()
                if (selectedGoal?.copy(custom = true) == goal) selectGoal(null)
                buildGoalChips()
                renderGoal()
            }
            .show()
    }

    // --- helpers ---

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
    }

    companion object {
        private const val MIN_KPH = 0.5
        private const val DEFAULT_MAX_KPH = 6.0
        private const val INCLINE_DEBOUNCE_MS = 400L
        /** Rail stays locked after a full-km/h snap until the belt reports the target, at most this long. */
        private const val LOCK_MAX_MS = 3000L
        private const val LOCK_TOLERANCE = 0.1
        private const val LOCK_TICK_MS = 200L
    }
}
