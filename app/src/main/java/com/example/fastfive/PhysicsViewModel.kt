package com.example.fastfive

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 가속도 센서 읽기와 물리 시뮬레이션 게임 루프를 함께 담당하는 ViewModel.
 *
 * 스레드 분리 구조:
 *   - 센서 스레드 → tiltX / tiltY 기록 (@Volatile)
 *   - Dispatchers.Main 코루틴 → 16ms 고정 주기로 물리 계산 후 balls 상태 갱신
 *   - Compose → balls 상태를 관찰해 Canvas 렌더링
 *
 * 이전 구현은 센서 이벤트마다 Composable recomposition을 유발했으며,
 * 이로 인해 물리 계산이 불규칙한 타이밍에 실행되어 심각한 프레임 드랍이 발생했다.
 */
class PhysicsViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    companion object {
        private const val BALL_COUNT = 13
        private const val BALL_RADIUS = 60f
        private const val BALL_SPACING = 180f   // 공 초기 배치 간격 (px)

        private const val DAMPING = 0.992f          // 매 프레임 속도 감쇠 비율 (1.0 = 감쇠 없음)
        private const val SENSITIVITY = 60f         // 기울기 → 가속도 변환 배율
        private const val MAX_SPEED = 15f           // 프레임당 최대 이동 속도 (px)

        // 한 프레임을 여러 서브스텝으로 나눠 계산해 터널링(공이 벽을 뚫는 현상) 방지
        private const val SUB_STEPS = 8
        // 서브스텝 내 충돌 반복 횟수 — 많을수록 정확하지만 연산량 증가
        private const val COLLISION_ITERATIONS = 6

        private const val SEPARATION_STRENGTH = 0.3f   // 겹침 해소 시 속도 보정 강도
        private const val WALL_BOUNCE = -0.72f          // 벽 반사 계수 (음수 = 방향 반전 + 에너지 손실)
        private const val WALL_FRICTION = 0.99f         // 벽 접촉 시 수직 방향 마찰 계수

        // 저주파 필터(Low-pass filter) 계수: 값이 작을수록 센서 노이즈를 더 많이 걸러낸다
        private const val SENSOR_ALPHA = 0.1f

    }

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // 센서 스레드(쓰기)와 메인 스레드(읽기) 간 가시성 보장을 위해 @Volatile 사용
    @Volatile private var tiltX = 0f
    @Volatile private var tiltY = 0f

    // SnapshotStateList: 리스트 내 Ball 필드 변경을 Compose가 감지해 Canvas를 자동 재드로우
    val balls = mutableStateListOf<Ball>()

    var containerWidth = 0f
        private set
    private var containerHeight = 0f

    init {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        initBalls()
    }

    /** Canvas의 실제 픽셀 크기를 전달받아 벽 충돌 경계 계산에 사용한다. */
    fun setBounds(width: Float, height: Float) {
        containerWidth = width
        containerHeight = height
    }

    /** 공을 가로 방향으로 균등 간격 배치해 초기화한다. */
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

    /**
     * 한 프레임의 물리를 SUB_STEPS개 서브스텝으로 분할해 계산한다.
     * 서브스텝마다 충돌을 COLLISION_ITERATIONS회 반복해 겹침이 누적되는 것을 방지한다.
     */
    fun updatePhysics() {
        repeat(SUB_STEPS) {
            applyForces()
            repeat(COLLISION_ITERATIONS) {
                resolveBallCollisions()
                resolveWallCollisions()
            }
        }
    }

    /** 기울기를 가속도로 변환해 각 공의 속도·위치를 갱신한다. */
    private fun applyForces() {
        // tiltX * abs(tiltX): 제곱 스케일링으로 작은 기울기는 둔하게, 큰 기울기는 민감하게 반응
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

    /**
     * 공 쌍 간 충돌을 감지하고 겹침을 해소한다.
     * 질량 비율에 따라 위치를 보정해 무거운 공이 덜 밀리도록 한다.
     */
    private fun resolveBallCollisions() {
        for (i in balls.indices) {
            for (j in i + 1 until balls.size) {
                val b1 = balls[i]
                val b2 = balls[j]
                val dx = b2.posX - b1.posX
                val dy = b2.posY - b1.posY
                val distance = sqrt(dx * dx + dy * dy)
                val minDist = b1.radius + b2.radius
                // distance == 0f: 두 공이 완전히 겹쳤을 때 NaN 방지
                if (distance >= minDist || distance == 0f) continue

                val overlap = minDist - distance
                val nx = dx / distance   // 충돌 법선 벡터 (단위 벡터)
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

    /**
     * 공이 컨테이너 경계를 벗어나면 위치를 클램프하고 속도를 반사시킨다.
     * 좌표 원점은 컨테이너 중심이며, halfW/halfH가 각 축의 경계값이다.
     */
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

    /** 센서 스레드에서 호출됨. 저주파 필터로 노이즈를 줄여 tiltX/tiltY에 기록한다. */
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
