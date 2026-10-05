package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "detection_sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timeLabel: String,
    val dateHeader: String,
    val durationSeconds: Int,
    val durationFormatted: String,
    val detectedObjectsList: String, // Comma-separated list e.g. "Person, Door, Zebra-Crossing"
    val status: String = "COMPLETED",
    val timestamp: Long = System.currentTimeMillis()
)
