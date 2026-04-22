package com.codex.exercisevision

data class ExerciseProfile(
    val met: Double,
    val tips: List<String>
)

data class PoseMetrics(
    val kneeAngle: Double,
    val elbowAngle: Double,
    val bodyLineAngle: Double,
    val torsoTilt: Double,
    val ankleDistanceNorm: Double,
    val wristsAboveShoulders: Boolean,
    val bodyHorizontal: Boolean,
    val hipDepthNorm: Double
)

data class SessionRecord(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val calories: Double,
    val reps: Int,
    val minutes: Double,
    val formPercent: Double
)

data class AggregatedRow(
    val period: String,
    val sessions: Int,
    val calories: Double,
    val reps: Int,
    val minutes: Double,
    val growth: Double?
)

enum class ViewPeriod {
    DAILY,
    MONTHLY,
    YEARLY
}
