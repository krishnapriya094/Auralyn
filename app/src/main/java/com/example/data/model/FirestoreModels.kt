package com.example.data.model

import com.google.firebase.Timestamp

data class DetectionEventDto(
    val id: String = "",
    val userId: String = "",
    val label: String = "",
    val confidence: Double = 0.0,
    val location: String = "",
    val timestamp: Timestamp? = null
)

data class DetectionSessionDto(
    val id: String = "",
    val userId: String = "",
    val timeLabel: String = "",
    val dateHeader: String = "",
    val durationSeconds: Int = 0,
    val durationFormatted: String = "",
    val detectedObjectsList: String = "",
    val objectsCount: Int = 0,
    val status: String = "COMPLETED",
    val timestamp: Long = 0L
)

data class KnownPersonDto(
    val id: String = "",
    val userId: String = "",
    val name: String = "",
    val relationship: String = "",
    val faceFeatures: String = "",
    val thumbnailBase64: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
