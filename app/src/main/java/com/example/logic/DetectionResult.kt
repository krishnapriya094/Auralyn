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

    val proximityDescription: String
        get() {
            val box = boundingBox ?: return "nearby"
            val height = box.height()
            val area = box.width() * height
            return when {
                height > 0.52f || area > 0.28f -> "very close"
                height > 0.28f || area > 0.10f -> "approaching"
                else -> "at distance"
            }
        }

    val isUrgentProximity: Boolean
        get() {
            val box = boundingBox ?: return false
            return box.height() > 0.52f || (box.width() * box.height()) > 0.28f
        }
}
