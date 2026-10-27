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

class HistoryActivity : AppCompatActivity() {

    private lateinit var b: ActivityHistoryBinding
    private val store by lazy { RunStore(File(filesDir, "runs.json")) }
    private var runs: List<Run> = emptyList()
    private val adapter = RunAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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
        adapter.notifyDataSetChanged()
        val empty = runs.isEmpty()
        b.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        b.list.visibility = if (empty) View.GONE else View.VISIBLE
        b.tvTotals.text = if (empty) "" else getString(
            R.string.history_totals, runs.size, Fmt.km2(runs.sumOf { it.distanceKm }), Fmt.minutes(runs.sumOf { it.durationS }.toDouble())
        )
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
            h.v.root.setOnLongClickListener { confirmDelete(r); true }
        }
    }
}
