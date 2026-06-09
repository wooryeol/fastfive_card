package com.example.fastfive

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

class PhysicsViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    companion object {
        private const val BALL_COUNT = 13
        private const val BALL_RADIUS = 60f
        private const val BALL_SPACING = 180f
        private const val DAMPING = 0.995f
        private const val SENSITIVITY = 45f
        private const val MAX_SPEED = 15f
        private const val SUB_STEPS = 6
        private const val COLLISION_ITERATIONS = 5
        private const val SEPARATION_STRENGTH = 0.5f
        private const val WALL_BOUNCE = -0.85f
        private const val WALL_FRICTION = 0.98f
        private const val SENSOR_ALPHA = 0.1f
        private const val FRAME_DELAY_MS = 16L
    }

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile private var tiltX = 0f
    @Volatile private var tiltY = 0f

    val balls = mutableStateListOf<Ball>()

    private var containerWidth = 0f
    private var containerHeight = 0f

    init {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        initBalls()
        startGameLoop()
    }

    fun setBounds(width: Float, height: Float) {
        containerWidth = width
        containerHeight = height
    }

    private fun initBalls() {
        val startX = -((BALL_COUNT - 1) * BALL_SPACING) / 2
        repeat(BALL_COUNT) { i ->
            balls.add(
                Ball(
                    posX = startX + i * BALL_SPACING,
                    posY = 0f,
                    velocityX = 0f,
                    velocityY = 0f,
                    radius = BALL_RADIUS,
                    mass = (1..50).random().toFloat()
                )
            )
        }
    }

    private fun startGameLoop() {
        viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                if (containerWidth > 0f) updatePhysics()
                delay(FRAME_DELAY_MS)
            }
        }
    }

    private fun updatePhysics() {
        repeat(SUB_STEPS) {
            applyForces()
            repeat(COLLISION_ITERATIONS) {
                resolveBallCollisions()
                resolveWallCollisions()
            }
        }
    }

    private fun applyForces() {
        val inputX = tiltX * abs(tiltX)
        val inputY = tiltY * abs(tiltY)
        balls.forEach { ball ->
            ball.velocityX += inputX * SENSITIVITY / SUB_STEPS
            ball.velocityY += inputY * SENSITIVITY / SUB_STEPS
            ball.velocityX = (ball.velocityX * DAMPING).coerceIn(-MAX_SPEED, MAX_SPEED)
            ball.velocityY = (ball.velocityY * DAMPING).coerceIn(-MAX_SPEED, MAX_SPEED)
            ball.posX += ball.velocityX / SUB_STEPS
            ball.posY += ball.velocityY / SUB_STEPS
        }
    }

    private fun resolveBallCollisions() {
        for (i in balls.indices) {
            for (j in i + 1 until balls.size) {
                val b1 = balls[i]
                val b2 = balls[j]
                val dx = b2.posX - b1.posX
                val dy = b2.posY - b1.posY
                val distance = sqrt(dx * dx + dy * dy)
                val minDist = b1.radius + b2.radius
                if (distance >= minDist || distance == 0f) continue

                val overlap = minDist - distance
                val nx = dx / distance
                val ny = dy / distance
                val totalMass = b1.mass + b2.mass
                val ratio1 = b2.mass / totalMass
                val ratio2 = b1.mass / totalMass

                b1.velocityX -= nx * SEPARATION_STRENGTH
                b1.velocityY -= ny * SEPARATION_STRENGTH
                b2.velocityX += nx * SEPARATION_STRENGTH
                b2.velocityY += ny * SEPARATION_STRENGTH
                b1.posX -= nx * overlap * ratio1
                b1.posY -= ny * overlap * ratio1
                b2.posX += nx * overlap * ratio2
                b2.posY += ny * overlap * ratio2
            }
        }
    }

    private fun resolveWallCollisions() {
        val halfW = containerWidth / 2
        val halfH = containerHeight / 2
        balls.forEach { ball ->
            if (ball.posX < -halfW + ball.radius) {
                ball.posX = -halfW + ball.radius
                ball.velocityX *= WALL_BOUNCE
                ball.velocityY *= WALL_FRICTION
            }
            if (ball.posX > halfW - ball.radius) {
                ball.posX = halfW - ball.radius
                ball.velocityX *= WALL_BOUNCE
                ball.velocityY *= WALL_FRICTION
            }
            if (ball.posY < -halfH + ball.radius) {
                ball.posY = -halfH + ball.radius
                ball.velocityY *= WALL_BOUNCE
                ball.velocityX *= WALL_FRICTION
            }
            if (ball.posY > halfH - ball.radius) {
                ball.posY = halfH - ball.radius
                ball.velocityY *= WALL_BOUNCE
                ball.velocityX *= WALL_FRICTION
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        tiltX = tiltX + SENSOR_ALPHA * (event.values[0] - tiltX)
        tiltY = tiltY + SENSOR_ALPHA * (event.values[1] - tiltY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onCleared() {
        sensorManager.unregisterListener(this)
        super.onCleared()
    }
}
