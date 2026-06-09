package com.example.fastfive

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SensorViewModel(application: Application): AndroidViewModel(application), SensorEventListener {

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val _tiltX = MutableStateFlow(0f)
    val tiltX: StateFlow<Float> = _tiltX
    private val _tiltY = MutableStateFlow(0f)
    val tiltY: StateFlow<Float> = _tiltY

    init {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        _tiltX.value = smooth(_tiltX.value, event.values[0])
        _tiltY.value = smooth(_tiltY.value, event.values[1])
    }

    override fun onCleared() {
        sensorManager.unregisterListener(this)
        super.onCleared()
    }

    private val alpha = 0.1f

    private fun smooth(current: Float, new: Float): Float {
        return current + alpha * (new - current)
    }
}