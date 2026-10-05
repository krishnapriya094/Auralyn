package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuralynSettings(
    val voiceAssistantEnabled: Boolean = true,
    val speechSpeed: String = "normal", // "slow", "normal", "fast"
    val announcementDelaySeconds: Int = 5, // 3, 5, 10
    val detectionConfidence: String = "medium", // "low", "medium", "high"
    val highContrast: Boolean = false,
    val hapticFeedback: Boolean = true,
    val enabledObjects: Map<String, Boolean> = mapOf(
        "Chair" to true,
        "Bottle" to true,
        "Table" to true,
        "Book" to true,
        "Box" to true,
        "Lamp" to true,
        "Door" to true,
        "Window" to true,
        "Person" to true
    )
)

class PreferencesRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auralyn_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AuralynSettings> = _settings.asStateFlow()

    private fun loadSettings(): AuralynSettings {
        val voiceEnabled = prefs.getBoolean("voice_assistant_enabled", true)
        val speechSpeed = prefs.getString("speech_speed", "normal") ?: "normal"
        val delay = prefs.getInt("announcement_delay", 5)
        val confidence = prefs.getString("detection_confidence", "medium") ?: "medium"
        val highContrast = prefs.getBoolean("high_contrast", false)
        val haptic = prefs.getBoolean("haptic_feedback", true)

        val defaultObjects = listOf("Chair", "Bottle", "Table", "Book", "Box", "Lamp", "Door", "Window", "Person")
        val objMap = mutableMapOf<String, Boolean>()
        for (obj in defaultObjects) {
            objMap[obj] = prefs.getBoolean("filter_$obj", true)
        }

        return AuralynSettings(
            voiceAssistantEnabled = voiceEnabled,
            speechSpeed = speechSpeed,
            announcementDelaySeconds = delay,
            detectionConfidence = confidence,
            highContrast = highContrast,
            hapticFeedback = haptic,
            enabledObjects = objMap
        )
    }

    fun setVoiceAssistantEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("voice_assistant_enabled", enabled).apply()
        _settings.value = _settings.value.copy(voiceAssistantEnabled = enabled)
    }

    fun setSpeechSpeed(speed: String) {
        prefs.edit().putString("speech_speed", speed).apply()
        _settings.value = _settings.value.copy(speechSpeed = speed)
    }

    fun setAnnouncementDelay(seconds: Int) {
        prefs.edit().putInt("announcement_delay", seconds).apply()
        _settings.value = _settings.value.copy(announcementDelaySeconds = seconds)
    }

    fun setDetectionConfidence(confidence: String) {
        prefs.edit().putString("detection_confidence", confidence).apply()
        _settings.value = _settings.value.copy(detectionConfidence = confidence)
    }

    fun setHighContrast(enabled: Boolean) {
        prefs.edit().putBoolean("high_contrast", enabled).apply()
        _settings.value = _settings.value.copy(highContrast = enabled)
    }

    fun setHapticFeedback(enabled: Boolean) {
        prefs.edit().putBoolean("haptic_feedback", enabled).apply()
        _settings.value = _settings.value.copy(hapticFeedback = enabled)
    }

    fun setObjectFilter(obj: String, enabled: Boolean) {
        prefs.edit().putBoolean("filter_$obj", enabled).apply()
        val updated = _settings.value.enabledObjects.toMutableMap()
        updated[obj] = enabled
        _settings.value = _settings.value.copy(enabledObjects = updated)
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _settings.value = AuralynSettings()
    }
}
