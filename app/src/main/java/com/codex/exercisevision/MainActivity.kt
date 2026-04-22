package com.codex.exercisevision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "exercise_vision_prefs"
        private const val SESSIONS_KEY = "sessions_json"
    }

    private val exerciseProfiles = mapOf(
        "Squat" to ExerciseProfile(
            met = 5.0,
            tips = listOf(
                "Keep your chest lifted and spine neutral.",
                "Drive knees in line with toes.",
                "Lower until thighs are near parallel."
            )
        ),
        "Push-up" to ExerciseProfile(
            met = 8.0,
            tips = listOf(
                "Keep head, hips, and ankles in one line.",
                "Lower chest under control.",
                "Avoid flaring elbows too wide."
            )
        ),
        "Jumping Jack" to ExerciseProfile(
            met = 8.0,
            tips = listOf(
                "Raise arms fully overhead.",
                "Land softly with knees unlocked.",
                "Keep a steady rhythm."
            )
        ),
        "Lunge" to ExerciseProfile(
            met = 5.5,
            tips = listOf(
                "Keep front knee over ankle.",
                "Drop rear knee straight down.",
                "Keep torso upright."
            )
        ),
        "Unknown" to ExerciseProfile(
            met = 1.5,
            tips = listOf("Move farther back so your full body is visible.")
        )
    )

    private lateinit var previewView: PreviewView
    private lateinit var enableCameraButton: Button
    private lateinit var startSessionButton: Button
    private lateinit var stopSessionButton: Button
    private lateinit var weightInput: EditText
    private lateinit var goalInput: EditText
    private lateinit var exerciseNameText: TextView
    private lateinit var formStatusText: TextView
    private lateinit var repCountText: TextView
    private lateinit var caloriesText: TextView
    private lateinit var activeTimeText: TextView
    private lateinit var goalProgressText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var periodSpinner: Spinner
    private lateinit var analyticsRecyclerView: RecyclerView
    private lateinit var dailyChart: LineChart
    private lateinit var monthlyChart: LineChart
    private lateinit var yearlyChart: LineChart

    private val gson = Gson()
    private val sessions = mutableListOf<SessionRecord>()
    private lateinit var analyticsAdapter: AnalyticsAdapter

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val isAnalyzing = AtomicBoolean(false)
    private lateinit var poseDetector: PoseDetector
    private var cameraReady = false

    private var sessionActive = false
    private var sessionStartedAtMs = 0L
    private var lastFrameRealtimeMs: Long? = null
    private var caloriesBurned = 0.0
    private var activeSeconds = 0.0
    private var totalReps = 0
    private var formFrameCount = 0
    private var goodFormFrameCount = 0
    private var currentExercise = "Unknown"

    private val exerciseWindow = ArrayDeque<String>()

    private val repState = mutableMapOf(
        "squat" to "up",
        "pushup" to "up",
        "jumpingJack" to "in",
        "lunge" to "up"
    )

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                bindCameraUseCases()
            } else {
                toast("Camera permission is required for exercise detection.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        configurePoseDetector()
        configureRecycler()
        configurePeriodSpinner()
        loadSessions()
        renderAnalytics()
        updateLiveUi(
            exercise = "Unknown",
            formOk = false,
            feedback = listOf("Enable camera, then start a session.")
        )

        enableCameraButton.setOnClickListener { requestCameraPermissionAndStart() }
        startSessionButton.setOnClickListener { startSession() }
        stopSessionButton.setOnClickListener { stopSession() }
    }

    override fun onDestroy() {
        super.onDestroy()
        poseDetector.close()
        cameraExecutor.shutdown()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        enableCameraButton = findViewById(R.id.enableCameraButton)
        startSessionButton = findViewById(R.id.startSessionButton)
        stopSessionButton = findViewById(R.id.stopSessionButton)
        weightInput = findViewById(R.id.weightInput)
        goalInput = findViewById(R.id.goalInput)
        exerciseNameText = findViewById(R.id.exerciseNameText)
        formStatusText = findViewById(R.id.formStatusText)
        repCountText = findViewById(R.id.repCountText)
        caloriesText = findViewById(R.id.caloriesText)
        activeTimeText = findViewById(R.id.activeTimeText)
        goalProgressText = findViewById(R.id.goalProgressText)
        feedbackText = findViewById(R.id.feedbackText)
        periodSpinner = findViewById(R.id.periodSpinner)
        analyticsRecyclerView = findViewById(R.id.analyticsRecyclerView)
        dailyChart = findViewById(R.id.dailyChart)
        monthlyChart = findViewById(R.id.monthlyChart)
        yearlyChart = findViewById(R.id.yearlyChart)
    }

    private fun configurePoseDetector() {
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
        poseDetector = PoseDetection.getClient(options)
    }

    private fun configureRecycler() {
        analyticsAdapter = AnalyticsAdapter()
        analyticsRecyclerView.layoutManager = LinearLayoutManager(this)
        analyticsRecyclerView.adapter = analyticsAdapter
    }

    private fun configurePeriodSpinner() {
        val labels = listOf("Daily", "Monthly", "Yearly")
        periodSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        periodSpinner.setSelection(0)
        periodSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                renderAnalytics()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
    }

    private fun requestCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            bindCameraUseCases()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun bindCameraUseCases() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()

                val previewUseCase = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val analysisUseCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, PoseAnalyzer())
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    previewUseCase,
                    analysisUseCase
                )
                cameraReady = true
                enableCameraButton.isEnabled = false
                enableCameraButton.text = "Camera Enabled"
                toast("Camera is ready.")
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private inner class PoseAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            if (isAnalyzing.getAndSet(true)) {
                imageProxy.close()
                return
            }

            val inputImage = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            poseDetector.process(inputImage)
                .addOnSuccessListener { pose ->
                    handlePoseResult(pose)
                }
                .addOnFailureListener {
                    // Ignore transient detection failures.
                }
                .addOnCompleteListener {
                    isAnalyzing.set(false)
                    imageProxy.close()
                }
        }
    }

    private fun handlePoseResult(pose: Pose) {
        val metrics = extractMetrics(pose)
        if (metrics == null) {
            currentExercise = "Unknown"
            updateCalories(formOk = false)
            runOnUiThread {
                updateLiveUi(
                    exercise = "Unknown",
                    formOk = false,
                    feedback = listOf("Move farther back so your full body stays in frame.")
                )
            }
            return
        }

        val candidateExercise = detectExercise(metrics)
        currentExercise = smoothExercise(candidateExercise)
        val formResult = evaluateForm(currentExercise, metrics)
        maybeCountRep(currentExercise, metrics, formResult.first)
        updateCalories(formResult.first)

        runOnUiThread {
            updateLiveUi(
                exercise = currentExercise,
                formOk = formResult.first,
                feedback = formResult.second
            )
        }
    }

    private fun startSession() {
        if (!cameraReady) {
            toast("Enable camera first.")
            return
        }

        sessionActive = true
        sessionStartedAtMs = System.currentTimeMillis()
        lastFrameRealtimeMs = null
        caloriesBurned = 0.0
        activeSeconds = 0.0
        totalReps = 0
        formFrameCount = 0
        goodFormFrameCount = 0
        currentExercise = "Unknown"
        exerciseWindow.clear()
        repState["squat"] = "up"
        repState["pushup"] = "up"
        repState["jumpingJack"] = "in"
        repState["lunge"] = "up"

        startSessionButton.isEnabled = false
        stopSessionButton.isEnabled = true
        updateLiveUi(
            exercise = "Unknown",
            formOk = false,
            feedback = listOf("Session started. Begin your exercise.")
        )
    }

    private fun stopSession() {
        if (!sessionActive) {
            return
        }

        sessionActive = false
        startSessionButton.isEnabled = true
        stopSessionButton.isEnabled = false

        val minutes = activeSeconds / 60.0
        val formPercent = if (formFrameCount == 0) 0.0 else {
            (goodFormFrameCount.toDouble() / formFrameCount.toDouble()) * 100.0
        }

        val record = SessionRecord(
            id = UUID.randomUUID().toString(),
            startedAt = sessionStartedAtMs,
            endedAt = System.currentTimeMillis(),
            calories = roundTo2(caloriesBurned),
            reps = totalReps,
            minutes = roundTo2(minutes),
            formPercent = roundTo1(formPercent)
        )
        sessions.add(record)
        saveSessions()
        renderAnalytics()

        val summary = String.format(
            Locale.US,
            "Session saved: %.2f cal, %d reps, %.2f min.",
            record.calories,
            record.reps,
            record.minutes
        )
        feedbackText.text = summary
        toast("Session stored")
    }

    private fun extractMetrics(pose: Pose): PoseMetrics? {
        val leftShoulder = landmark(pose, PoseLandmark.LEFT_SHOULDER) ?: return null
        val rightShoulder = landmark(pose, PoseLandmark.RIGHT_SHOULDER) ?: return null
        val leftElbow = landmark(pose, PoseLandmark.LEFT_ELBOW) ?: return null
        val rightElbow = landmark(pose, PoseLandmark.RIGHT_ELBOW) ?: return null
        val leftWrist = landmark(pose, PoseLandmark.LEFT_WRIST) ?: return null
        val rightWrist = landmark(pose, PoseLandmark.RIGHT_WRIST) ?: return null
        val leftHip = landmark(pose, PoseLandmark.LEFT_HIP) ?: return null
        val rightHip = landmark(pose, PoseLandmark.RIGHT_HIP) ?: return null
        val leftKnee = landmark(pose, PoseLandmark.LEFT_KNEE) ?: return null
        val rightKnee = landmark(pose, PoseLandmark.RIGHT_KNEE) ?: return null
        val leftAnkle = landmark(pose, PoseLandmark.LEFT_ANKLE) ?: return null
        val rightAnkle = landmark(pose, PoseLandmark.RIGHT_ANKLE) ?: return null

        val shoulderMid = midpoint(leftShoulder, rightShoulder)
        val hipMid = midpoint(leftHip, rightHip)
        val kneeMid = midpoint(leftKnee, rightKnee)
        val ankleMid = midpoint(leftAnkle, rightAnkle)

        val shoulderWidth = max(distance(leftShoulder, rightShoulder), 1.0)
        val ankleDistanceNorm = distance(leftAnkle, rightAnkle) / shoulderWidth
        val hipDepthNorm = (hipMid.y - kneeMid.y) / shoulderWidth

        val kneeAngle = min(
            angle(leftHip, leftKnee, leftAnkle),
            angle(rightHip, rightKnee, rightAnkle)
        )
        val elbowAngle = min(
            angle(leftShoulder, leftElbow, leftWrist),
            angle(rightShoulder, rightElbow, rightWrist)
        )
        val bodyLineAngle = angle(shoulderMid, hipMid, ankleMid)
        val torsoTilt = angleFromVertical(shoulderMid, hipMid)
        val wristsAboveShoulders = leftWrist.y < leftShoulder.y && rightWrist.y < rightShoulder.y
        val bodyHorizontal = abs(shoulderMid.y - hipMid.y) / shoulderWidth < 0.35

        return PoseMetrics(
            kneeAngle = kneeAngle,
            elbowAngle = elbowAngle,
            bodyLineAngle = bodyLineAngle,
            torsoTilt = torsoTilt,
            ankleDistanceNorm = ankleDistanceNorm,
            wristsAboveShoulders = wristsAboveShoulders,
            bodyHorizontal = bodyHorizontal,
            hipDepthNorm = hipDepthNorm
        )
    }

    private fun detectExercise(metrics: PoseMetrics): String {
        val scores = mutableMapOf(
            "Squat" to 0,
            "Push-up" to 0,
            "Jumping Jack" to 0,
            "Lunge" to 0
        )

        if (metrics.kneeAngle < 145) scores["Squat"] = scores["Squat"]!! + 2
        if (metrics.hipDepthNorm > -0.25) scores["Squat"] = scores["Squat"]!! + 1
        if (metrics.torsoTilt < 35) scores["Squat"] = scores["Squat"]!! + 1
        if (metrics.ankleDistanceNorm in 0.8..2.2) scores["Squat"] = scores["Squat"]!! + 1

        if (metrics.bodyHorizontal) scores["Push-up"] = scores["Push-up"]!! + 2
        if (metrics.elbowAngle < 150) scores["Push-up"] = scores["Push-up"]!! + 1
        if (metrics.bodyLineAngle > 155) scores["Push-up"] = scores["Push-up"]!! + 1

        if (metrics.wristsAboveShoulders) scores["Jumping Jack"] = scores["Jumping Jack"]!! + 2
        if (metrics.ankleDistanceNorm > 1.8) scores["Jumping Jack"] = scores["Jumping Jack"]!! + 2
        if (metrics.kneeAngle > 150) scores["Jumping Jack"] = scores["Jumping Jack"]!! + 1

        if (metrics.kneeAngle < 115 && metrics.ankleDistanceNorm > 1.1) {
            scores["Lunge"] = scores["Lunge"]!! + 2
        }
        if (metrics.torsoTilt < 28) scores["Lunge"] = scores["Lunge"]!! + 1

        val winner = scores.maxByOrNull { it.value } ?: return "Unknown"
        return if (winner.value >= 3) winner.key else "Unknown"
    }

    private fun smoothExercise(candidate: String): String {
        exerciseWindow.addLast(candidate)
        if (exerciseWindow.size > 12) {
            exerciseWindow.removeFirst()
        }

        val freq = mutableMapOf<String, Int>()
        for (value in exerciseWindow) {
            freq[value] = (freq[value] ?: 0) + 1
        }

        return freq.maxByOrNull { it.value }?.key ?: "Unknown"
    }

    private fun evaluateForm(exercise: String, metrics: PoseMetrics): Pair<Boolean, List<String>> {
        val feedback = mutableListOf<String>()

        when (exercise) {
            "Squat" -> {
                if (metrics.torsoTilt > 40) {
                    feedback.add("Keep your chest up. Too much forward lean detected.")
                }
                if (metrics.kneeAngle > 115) {
                    feedback.add("Go a little deeper for better squat depth.")
                }
                if (metrics.ankleDistanceNorm < 0.8) {
                    feedback.add("Place feet slightly wider for balance.")
                }
            }

            "Push-up" -> {
                if (metrics.bodyLineAngle < 160) {
                    feedback.add("Keep your body straight from shoulders to ankles.")
                }
                if (metrics.elbowAngle > 105) {
                    feedback.add("Lower further to complete full range of motion.")
                }
                if (!metrics.bodyHorizontal) {
                    feedback.add("Turn slightly sideways to improve push-up tracking.")
                }
            }

            "Jumping Jack" -> {
                if (!metrics.wristsAboveShoulders) {
                    feedback.add("Raise your hands fully overhead each rep.")
                }
                if (metrics.ankleDistanceNorm < 1.6) {
                    feedback.add("Jump feet wider for a complete jack movement.")
                }
            }

            "Lunge" -> {
                if (metrics.torsoTilt > 30) {
                    feedback.add("Keep your torso upright during lunge.")
                }
                if (metrics.kneeAngle > 120) {
                    feedback.add("Bend the front knee more for stronger depth.")
                }
            }

            else -> {
                feedback.add("Stand where your full body is visible to the camera.")
            }
        }

        val isCorrect = feedback.isEmpty()
        if (isCorrect && exercise != "Unknown") {
            feedback.add("Great form. Keep going.")
        }
        return Pair(isCorrect, feedback)
    }

    private fun maybeCountRep(exercise: String, metrics: PoseMetrics, formOk: Boolean) {
        if (!sessionActive || !formOk) {
            return
        }

        if (exercise == "Squat") {
            if (metrics.kneeAngle < 100 && repState["squat"] == "up") {
                repState["squat"] = "down"
            }
            if (metrics.kneeAngle > 155 && repState["squat"] == "down") {
                repState["squat"] = "up"
                totalReps += 1
            }
        }

        if (exercise == "Push-up") {
            if (metrics.elbowAngle < 85 && repState["pushup"] == "up") {
                repState["pushup"] = "down"
            }
            if (metrics.elbowAngle > 155 && repState["pushup"] == "down") {
                repState["pushup"] = "up"
                totalReps += 1
            }
        }

        if (exercise == "Jumping Jack") {
            val outPosition = metrics.wristsAboveShoulders && metrics.ankleDistanceNorm > 1.9
            val inPosition = !metrics.wristsAboveShoulders && metrics.ankleDistanceNorm < 1.35
            if (outPosition && repState["jumpingJack"] == "in") {
                repState["jumpingJack"] = "out"
            }
            if (inPosition && repState["jumpingJack"] == "out") {
                repState["jumpingJack"] = "in"
                totalReps += 1
            }
        }

        if (exercise == "Lunge") {
            if (metrics.kneeAngle < 95 && repState["lunge"] == "up") {
                repState["lunge"] = "down"
            }
            if (metrics.kneeAngle > 150 && repState["lunge"] == "down") {
                repState["lunge"] = "up"
                totalReps += 1
            }
        }
    }

    private fun updateCalories(formOk: Boolean) {
        val nowMs = SystemClock.elapsedRealtime()
        val lastMs = lastFrameRealtimeMs
        lastFrameRealtimeMs = nowMs

        if (lastMs == null || !sessionActive) {
            return
        }

        val dtSeconds = min((nowMs - lastMs).toDouble() / 1000.0, 1.0)
        val weightKg = parseDouble(weightInput.text?.toString(), defaultValue = 70.0).coerceIn(30.0, 220.0)
        val met = exerciseProfiles[currentExercise]?.met ?: exerciseProfiles.getValue("Unknown").met
        val qualityMultiplier = if (formOk) 1.0 else 0.75
        val caloriesPerMinute = (met * 3.5 * weightKg) / 200.0

        caloriesBurned += caloriesPerMinute * (dtSeconds / 60.0) * qualityMultiplier
        activeSeconds += dtSeconds
        formFrameCount += 1
        if (formOk) {
            goodFormFrameCount += 1
        }
    }

    private fun updateLiveUi(exercise: String, formOk: Boolean, feedback: List<String>) {
        exerciseNameText.text = "Exercise: $exercise"
        formStatusText.text = when {
            exercise == "Unknown" -> "Form: -"
            formOk -> "Form: Correct"
            else -> "Form: Needs Adjustment"
        }
        repCountText.text = "Reps: $totalReps"
        caloriesText.text = String.format(Locale.US, "Calories: %.2f", caloriesBurned)
        activeTimeText.text = "Active Time: ${formatTime(activeSeconds)}"

        val goal = parseDouble(goalInput.text?.toString(), defaultValue = 350.0).coerceAtLeast(10.0)
        val progressPercent = min((caloriesBurned / goal) * 100.0, 999.0)
        goalProgressText.text = String.format(Locale.US, "Goal Progress: %.0f%%", progressPercent)

        val displayFeedback = if (feedback.isNotEmpty()) {
            feedback
        } else {
            exerciseProfiles[exercise]?.tips ?: exerciseProfiles.getValue("Unknown").tips
        }
        feedbackText.text = displayFeedback.take(3).joinToString("\n") { "• $it" }
    }

    private fun loadSessions() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(SESSIONS_KEY, null) ?: return
        val listType = object : TypeToken<List<SessionRecord>>() {}.type
        val parsed = runCatching { gson.fromJson<List<SessionRecord>>(raw, listType) }.getOrNull() ?: emptyList()
        sessions.clear()
        sessions.addAll(parsed)
    }

    private fun saveSessions() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(SESSIONS_KEY, gson.toJson(sessions)).apply()
    }

    private fun renderAnalytics() {
        val selectedPeriod = when (periodSpinner.selectedItemPosition) {
            1 -> ViewPeriod.MONTHLY
            2 -> ViewPeriod.YEARLY
            else -> ViewPeriod.DAILY
        }
        val rowsForTable = aggregateRows(selectedPeriod)
        analyticsAdapter.submitData(rowsForTable)
        renderCharts()
    }

    private fun renderCharts() {
        val daily = aggregateRows(ViewPeriod.DAILY).reversed().takeLast(30)
        val monthly = aggregateRows(ViewPeriod.MONTHLY).reversed().takeLast(12)
        val yearly = aggregateRows(ViewPeriod.YEARLY).reversed()

        renderLineChart(dailyChart, daily, "Daily")
        renderLineChart(monthlyChart, monthly, "Monthly")
        renderLineChart(yearlyChart, yearly, "Yearly")
    }

    private fun renderLineChart(chart: LineChart, rows: List<AggregatedRow>, noDataLabel: String) {
        if (rows.isEmpty()) {
            chart.clear()
            chart.setNoDataText("$noDataLabel data appears after your first completed session.")
            chart.invalidate()
            return
        }

        val labels = rows.map { it.period }
        val caloriesEntries = rows.mapIndexed { index, row ->
            Entry(index.toFloat(), row.calories.toFloat())
        }
        val repsEntries = rows.mapIndexed { index, row ->
            Entry(index.toFloat(), row.reps.toFloat())
        }

        val caloriesSet = LineDataSet(caloriesEntries, "Calories").apply {
            color = 0xFF1F8A5B.toInt()
            setCircleColor(0xFF1F8A5B.toInt())
            lineWidth = 2f
            circleRadius = 3f
            valueTextSize = 9f
        }

        val repsSet = LineDataSet(repsEntries, "Reps").apply {
            color = 0xFFF18731.toInt()
            setCircleColor(0xFFF18731.toInt())
            lineWidth = 2f
            circleRadius = 3f
            valueTextSize = 9f
        }

        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.legend.isEnabled = true
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            valueFormatter = IndexAxisValueFormatter(labels)
            labelRotationAngle = -35f
        }
        chart.axisLeft.axisMinimum = 0f
        chart.data = LineData(caloriesSet, repsSet)
        chart.invalidate()
    }

    private fun aggregateRows(period: ViewPeriod): List<AggregatedRow> {
        if (sessions.isEmpty()) {
            return emptyList()
        }

        data class Accumulator(
            var sessions: Int = 0,
            var calories: Double = 0.0,
            var reps: Int = 0,
            var minutes: Double = 0.0
        )

        val buckets = mutableMapOf<String, Accumulator>()
        for (entry in sessions) {
            val key = periodKey(entry.endedAt, period)
            val bucket = buckets.getOrPut(key) { Accumulator() }
            bucket.sessions += 1
            bucket.calories += entry.calories
            bucket.reps += entry.reps
            bucket.minutes += entry.minutes
        }

        val sortedKeys = buckets.keys.sorted()
        val ascending = mutableListOf<AggregatedRow>()
        var previousCalories: Double? = null
        for (key in sortedKeys) {
            val bucket = buckets.getValue(key)
            val growth = if (previousCalories == null || previousCalories == 0.0) {
                null
            } else {
                ((bucket.calories - previousCalories) / previousCalories) * 100.0
            }
            ascending.add(
                AggregatedRow(
                    period = key,
                    sessions = bucket.sessions,
                    calories = roundTo2(bucket.calories),
                    reps = bucket.reps,
                    minutes = roundTo2(bucket.minutes),
                    growth = growth?.let { roundTo1(it) }
                )
            )
            previousCalories = bucket.calories
        }

        return ascending.reversed()
    }

    private fun periodKey(timestampMs: Long, period: ViewPeriod): String {
        val zoned = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault())
        return when (period) {
            ViewPeriod.DAILY -> zoned.toLocalDate().toString()
            ViewPeriod.MONTHLY -> String.format(
                Locale.US,
                "%04d-%02d",
                zoned.year,
                zoned.monthValue
            )

            ViewPeriod.YEARLY -> zoned.year.toString()
        }
    }

    private fun landmark(pose: Pose, type: Int): PointF? {
        return pose.getPoseLandmark(type)?.position
    }

    private fun midpoint(a: PointF, b: PointF): PointF {
        return PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }

    private fun distance(a: PointF, b: PointF): Double {
        return hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
    }

    private fun angle(a: PointF, b: PointF, c: PointF): Double {
        val ab = distance(a, b)
        val cb = distance(c, b)
        if (ab == 0.0 || cb == 0.0) {
            return 180.0
        }

        val dot = (a.x - b.x) * (c.x - b.x) + (a.y - b.y) * (c.y - b.y)
        val cosine = clamp(dot.toDouble() / (ab * cb), -1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }

    private fun angleFromVertical(top: PointF, bottom: PointF): Double {
        val dx = abs(top.x - bottom.x).toDouble()
        val dy = abs(bottom.y - top.y).toDouble()
        return Math.toDegrees(atan2(dx, max(dy, 1.0)))
    }

    private fun formatTime(seconds: Double): String {
        val total = max(seconds.toInt(), 0)
        val mins = total / 60
        val secs = total % 60
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    private fun parseDouble(raw: String?, defaultValue: Double): Double {
        return raw?.trim()?.toDoubleOrNull() ?: defaultValue
    }

    private fun clamp(value: Double, minValue: Double, maxValue: Double): Double {
        return max(minValue, min(maxValue, value))
    }

    private fun roundTo2(value: Double): Double {
        return String.format(Locale.US, "%.2f", value).toDouble()
    }

    private fun roundTo1(value: Double): Double {
        return String.format(Locale.US, "%.1f", value).toDouble()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
