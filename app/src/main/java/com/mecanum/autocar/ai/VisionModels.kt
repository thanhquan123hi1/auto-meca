package com.mecanum.autocar.ai

import android.graphics.RectF

data class Detection(
    val label: String,
    val confidence: Float,
    val box: RectF,
) {
    val centerX: Float get() = box.centerX()
    val area: Float get() = box.width().coerceAtLeast(0f) * box.height().coerceAtLeast(0f)
}

data class GoalMarker(
    val id: Int,
    val box: RectF,
    val centerX: Float,
    val centerY: Float,
    val area: Float,
    val distanceCm: Float? = null,
)

data class VisionResult(
    val frameWidth: Int,
    val frameHeight: Int,
    val detections: List<Detection>,
    val marker: GoalMarker?,
    val aiFps: Float,
    val command: Char,
    val reason: String,
    val autonomous: Boolean,
)
