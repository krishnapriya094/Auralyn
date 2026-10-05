package com.example.logic

import android.graphics.RectF

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF? = null
) {
    val positionDescription: String
        get() {
            val box = boundingBox ?: return "in view"
            val centerX = (box.left + box.right) / 2f
            return when {
                centerX < 0.35f -> "on your left"
                centerX > 0.65f -> "on your right"
                else -> "ahead"
            }
        }
}
