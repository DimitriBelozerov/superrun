package de.dimitri.pitpatlite

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import de.dimitri.pitpatlite.PitPatProtocol.BeltState
import de.dimitri.pitpatlite.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), TreadmillBle.Listener {

    private lateinit var b: ActivityMainBinding
    private lateinit var ble: TreadmillBle
    private val prefs by lazy { getSharedPreferences("pitpat", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())

    private var targetKph = 2.0
    private var targetIncline = 0
    private var maxKph = DEFAULT_MAX_KPH
    private var maxIncline = 0
    private var last: PitPatProtocol.Status? = null
    private var buzzerOn: Boolean? = null

    private var speedSendPending = false
    private val sendSpeedRunnable = Runnable {
        speedSendPending = false
        if (isMoving()) ble.send(PitPatProtocol.setSpeed(targetKph, targetIncline))
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) startConnect() else toast(getString(R.string.perm_denied))
    }
    private val enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (ble.isBluetoothOn) startConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Edge-to-edge (API 35+): keep content out of the status/navigation bar zones.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ble = TreadmillBle(applicationContext, this)

        targetKph = prefs.getFloat("target", 2.0f).toDouble()

        b.btnConnect.setOnClickListener {
            if (ble.state == TreadmillBle.ConnState.DISCONNECTED) ensurePermissionsThenConnect() else ble.disconnect()
        }
        b.btnSpeedMinus05.setOnClickListener { adjustSpeed(-0.5) }
        b.btnSpeedMinus01.setOnClickListener { adjustSpeed(-0.1) }
        b.btnSpeedPlus01.setOnClickListener { adjustSpeed(0.1) }
        b.btnSpeedPlus05.setOnClickListener { adjustSpeed(0.5) }
        b.btnInclineMinus.setOnClickListener { adjustIncline(-1) }
        b.btnInclinePlus.setOnClickListener { adjustIncline(1) }
        b.btnStart.setOnClickListener { ble.send(PitPatProtocol.start(targetKph, targetIncline)) }
        b.btnPause.setOnClickListener { ble.send(PitPatProtocol.pause()) }
        b.btnStop.setOnClickListener { ble.send(PitPatProtocol.stop()) }
        b.btnMute.setOnClickListener {
            ble.send(if (buzzerOn == false) PitPatProtocol.SOUND_ON else PitPatProtocol.SOUND_OFF)
        }
        buildPresets()
        renderTarget()
        renderIncline()
        onConnectionChanged(TreadmillBle.ConnState.DISCONNECTED, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        ble.disconnect()
    }

    // --- connection ---

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermissionsThenConnect() {
        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else startConnect()
    }

    private fun startConnect() {
        if (!ble.isBluetoothOn) {
            enableBtLauncher.launch(android.content.Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
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
            TreadmillBle.ConnState.CONNECTED -> getString(R.string.state_connected)
        }
        b.btnConnect.text = getString(if (state == TreadmillBle.ConnState.DISCONNECTED) R.string.connect else R.string.disconnect)
        if (connected) {
            ble.address?.let { prefs.edit().putString("address", it).apply() }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (state == TreadmillBle.ConnState.DISCONNECTED && message == getString(R.string.not_found)) {
                prefs.edit().remove("address").apply()
            }
            last = null
            buzzerOn = null
            renderStatus(null)
        }
        setControlsEnabled(connected)
    }

    // --- status ---

    override fun onStatus(status: PitPatProtocol.Status) {
        last = status
        if (status.maxKph > 0.5) maxKph = status.maxKph
        maxIncline = status.maxIncline
        status.buzzerOn?.let { buzzerOn = it }
        if (!speedSendPending) {
            // Mirror targets set on the treadmill itself (remote / buttons), so a speed command
            // never resends a stale incline and pulls the deck back to 0.
            if (status.state == BeltState.RUNNING && status.targetKph > 0) {
                targetKph = status.targetKph
                renderTarget()
            }
            targetIncline = status.targetIncline.coerceIn(0, maxIncline)
            renderIncline()
        }
        renderStatus(status)
    }

    private fun renderStatus(s: PitPatProtocol.Status?) {
        b.tvSpeed.text = s?.let { fmtSpeed(it.currentKph) } ?: "0.0"
        b.tvBeltState.text = when (s?.state) {
            BeltState.STOPPED -> getString(R.string.belt_stopped)
            BeltState.RUNNING -> getString(R.string.belt_running)
            BeltState.PAUSED -> getString(R.string.belt_paused)
            BeltState.STARTING -> getString(R.string.belt_starting)
            else -> getString(R.string.belt_unknown)
        } + if (s?.checksumOk == false) " ⚠" else ""
        b.tvDistance.text = s?.let { String.format(Locale.GERMANY, "%.2f km", it.distanceKm) } ?: "–"
        b.tvDuration.text = s?.let { fmtDuration(it.durationS) } ?: "–"
        b.tvSteps.text = s?.steps?.toString() ?: "–"
        b.tvCalories.text = s?.calories?.toString() ?: "–"
        b.rowIncline.visibility = if (maxIncline > 0) View.VISIBLE else View.GONE
        b.tvIncline.text = if (s != null && s.incline != targetIncline) "${s.incline}→$targetIncline" else "$targetIncline"
        b.btnMute.text = getString(if (buzzerOn == false) R.string.unmute else R.string.mute)
        b.btnMute.visibility = if (buzzerOn == null && s != null) View.GONE else View.VISIBLE
        b.tvDebug.text = s?.let { "fw ${it.firmware}  max ${fmtSpeed(it.maxKph)}  ${PitPatProtocol.toHex(it.raw)}" } ?: ""
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(b.btnStart, b.btnPause, b.btnStop, b.btnMute, b.btnInclineMinus, b.btnInclinePlus).forEach { it.isEnabled = enabled }
    }

    // --- speed / incline ---

    private fun isMoving(): Boolean = last?.state == BeltState.RUNNING || last?.state == BeltState.STARTING

    private fun adjustSpeed(delta: Double) {
        targetKph = ((targetKph + delta) * 10).roundToInt() / 10.0
        targetKph = targetKph.coerceIn(MIN_KPH, maxKph)
        renderTarget()
        prefs.edit().putFloat("target", targetKph.toFloat()).apply()
        scheduleSpeedSend()
    }

    private fun setPreset(kph: Double) {
        targetKph = kph.coerceIn(MIN_KPH, maxKph)
        renderTarget()
        prefs.edit().putFloat("target", targetKph.toFloat()).apply()
        scheduleSpeedSend()
    }

    private fun adjustIncline(delta: Int) {
        targetIncline = (targetIncline + delta).coerceIn(0, maxIncline)
        renderIncline()
        scheduleSpeedSend()
    }

    private fun scheduleSpeedSend() {
        main.removeCallbacks(sendSpeedRunnable)
        speedSendPending = false
        if (isMoving()) {
            speedSendPending = true
            main.postDelayed(sendSpeedRunnable, SPEED_DEBOUNCE_MS)
        }
    }

    private fun renderTarget() {
        b.tvTarget.text = fmtSpeed(targetKph)
    }

    private fun renderIncline() {
        b.tvIncline.text = "$targetIncline"
    }

    private fun buildPresets() {
        b.rowPresets.removeAllViews()
        PRESETS.forEach { kph ->
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.text = fmtSpeed(kph)
            btn.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            btn.setOnClickListener { setPreset(kph) }
            b.rowPresets.addView(btn)
        }
    }

    // --- helpers ---

    private fun fmtSpeed(kph: Double) = String.format(Locale.US, "%.1f", kph)

    private fun fmtDuration(seconds: Double): String {
        val s = seconds.toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MIN_KPH = 0.5
        private const val DEFAULT_MAX_KPH = 6.0
        private const val SPEED_DEBOUNCE_MS = 400L
        private val PRESETS = listOf(2.0, 3.0, 4.0, 5.0, 6.0)
    }
}
