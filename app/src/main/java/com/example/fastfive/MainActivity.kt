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

@Composable
fun TiltBallScreen(viewModel: PhysicsViewModel) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val imageBitmap = ImageBitmap.imageResource(R.drawable.img_0832)

        Canvas(
            modifier = Modifier
                .size(width = 300.dp, height = 400.dp)
                .background(color = Color.LightGray, shape = RoundedCornerShape(16.dp))
                .onSizeChanged { size ->
                    viewModel.setBounds(size.width.toFloat(), size.height.toFloat())
                }
        ) {
            viewModel.balls.forEach { ball ->
                val centerX = size.width / 2 - ball.posX
                val centerY = size.height / 2 + ball.posY
                val radius = ball.radius

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
