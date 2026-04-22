package com.codex.exercisevision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CEDBD2")
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }

    private val caloriesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F8A5B")
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }

    private val repsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F18731")
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#526458")
        textSize = dp(10f)
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6D7F73")
        textSize = dp(12f)
    }

    private var labels: List<String> = emptyList()
    private var calories: List<Float> = emptyList()
    private var reps: List<Float> = emptyList()
    private var noDataMessage: String = "No data yet."

    fun setData(labels: List<String>, calories: List<Float>, reps: List<Float>, noDataMessage: String) {
        this.labels = labels
        this.calories = calories
        this.reps = reps
        this.noDataMessage = noDataMessage
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = dp(12f)
        val right = width - dp(12f)
        val top = dp(16f)
        val bottom = height - dp(24f)
        val chartWidth = right - left
        val chartHeight = bottom - top

        if (labels.isEmpty() || calories.isEmpty() || reps.isEmpty()) {
            canvas.drawText(noDataMessage, left, height / 2f, noDataPaint)
            return
        }

        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, top, left, bottom, axisPaint)

        val maxValue = max(calories.maxOrNull() ?: 0f, reps.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val count = labels.size
        if (count < 2) {
            return
        }

        val stepX = chartWidth / (count - 1)
        val caloriesPath = Path()
        val repsPath = Path()

        for (i in 0 until count) {
            val x = left + (i * stepX)
            val calY = bottom - ((calories.getOrElse(i) { 0f } / maxValue) * chartHeight)
            val repsY = bottom - ((reps.getOrElse(i) { 0f } / maxValue) * chartHeight)

            if (i == 0) {
                caloriesPath.moveTo(x, calY)
                repsPath.moveTo(x, repsY)
            } else {
                caloriesPath.lineTo(x, calY)
                repsPath.lineTo(x, repsY)
            }

            if (i % max(1, count / 4) == 0 || i == count - 1) {
                val trimmed = labels[i].takeLast(5)
                canvas.drawText(trimmed, x - dp(10f), height - dp(6f), labelPaint)
            }
        }

        canvas.drawPath(caloriesPath, caloriesPaint)
        canvas.drawPath(repsPath, repsPaint)

        canvas.drawText("Calories", left, top - dp(4f), caloriesPaint)
        canvas.drawText("Reps", left + dp(70f), top - dp(4f), repsPaint)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
