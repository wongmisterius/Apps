package com.photo3d.spatial.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Traduce la orientación del teléfono (sensor ROTATION_VECTOR, con fallback a
 * ACCELEROMETER) en un desplazamiento [-1, 1] en X e Y, usado para mover la
 * cámara virtual y generar el paralaje al inclinar el teléfono.
 */
class TiltSensorManager(context: Context, private val onTilt: (x: Float, y: Float) -> Unit) :
    SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        val sensor = rotationSensor ?: accelSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                // orientation[1] = pitch (adelante/atrás), orientation[2] = roll (izq/der)
                val roll = orientation[2]
                val pitch = orientation[1]
                val x = (roll / MAX_ANGLE_RAD).coerceIn(-1f, 1f)
                val y = (pitch / MAX_ANGLE_RAD).coerceIn(-1f, 1f)
                onTilt(x, y)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Fallback simple si no hay ROTATION_VECTOR disponible.
                val x = (-event.values[0] / GRAVITY).coerceIn(-1f, 1f)
                val y = (event.values[1] / GRAVITY - 1f).coerceIn(-1f, 1f)
                onTilt(x, y)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val GRAVITY = 9.81f
        const val MAX_ANGLE_RAD = 0.5f // ~28 grados para llegar al desplazamiento máximo
    }
}
