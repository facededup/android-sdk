package ng.facededup.sdk

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Samples motion/orientation/proximity sensors DURING capture to feed the anti-fraud
 * metadata (DeviceMovementDetected, DevicePositions, DeviceOrientation, ProximitySensor).
 * Start at capture begin, stop at submit; read the summary into [DeviceMetadata].
 */
internal class MovementMonitor(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var moved = false
    private var minPitch = Float.MAX_VALUE; private var maxPitch = -Float.MAX_VALUE
    private var minRoll = Float.MAX_VALUE; private var maxRoll = -Float.MAX_VALUE
    private var proximityNear = false
    private var proximitySeen = false
    private var samples = 0

    fun start() {
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val g = sqrt(x * x + y * y + z * z)
                // |g - 9.81| spikes when the phone is being moved/shaken (vs held steady).
                if (abs(g - SensorManager.GRAVITY_EARTH) > 1.6f) moved = true
                // pitch/roll (deg) — tilt range over the capture = DevicePositions.
                val pitch = Math.toDegrees(Math.atan2(y.toDouble(), z.toDouble())).toFloat()
                val roll = Math.toDegrees(Math.atan2(x.toDouble(), z.toDouble())).toFloat()
                minPitch = minOf(minPitch, pitch); maxPitch = maxOf(maxPitch, pitch)
                minRoll = minOf(minRoll, roll); maxRoll = maxOf(maxRoll, roll)
                samples++
            }
            Sensor.TYPE_PROXIMITY -> {
                proximitySeen = true
                if (e.values[0] < (e.sensor.maximumRange.takeIf { it > 0 } ?: 5f)) proximityNear = true
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** Summary for the metadata payload. */
    fun summary(): Map<String, Any?> = mapOf(
        "DeviceMovementDetected" to moved,
        "DevicePositions" to if (samples == 0) null else mapOf(
            "pitch_min" to round1(minPitch), "pitch_max" to round1(maxPitch),
            "roll_min" to round1(minRoll), "roll_max" to round1(maxRoll),
            "samples" to samples,
        ),
        "ProximitySensor" to if (!proximitySeen) "unavailable" else if (proximityNear) "near" else "far",
    )

    private fun round1(v: Float) = if (v == Float.MAX_VALUE || v == -Float.MAX_VALUE) null else Math.round(v * 10) / 10.0
}
