package com.codex.exercisevision

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class AnalyticsAdapter : RecyclerView.Adapter<AnalyticsAdapter.AnalyticsViewHolder>() {
    private val rows = mutableListOf<AggregatedRow>()

    fun submitData(newRows: List<AggregatedRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnalyticsViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_analytics_row, parent, false)
        return AnalyticsViewHolder(view)
    }

    override fun onBindViewHolder(holder: AnalyticsViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    class AnalyticsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val periodText: TextView = itemView.findViewById(R.id.periodText)
        private val sessionsText: TextView = itemView.findViewById(R.id.sessionsText)
        private val caloriesValueText: TextView = itemView.findViewById(R.id.caloriesValueText)
        private val repsValueText: TextView = itemView.findViewById(R.id.repsValueText)
        private val minutesValueText: TextView = itemView.findViewById(R.id.minutesValueText)
        private val growthText: TextView = itemView.findViewById(R.id.growthText)

        fun bind(row: AggregatedRow) {
            periodText.text = row.period
            sessionsText.text = row.sessions.toString()
            caloriesValueText.text = String.format(Locale.US, "%.2f", row.calories)
            repsValueText.text = row.reps.toString()
            minutesValueText.text = String.format(Locale.US, "%.1f", row.minutes)
            growthText.text = formatGrowth(row.growth)
        }

        private fun formatGrowth(growth: Double?): String {
            if (growth == null || growth.isNaN()) {
                return "-"
            }

            val sign = if (growth > 0) "+" else ""
            return String.format(Locale.US, "%s%.1f%%", sign, growth)
        }
    }
}
