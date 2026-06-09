package com.example.fastfive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * 물리 시뮬레이션의 공 하나를 나타내는 모델.
 * 위치·속도를 mutableFloatStateOf로 선언해 Compose Canvas가 변경을 감지하고
 * 별도 recomposition 없이 자동으로 다시 그린다.
 */
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
