package com.example.fitnesstracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fitnesstracker.ui.theme.FitnessTrackerTheme
// Class 1
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.sqrt
// Class 2
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.blue
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.components.XAxis
// Class 3
// ---- Add these new imports for Class 3 ----
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.ButtonDefaults
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity(), SensorEventListener{

    // Compose state - read by FitnessTrakerScreen(), written from sensor callbacks
    private var motionText by mutableStateOf("Motion: Stationary")
    private var gyroText by mutableStateOf("Rotation (Pitch, Roll, Yaw): 0.00, 0.00, 0.00")
    private var directionText by mutableStateOf("Direction:")

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null

    // Sensor fusion - both needed for the compass
    private var accelValues = FloatArray(3)
    private var magnetValues = FloatArray(3)

    // Baseline - cancels gravity
    private var baselineX = 0f
    private var baselineY = 0f
    private var baselineZ = 9.8f

    // MPAndroidChart is a View not a composable
    private lateinit var barChart: BarChart

    // Sample data for the chart - index 2 is 0f to create a visible gap
    private val hourlySteps = floatArrayOf(1000f, 1200f, 0f, 800f)

    // Hardware step counter
    private var stepCounterSensor: Sensor? = null // demo mode for emulator

    // Step tracking
    private var sessionStepCount = 0
    private var initialStepCount = -1 // anchor point

    // health metric display state
    private var stepCountText by mutableStateOf("Steps: 0")
    private var caloriesText by mutableStateOf("Calories: 0.0 kcal")
    private var distanceText by mutableStateOf("Distance: 0.0 km")
    private val ACTIVITY_PERMISSION_CODE = 100

    // Demo Mode - Compose state so the button recomposes when it flips
    private var isDemoMode by mutableStateOf(false)
    private val demoHandler = Handler(Looper.getMainLooper())



    companion object {
        private const val STATIONARY_THRESHOLD = 2.0f
        private const val WALKING_THRESHOLD = 12.0f
        //
        private const val CALORIES_PER_STEP = 0.04f   // kcal burned per step (adult average) -- Assignment 3 uses this
        private const val STRIDE_LENGTH_M = 0.762f    // metres per step (adult average) -- Assignment 3 uses this
        private const val ACTIVITY_PERMISSION_CODE = 100  // identifies the permission request
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSensors()
        requestActivityPermission()

        setContent {
            FitnessTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FitnessTrackerScreen(
                        motionText = motionText,
                        gyroText = gyroText,
                        directionText = directionText,
                        onRefreshChartClick = { setupChart() },
                        onChartCreated = { chart -> barChart = chart; setupChart() },
                        stepCountText = stepCountText,
                        caloriesText = caloriesText,
                        distanceText = distanceText,
                        isDemoMode = isDemoMode,
                        onDemoModeClick = { toggleDemoMode() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        stepCounterSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)}
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        stepCounterSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    }

    private fun requestActivityPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), ACTIVITY_PERMISSION_CODE)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelValues = event.values.clone()
            }
            // ASSIGNMENT 1
            //   Add two more cases to this when block:
            //   - TYPE_GYROSCOPE: pass event.values to handleGyroscope()
            //   - TYPE_MAGNETIC_FIELD: save a CLONE of event.values into magnetValues
            //     (same reason as the accelerometer case), then call updateCompass()

            // Assignment 1 -- gyroscope passes raw rad/s values to the display handler
            Sensor.TYPE_GYROSCOPE -> {
                handleGyroscope(event.values)
            }

            // Assignment 1 -- magnetometer must be cloned, then triggers compass update
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetValues = event.values.clone()
                updateCompass()
            }
            Sensor.TYPE_STEP_COUNTER -> {
                handleStepCounter(event.values[0].toInt())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { }

    private fun handleAccelerometer(values: FloatArray) {
        val x = values[0] - baselineX
        val y = values[1] - baselineY
        val z = values[2] - baselineZ
        val magnitude = sqrt(x * x + y * y + z * z)
        motionText = "Motion: ${classifyMotion(magnitude)}"
    }

    // values[] are rotation rates in radians/second -- %.2f keeps 2 decimal places
    private fun handleGyroscope(values: FloatArray) {
        gyroText = "Rotation (Pitch, Roll, Yaw): ${"%.2f".format(values[0])}, ${"%.2f".format(values[1])}, ${"%.2f".format(values[2])}"
    }

    // Sensor fusion: accel provides gravity reference (tilt), magnet provides North reference
    // getRotationMatrix() returns false if either sensor hasn't fired yet -- if-guard prevents a crash
    private fun updateCompass() {
        val rm = FloatArray(9); val im = FloatArray(9)
        if (SensorManager.getRotationMatrix(rm, im, accelValues, magnetValues)) {
            val orientation = FloatArray(3); SensorManager.getOrientation(rm, orientation)
            // orientation[0] = azimuth in radians (-pi to +pi) -> degrees -> normalize 0-360
            val deg = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360) % 360
            directionText = "Direction: ${getDirection(deg)}"
        }
    }

    // North wraps around 0: both 335 deg and 5 are north
    private fun getDirection(d: Float): String = when {
        d < 45 || d >= 315 -> "N" //  and0-44 315-359
        d < 135            -> "E" // 45-135
        d < 225            -> "S" // 135-224
        else               -> "W" // 225-314
    }

    // Builds the three-layer data model (BarEntry -> BarDataSet -> BarData) and configures the chart
    // Called once when the chart is created, and again every time Refresh Charts is tapped
    private fun setupChart() {
        val entries = hourlySteps.mapIndexed { i, v -> BarEntry(i.toFloat(), v) }
        val dataSet = BarDataSet(entries, "Steps").apply {
            color = Color.CYAN  // built-in constant, already an Int
            valueTextSize = 10f
        }
        barChart.apply {
            data = BarData(dataSet)
            description.text = "Step Count per Hour"
            xAxis.position = XAxis.XAxisPosition.BOTTOM  // labels below bars
            xAxis.granularity = 1f                       // one label per bar
            axisRight.isEnabled = false                  // hide duplicate right Y axis
            setFitBars(true)  // bars fill full chart width
            animateY(800)     // bars grow upward over 800ms on load
            invalidate()      // force redraw -- required after every data change
        }
    }

    private fun handleStepCounter(totalSteps: Int) {
        if (initialStepCount == -1) {
            initialStepCount = totalSteps
        }
        sessionStepCount = totalSteps - initialStepCount
        updateHealthMetrics()
        updateChartWithSteps()
    }

    private fun updateHealthMetrics() {
        stepCountText = "Steps: $sessionStepCount"
        caloriesText =  "Calories: ${"%.1f".format(calculateCalories(sessionStepCount))} kcal"
        distanceText = "Distance: ${"%.2f".format(calculateDistance(sessionStepCount))} km"
    }
    // ASSIGNMENT 3
    //   calculateCalories: return the total calories burned for `steps`,
    //   using the per-step constant in the companion object.
    //   calculateDistance: return the distance in KILOMETERS for `steps`,
    //   using the stride-length constant. The constant is in metres, so a
    //   conversion is needed. See Hour 1: Health Metric Formulas.

    private fun calculateCalories(steps: Int): Float {
        // 0.04 kcal per step -- average for an adult at walking pace

        return steps * CALORIES_PER_STEP

    }

    private fun calculateDistance(steps: Int): Float {
        // Multiply steps by stride length in metres, then divide by 1000 to get km

        return (steps * STRIDE_LENGTH_M) / 1000f

    }


    private fun updateChartWithSteps() {
        val updatedSteps = floatArrayOf(
            sessionStepCount * 0.1f,
            sessionStepCount * 0.2f,
            sessionStepCount * 0.3f,
            sessionStepCount * 0.4f
        )

        val entries = updatedSteps.mapIndexed { i, v -> BarEntry(i.toFloat(), v) }
        val dataSet = BarDataSet(entries, "Steps").apply {
            color = Color.CYAN
            valueTextSize = 10f
        }
        barChart.apply {
            data = BarData(dataSet)
            invalidate()
        }
    }

    private fun toggleDemoMode() {
        isDemoMode = !isDemoMode
        if (isDemoMode) {
            demoHandler.post(demoRunnable)
        } else {
            demoHandler.removeCallbacks(demoRunnable)
        }
    }

    private val demoRunnable = object : Runnable {
        private var compassAngle = 0f // tracks simulated heading independently
        override fun run() {
            if (!isDemoMode) return // safety check

            // sine wave walking simulation - feeds into handleAccelerometer(), same
            // method real sensor events call. the app cant tell the difference.
            val t = System.currentTimeMillis()
            handleAccelerometer(floatArrayOf(
                (Math.sin(t / 200.0) * 3).toFloat(),
                (Math.cos(t/300.0) * 2).toFloat(),
                9.8f + (Math.sin(t / 150.0) *4).toFloat()
            ))

            // Rotate compass 5 deg per tick so direction cycles through
            compassAngle = (compassAngle + 5f) % 360f
            directionText = "Direction: ${getDirection(compassAngle)}"
            sessionStepCount += 2 // simulate 4 steps/second at 500 ms interval
            updateHealthMetrics()
            updateChartWithSteps()
            demoHandler.postDelayed(this, 500)
        }
    }


    private fun classifyMotion(magnitude: Float): String {
        return when {
            magnitude < STATIONARY_THRESHOLD -> "Stationary"
            magnitude < WALKING_THRESHOLD    -> "Walking"
            else                             -> "Jogging"
        }
    }
}

@Composable
fun FitnessTrackerScreen(
    motionText: String,
    gyroText: String,
    directionText: String,
    onRefreshChartClick: () -> Unit,
    onChartCreated: (BarChart) -> Unit,
    stepCountText: String,
    caloriesText: String,
    distanceText: String,
    isDemoMode: Boolean,
    onDemoModeClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(text = motionText, fontSize = 20.sp, modifier = Modifier.padding(bottom = 20.dp))
        Text(text = gyroText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 20.dp))
        Text(text = directionText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 20.dp))

        Text(text = stepCountText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text(text = caloriesText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text(text = distanceText, fontSize = 16.sp, modifier = Modifier.padding(bottom = 20.dp))

        AndroidView(
            factory = { ctx -> BarChart(ctx).also(onChartCreated) },
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .padding(bottom = 16.dp)
        )

        Button(onClick = onRefreshChartClick, modifier = Modifier.padding(bottom = 20.dp).align(Alignment.CenterHorizontally)) {
            Text("Refresh Charts")
        }

        Button(
            onClick = onDemoModeClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDemoMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(if (isDemoMode) "⏹  Stop Demo" else "▶  Demo Mode")
        }
    }
}
































