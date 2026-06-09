package com.example.fastfive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

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
