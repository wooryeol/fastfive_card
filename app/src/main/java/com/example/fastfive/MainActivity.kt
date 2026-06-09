package com.example.fastfive

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // PhysicsViewModel은 Application Context가 필요하므로 커스텀 Factory로 생성한다.
            val context = LocalContext.current.applicationContext as Application
            val viewModel: PhysicsViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return PhysicsViewModel(context) as T
                    }
                }
            )
            TiltBallScreen(viewModel)
        }
    }
}

/**
 * 렌더링 전용 Composable. 물리 계산은 PhysicsViewModel의 게임 루프가 담당하며,
 * 이 함수는 balls 상태를 읽어 Canvas에 그리는 역할만 한다.
 *
 * 좌표계: Canvas 원점(0,0)은 좌상단, 물리 좌표 원점은 컨테이너 중심.
 *   centerX = width/2 - ball.posX  (x축 반전: 센서 x 양수 = 오른쪽 기울기 = 왼쪽 이동)
 *   centerY = height/2 + ball.posY (y축: 물리 y 양수 = 아래쪽)
 */
@Composable
fun TiltBallScreen(viewModel: PhysicsViewModel) {
    // withFrameNanos는 Compose의 MonotonicFrameClock에 동기화되므로
    // delay(16L) 방식과 달리 vsync 신호에 맞춰 정확히 한 프레임씩 물리를 갱신한다.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                if (viewModel.containerWidth > 0f) viewModel.updatePhysics()
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val imageBitmap = ImageBitmap.imageResource(R.drawable.img_0832)

        Canvas(
            modifier = Modifier
                .size(width = 300.dp, height = 400.dp)
                .background(color = Color.LightGray, shape = RoundedCornerShape(16.dp))
                // BoxWithConstraints 대신 onSizeChanged를 사용해 불필요한 레이아웃 노드를 제거함
                .onSizeChanged { size ->
                    viewModel.setBounds(size.width.toFloat(), size.height.toFloat())
                }
        ) {
            viewModel.balls.forEach { ball ->
                val centerX = size.width / 2 - ball.posX
                val centerY = size.height / 2 + ball.posY
                val radius = ball.radius

                // 원형 클리핑 후 이미지를 그려 공 모양으로 잘라낸다
                clipPath(
                    path = Path().apply {
                        addOval(
                            Rect(
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
                        dstSize = IntSize((radius * 2).toInt(), (radius * 2).toInt()),
                        dstOffset = IntOffset((centerX - radius).toInt(), (centerY - radius).toInt())
                    )
                }
            }
        }
    }
}
