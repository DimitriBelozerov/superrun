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
    private var dayStarts = LongArray(0)
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
        b.weekChart.dotsAlways = true
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

    /**
     * km per day from the first run (at least 4 weeks) to today, today rightmost, horizontally scrollable.
     * Tapping a point filters the list to that day.
     */
    private fun renderWeek() {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val first = Calendar.getInstance().apply {
            timeInMillis = minOf(all.minOfOrNull { it.startMs } ?: today.timeInMillis, today.timeInMillis - 27L * 86_400_000)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val days = ArrayList<Long>()
        val cal = first.clone() as Calendar
        while (cal.timeInMillis <= today.timeInMillis) {
            days += cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        dayStarts = days.toLongArray()
        val n = dayStarts.size
        val dayFmt = SimpleDateFormat("EEE d", Locale.GERMANY)
        val monthFmt = SimpleDateFormat("MMM", Locale.GERMANY)
        val values = FloatArray(n)
        val labels = ArrayList<String>(n)
        val subs = ArrayList<String>(n)
        for (i in 0 until n) {
            val to = if (i + 1 < n) dayStarts[i + 1] else Long.MAX_VALUE
            values[i] = all.filter { it.startMs in dayStarts[i] until to }.sumOf { it.distanceKm }.toFloat()
            val c = Calendar.getInstance().apply { timeInMillis = dayStarts[i] }
            labels += dayFmt.format(c.time).replace(".", "")
            subs += if (i == 0 || c.get(Calendar.DAY_OF_MONTH) == 1) monthFmt.format(c.time).trimEnd('.') else ""
        }
        val density = resources.displayMetrics.density
        // Inside a HorizontalScrollView the child is measured UNSPECIFIED; a plain View then takes its minimum width.
        b.weekChart.minimumWidth = (n * DAY_WIDTH_DP * density).toInt()
        b.weekChart.requestLayout()
        b.weekChart.values = values
        b.weekChart.labels = labels
        b.weekChart.subLabels = subs
        b.weekChart.selected = selectedDay
        b.dayScroll.post { b.dayScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun renderList() {
        shown = if (selectedDay < 0 || selectedDay >= dayStarts.size) all else {
            val from = dayStarts[selectedDay]
            val to = if (selectedDay + 1 < dayStarts.size) dayStarts[selectedDay + 1] else Long.MAX_VALUE
            all.filter { it.startMs in from until to }
        }
        adapter.notifyDataSetChanged()
        b.list.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        val totals = if (shown.isEmpty()) getString(R.string.week_empty) else resources.getQuantityString(
            R.plurals.history_totals, shown.size, shown.size, Fmt.km2(shown.sumOf { it.distanceKm }), Fmt.minutes(shown.sumOf { it.durationS }.toDouble())
        )
        val prefix = if (selectedDay < 0 || selectedDay >= dayStarts.size) "" else SimpleDateFormat("EEE, d. MMM", Locale.GERMANY).format(Date(dayStarts[selectedDay])) + " · "
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

    private companion object {
        const val DAY_WIDTH_DP = 48
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
