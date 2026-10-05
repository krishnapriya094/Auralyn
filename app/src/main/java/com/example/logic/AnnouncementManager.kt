package com.example.logic

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.data.repository.AuralynSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class AnnouncementManager(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AnnouncementManager"
    }

    private var tts: TextToSpeech? = null
    private val announcedObjects = mutableMapOf<String, Long>()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }
            })
            _isTtsReady.value = true
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "Failed to initialize TTS: status=$status")
        }
    }

    fun announce(detections: List<DetectionResult>, settings: AuralynSettings) {
        if (!settings.voiceAssistantEnabled) return
        if (!_isTtsReady.value) return

        // 1. Configure speech rate
        val rate = when (settings.speechSpeed) {
            "slow" -> 0.75f
            "fast" -> 1.5f
            else -> 1.0f
        }
        tts?.setSpeechRate(rate)

        // 2. Determine confidence cutoff
        val minConfidence = when (settings.detectionConfidence) {
            "low" -> 0.25f
            "high" -> 0.60f
            else -> 0.35f
        }

        // 3. Filter detections
        val now = System.currentTimeMillis()
        val repeatIntervalMs = settings.announcementDelaySeconds * 1000L

        val toAnnounce = detections
            .filter { it.confidence >= minConfidence }
            .filter { detection ->
                // Check if user enabled this category in preferences (case-insensitive lookup)
                val isEnabled = settings.enabledObjects.entries.firstOrNull { 
                    it.key.equals(detection.label, ignoreCase = true) 
                }?.value ?: true
                isEnabled
            }
            .filter { detection ->
                val lastTime = announcedObjects[detection.label] ?: 0L
                (now - lastTime) > repeatIntervalMs
            }

        if (toAnnounce.isNotEmpty()) {
            val phrases = toAnnounce.map { formatLabelForSpeech(it.label) }
            val message = phrases.distinct().joinToString(", ") + " detected"
            speak(message)

            if (settings.hapticFeedback) {
                triggerHaptic()
            }

            toAnnounce.forEach {
                announcedObjects[it.label] = now
            }
        }
    }

    private fun formatLabelForSpeech(label: String): String {
        return when (label.lowercase()) {
            "chair_fail" -> "occupied chair"
            "chair_success" -> "available chair"
            "zebra-crossing" -> "zebra crossing"
            else -> label
        }
    }

    fun speak(message: String) {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "auralyn_${System.currentTimeMillis()}")
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            // Haptic might not be supported or allowed
        }
    }

    fun stopAnnouncing() {
        tts?.stop()
        _isSpeaking.value = false
        announcedObjects.clear()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
