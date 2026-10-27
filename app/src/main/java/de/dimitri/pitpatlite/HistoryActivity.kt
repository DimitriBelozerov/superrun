package de.dimitri.pitpatlite

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.dimitri.pitpatlite.databinding.ActivityHistoryBinding
import de.dimitri.pitpatlite.databinding.ItemRunBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var b: ActivityHistoryBinding
    private val store by lazy { RunStore(File(filesDir, "runs.json")) }
    private var runs: List<Run> = emptyList()
    private var badges: Map<Long, List<Records.Kind>> = emptyMap()
    private val adapter = RunAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        val d = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding((24 * d).toInt() + bars.left, (16 * d).toInt() + bars.top, (24 * d).toInt() + bars.right, (16 * d).toInt() + bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { finish() }
        val ble = (application as App).ble
        b.btnMute.setOnClickListener {
            val on = ble.lastStatus?.buzzerOn
            ble.send(if (on == false) PitPatProtocol.SOUND_ON else PitPatProtocol.SOUND_OFF)
            b.btnMute.text = getString(if (on == false) R.string.mute else R.string.unmute)
        }
        ble.lastStatus?.buzzerOn?.let {
            b.btnMute.visibility = View.VISIBLE
            b.btnMute.text = getString(if (it) R.string.mute else R.string.unmute)
        }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        reload()
    }

    private fun reload() {
        runs = store.load().sortedByDescending { it.startMs }
        badges = Records.badges(runs)
        adapter.notifyDataSetChanged()
        val empty = runs.isEmpty()
        b.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        b.list.visibility = if (empty) View.GONE else View.VISIBLE
        b.weekBars.visibility = if (empty) View.GONE else View.VISIBLE
        b.tvBests.visibility = if (empty) View.GONE else View.VISIBLE
        renderWeek()
        renderBests()
        b.tvTotals.text = if (empty) "" else resources.getQuantityString(
            R.plurals.history_totals, runs.size, runs.size, Fmt.km2(runs.sumOf { it.distanceKm }), Fmt.minutes(runs.sumOf { it.durationS }.toDouble())
        )
    }

    /** km per day, last 7 days, today rightmost. */
    private fun renderWeek() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -6)
        }
        val fmt = SimpleDateFormat("EEE", Locale.GERMANY)
        val values = FloatArray(7)
        val labels = ArrayList<String>(7)
        repeat(7) { i ->
            val from = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            val to = cal.timeInMillis
            values[i] = runs.filter { it.startMs in from until to }.sumOf { it.distanceKm }.toFloat()
            labels += fmt.format(Date(from)).trimEnd('.')
        }
        b.weekBars.values = values
        b.weekBars.labels = labels
        b.weekBars.highlight = 6
    }

    private fun renderBests() {
        val bests = Records.bests(runs)
        val day = SimpleDateFormat("d. MMM", Locale.GERMANY)
        fun line(label: Int, kind: Records.Kind, value: (Run) -> String) =
            bests[kind]?.let { "${getString(label)}  ${value(it)} · ${day.format(Date(it.startMs))}" }
        b.tvBests.text = listOfNotNull(
            line(R.string.best_distance, Records.Kind.DISTANCE) { Fmt.km2(it.distanceKm) },
            line(R.string.best_duration, Records.Kind.DURATION) { Fmt.clock(it.durationS.toDouble()) },
            line(R.string.best_avg, Records.Kind.AVG) { "${Fmt.kph(it.avgKph)} km/h" },
            line(R.string.best_steps, Records.Kind.STEPS) { it.steps.toString() },
        ).joinToString("\n")
    }

    private fun confirmDelete(run: Run) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.run_delete, Fmt.date(run.startMs)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> store.delete(run.startMs); reload() }
            .show()
    }

    private inner class RunAdapter : RecyclerView.Adapter<RunAdapter.VH>() {
        inner class VH(val v: ItemRunBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemRunBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = runs.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val r = runs[i]
            h.v.tvDate.text = Fmt.date(r.startMs)
            h.v.tvDuration.text = Fmt.clock(r.durationS.toDouble())
            h.v.tvDistance.text = Fmt.km2(r.distanceKm)
            h.v.tvAvg.text = getString(R.string.avg_kph, Fmt.kph(r.avgKph))
            h.v.tvSteps.text = r.steps.toString()
            h.v.tvCalories.text = getString(R.string.kcal_value, r.calories)
            h.v.tvGoal.visibility = if (r.goal != null) View.VISIBLE else View.GONE
            h.v.tvGoal.text = r.goal?.let { if (r.goalReached) "$it ✓" else it }
            val pb = badges[r.startMs]
            h.v.tvPb.visibility = if (pb.isNullOrEmpty()) View.GONE else View.VISIBLE
            h.v.tvPb.text = pb?.let { getString(R.string.pb_prefix, it.joinToString(" · ") { k -> k.label }) }
            h.v.bars.visibility = if (r.samples.size >= 2) View.VISIBLE else View.GONE
            h.v.bars.values = r.samples.toFloatArray()
            h.v.root.setOnLongClickListener { confirmDelete(r); true }
        }
    }
}
