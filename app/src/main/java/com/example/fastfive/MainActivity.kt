package com.example.fastfive

import android.R.attr.bitmap
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap


class Ball(
    posX: Float,
    posY: Float,
    velocityX: Float,
    velocityY: Float,
    val radius: Float,
    val mass: Float
) {
    var posX by mutableFloatStateOf(posX)
    var posY by mutableFloatStateOf(posY)
    var velocityX by mutableFloatStateOf(velocityX)
    var velocityY by mutableFloatStateOf(velocityY)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current.applicationContext as Application
            val viewModel: SensorViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SensorViewModel(context) as T
                    }
                }
            )

            TiltBallScreen(viewModel, 13)
        }
    }

    @Composable
    fun TiltBallScreen(viewModel: SensorViewModel, ballCount: Int) {

        val tiltX by viewModel.tiltX.collectAsState()
        val tiltY by viewModel.tiltY.collectAsState()

        TiltBallContent(tiltX, tiltY, ballCount)

    }

    @Composable
    fun TiltBallContent(tiltX: Float, tiltY: Float, ballCount: Int) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .size(width = 300.dp, height = 400.dp)
                    .background(
                        color = Color.LightGray,
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {

                val balls = remember {
                    mutableStateListOf<Ball>().apply {

                        val spacing = 180f   // ⭐ 공 간격 (radius*2 + 여유)
                        val startX = -((ballCount - 1) * spacing) / 2

                        repeat(ballCount) { i ->

                            val x = startX + i * spacing
                            val y = 0f

                            val mass = (1..50).random().toFloat()  // ⭐ 랜덤 질량

                            add(
                                Ball(
                                    posX = x,
                                    posY = y,
                                    velocityX = 0f,
                                    velocityY = 0f,
                                    radius = 60f,
                                    mass = mass
                                )
                            )
                        }
                    }
                }

                val damping = 0.995f
                val sensitivity = 45f
                val maxSpeed = 15f

                val width = constraints.maxWidth.toFloat()
                val height = constraints.maxHeight.toFloat()

                val subSteps = 6   // ⭐ 4~8 추천

                repeat(subSteps) {

                    balls.forEach { ball ->

                        // 가속
                        val inputX = tiltX * kotlin.math.abs(tiltX)
                        val inputY = tiltY * kotlin.math.abs(tiltY)

                        ball.velocityX += inputX * sensitivity / subSteps
                        ball.velocityY += inputY * sensitivity / subSteps

                        // 마찰
                        ball.velocityX *= damping
                        ball.velocityY *= damping

                        // 속도 제한
                        ball.velocityX = ball.velocityX.coerceIn(-maxSpeed, maxSpeed)
                        ball.velocityY = ball.velocityY.coerceIn(-maxSpeed, maxSpeed)

                        // 이동 (⭐ 나눠서 이동)
                        ball.posX += ball.velocityX / subSteps
                        ball.posY += ball.velocityY / subSteps
                    }

                    // ⭐ 여기서 충돌 처리 (공 + 벽 둘 다)

                    repeat(5) {

                        // 공 충돌
                        for (i in balls.indices) {
                            for (j in i + 1 until balls.size) {

                                val b1 = balls[i]
                                val b2 = balls[j]

                                val dx = b2.posX - b1.posX
                                val dy = b2.posY - b1.posY
                                val distance = sqrt(dx * dx + dy * dy)

                                val minDist = b1.radius + b2.radius

                                if (distance < minDist && distance != 0f) {

                                    val overlap = minDist - distance
                                    val nx = dx / distance
                                    val ny = dy / distance

                                    val totalMass = b1.mass + b2.mass
                                    val ratio1 = b2.mass / totalMass
                                    val ratio2 = b1.mass / totalMass

                                    // ⭐ 추가: 겹침 제거 후 속도도 분리
                                    val separationStrength = 0.5f

                                    b1.velocityX -= nx * separationStrength
                                    b1.velocityY -= ny * separationStrength

                                    b2.velocityX += nx * separationStrength
                                    b2.velocityY += ny * separationStrength

                                    b1.posX -= nx * overlap * ratio1
                                    b1.posY -= ny * overlap * ratio1

                                    b2.posX += nx * overlap * ratio2
                                    b2.posY += ny * overlap * ratio2

                                }
                            }
                        }

                        // 벽 충돌 (⭐ 반드시 같이)
                        balls.forEach { ball ->

                            if (ball.posX < -width / 2 + ball.radius) {
                                ball.posX = -width / 2 + ball.radius
                                ball.velocityX *= -0.85f
                                ball.velocityY *= 0.98f  // ⭐ 수직 방향 안정화
                            }

                            if (ball.posX > width / 2 - ball.radius) {
                                ball.posX = width / 2 - ball.radius
                                ball.velocityX *= -0.85f
                                ball.velocityY *= 0.98f  // ⭐ 수직 방향 안정화
                            }

                            if (ball.posY < -height / 2 + ball.radius) {
                                ball.posY = -height / 2 + ball.radius
                                ball.velocityY *= -0.85f
                                ball.velocityX *= 0.98f  // ⭐ 수직 방향 안정화
                            }

                            if (ball.posY > height / 2 - ball.radius) {
                                ball.posY = height / 2 - ball.radius
                                ball.velocityY *= -0.85f
                                ball.velocityX *= 0.98f  // ⭐ 수직 방향 안정화
                            }
                        }
                    }
                }

                val imageBitmap = ImageBitmap.imageResource(R.drawable.img_0832)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    balls.forEach { ball ->
                        val centerX = size.width / 2 - ball.posX
                        val centerY = size.height / 2 + ball.posY

                        val radius = ball.radius

                        clipPath(
                            path = androidx.compose.ui.graphics.Path().apply {
                                addOval(
                                    androidx.compose.ui.geometry.Rect(
                                        left = centerX - radius,
                                        top = centerY - radius,
                                        right = centerX + radius,
                                        bottom = centerY + radius
                                    )
                                )
                            }
                        ) {
                            drawImage(
                                image = imageBitmap,
                                dstSize = androidx.compose.ui.unit.IntSize(
                                    (radius * 2).toInt(),
                                    (radius * 2).toInt()
                                ),
                                dstOffset = androidx.compose.ui.unit.IntOffset(
                                    (centerX - radius).toInt(),
                                    (centerY - radius).toInt()
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}