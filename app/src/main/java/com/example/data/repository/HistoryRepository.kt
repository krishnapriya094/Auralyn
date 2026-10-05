package com.example.data.repository

import com.example.data.local.SessionDao
import com.example.data.local.SessionEntity
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryRepository(private val sessionDao: SessionDao) {

    val allSessions: Flow<List<SessionEntity>> = sessionDao.getAllSessions()

    suspend fun saveSession(durationSeconds: Int, detectedObjects: List<String>) {
        val now = System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val timeLabel = "Session at " + timeFormat.format(Date(now))

        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        val durationFormatted = if (minutes > 0) {
            "Duration: $minutes min $seconds sec"
        } else {
            "Duration: $seconds sec"
        }

        val objectsStr = if (detectedObjects.isNotEmpty()) {
            detectedObjects.distinct().joinToString(", ")
        } else {
            "Surroundings Scanned"
        }

        val session = SessionEntity(
            timeLabel = timeLabel,
            dateHeader = "Today",
            durationSeconds = durationSeconds,
            durationFormatted = durationFormatted,
            detectedObjectsList = objectsStr,
            status = "COMPLETED",
            timestamp = now
        )
        sessionDao.insertSession(session)
    }

    suspend fun clearHistory() {
        sessionDao.clearAllSessions()
    }

    suspend fun prepopulateIfEmpty() {
        if (sessionDao.getSessionCount() == 0) {
            val now = System.currentTimeMillis()
            val oneDayMillis = 24 * 60 * 60 * 1000L

            sessionDao.insertSession(
                SessionEntity(
                    timeLabel = "Session at 10:35 AM",
                    dateHeader = "Today",
                    durationSeconds = 205,
                    durationFormatted = "Duration: 3 min 25 sec",
                    detectedObjectsList = "Person, Chair, Bottle, Laptop, Door",
                    status = "COMPLETED",
                    timestamp = now - 3600000L
                )
            )
            sessionDao.insertSession(
                SessionEntity(
                    timeLabel = "Session at 04:15 PM",
                    dateHeader = "Yesterday",
                    durationSeconds = 70,
                    durationFormatted = "Duration: 1 min 10 sec",
                    detectedObjectsList = "Desk, Zebra-Crossing, Door",
                    status = "COMPLETED",
                    timestamp = now - oneDayMillis
                )
            )
            sessionDao.insertSession(
                SessionEntity(
                    timeLabel = "Session at 02:40 PM",
                    dateHeader = "Last Week",
                    durationSeconds = 345,
                    durationFormatted = "Duration: 5 min 45 sec",
                    detectedObjectsList = "Backpack, Chair, Person, Smartphone, Table",
                    status = "COMPLETED",
                    timestamp = now - (oneDayMillis * 5)
                )
            )
        }
    }
}
