package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AuralynDatabase
import com.example.data.local.SessionEntity
import com.example.data.repository.HistoryRepository
import com.example.logic.DetectionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var database: AuralynDatabase
    private lateinit var historyRepository: HistoryRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AuralynDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        historyRepository = HistoryRepository(database.sessionDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Auralyn", appName)
    }

    @Test
    fun `save and retrieve detection sessions in Room database`() = runBlocking {
        historyRepository.saveSession(
            durationSeconds = 125,
            detectedObjects = listOf("Zebra-Crossing", "Door", "Chair")
        )

        val sessions = historyRepository.allSessions.first()
        assertEquals(1, sessions.size)
        assertEquals("Duration: 2 min 5 sec", sessions[0].durationFormatted)
        assertTrue(sessions[0].detectedObjectsList.contains("Zebra-Crossing"))
        assertTrue(sessions[0].detectedObjectsList.contains("Door"))
    }

    @Test
    fun `clear history empties database`() = runBlocking {
        historyRepository.saveSession(durationSeconds = 30, detectedObjects = listOf("Door"))
        assertEquals(1, historyRepository.allSessions.first().size)

        historyRepository.clearHistory()
        assertEquals(0, historyRepository.allSessions.first().size)
    }
}
