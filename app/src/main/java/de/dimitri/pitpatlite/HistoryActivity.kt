package de.dimitri.pitpatlite

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
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
    private var all: List<Run> = emptyList()
    private var shown: List<Run> = emptyList()
    private var badges: Map<Long, List<Records.Kind>> = emptyMap()
    private val adapter = RunAdapter()
    private val dayStarts = LongArray(7)
    private var selectedDay = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) = deleteWithUndo(shown[vh.bindingAdapterPosition])
        }).attachToRecyclerView(b.list)
        b.weekChart.selectable = true
        b.weekChart.valueText = { Fmt.km2(it.toDouble()) }
        b.weekChart.onSelect = { i -> selectedDay = i; renderList() }
        reload()
    }

    private fun reload() {
        all = store.load().sortedByDescending { it.startMs }
        badges = Records.badges(all)
        val empty = all.isEmpty()
        b.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        b.weekChart.visibility = if (empty) View.GONE else View.VISIBLE
        b.cardBests.visibility = if (empty) View.GONE else View.VISIBLE
        renderWeek()
        renderBests()
        renderList()
    }

    /** km per day, last 7 days, today rightmost. Tapping a point filters the list to that day. */
    private fun renderWeek() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -6)
        }
        val fmt = SimpleDateFormat("EEE", Locale.GERMANY)
        val values = FloatArray(7)
        val labels = ArrayList<String>(7)
        repeat(7) { i ->
            dayStarts[i] = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            values[i] = all.filter { it.startMs in dayStarts[i] until cal.timeInMillis }.sumOf { it.distanceKm }.toFloat()
            labels += fmt.format(Date(dayStarts[i])).trimEnd('.')
        }
        b.weekChart.values = values
        b.weekChart.labels = labels
        b.weekChart.selected = selectedDay
    }

    private fun renderList() {
        shown = if (selectedDay < 0) all else {
            val from = dayStarts[selectedDay]
            val to = if (selectedDay < 6) dayStarts[selectedDay + 1] else Long.MAX_VALUE
            all.filter { it.startMs in from until to }
        }
        adapter.notifyDataSetChanged()
        b.list.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        val totals = if (shown.isEmpty()) getString(R.string.week_empty) else resources.getQuantityString(
            R.plurals.history_totals, shown.size, shown.size, Fmt.km2(shown.sumOf { it.distanceKm }), Fmt.minutes(shown.sumOf { it.durationS }.toDouble())
        )
        val prefix = if (selectedDay < 0) "" else SimpleDateFormat("EEE, d. MMM", Locale.GERMANY).format(Date(dayStarts[selectedDay])) + " · "
        b.tvTotals.text = if (all.isEmpty()) "" else prefix + totals
    }

    private fun renderBests() {
        val bests = Records.bests(all)
        val day = SimpleDateFormat("d. MMM", Locale.GERMANY)
        b.bestsGrid.removeAllViews()
        fun tile(label: Int, kind: Records.Kind, value: (Run) -> String) {
            val r = bests[kind] ?: return
            val v = layoutInflater.inflate(R.layout.item_best, b.bestsGrid, false)
            v.findViewById<TextView>(R.id.tvLabel).text = getString(label)
            v.findViewById<TextView>(R.id.tvValue).text = value(r)
            v.findViewById<TextView>(R.id.tvDate).text = day.format(Date(r.startMs))
            v.layoutParams = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 }
            b.bestsGrid.addView(v)
        }
        tile(R.string.best_distance, Records.Kind.DISTANCE) { Fmt.km2(it.distanceKm) }
        tile(R.string.best_duration, Records.Kind.DURATION) { Fmt.clock(it.durationS.toDouble()) }
        tile(R.string.best_avg, Records.Kind.AVG) { "${Fmt.kph(it.avgKph)} km/h" }
        tile(R.string.best_steps, Records.Kind.STEPS) { it.steps.toString() }
    }

    private fun deleteWithUndo(run: Run) {
        store.delete(run.startMs)
        reload()
        Snackbar.make(b.root, R.string.run_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { store.add(run); reload() }
            .show()
    }

    private inner class RunAdapter : RecyclerView.Adapter<RunAdapter.VH>() {
        inner class VH(val v: ItemRunBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemRunBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val r = shown[i]
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
            h.v.chart.visibility = if (r.samples.size >= 2) View.VISIBLE else View.GONE
            h.v.chart.values = r.samples.toFloatArray()
        }
    }
}
