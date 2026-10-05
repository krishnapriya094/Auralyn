package com.example.logic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoiceCommandManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "VoiceCommandManager"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningRequested = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.w(TAG, "Speech recognition is not available on this device")
                    onError("Speech recognition not available")
                    return@post
                }

                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            _isListening.value = true
                        }

                        override fun onBeginningOfSpeech() {}

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            _isListening.value = false
                        }

                        override fun onError(error: Int) {
                            _isListening.value = false
                            val message = getErrorMessage(error)
                            onError(message)

                            if (isListeningRequested) {
                                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                                    speechRecognizer?.cancel()
                                }
                                mainHandler.postDelayed({
                                    if (isListeningRequested) {
                                        startListening()
                                    }
                                }, 600)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            _isListening.value = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val firstMatch = matches?.firstOrNull()
                            if (!firstMatch.isNullOrBlank()) {
                                _lastRecognizedText.value = firstMatch
                                onResult(firstMatch)
                            }

                            if (isListeningRequested) {
                                mainHandler.postDelayed({
                                    if (isListeningRequested) {
                                        startListening()
                                    }
                                }, 400)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            matches?.firstOrNull()?.let {
                                _lastRecognizedText.value = it
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing SpeechRecognizer: ${e.message}", e)
                onError("Failed to init recognizer: ${e.message}")
            }
        }
    }

    fun startListening() {
        isListeningRequested = true
        mainHandler.post {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                _isListening.value = false
                onError("Start listening error: ${e.message}")
            }
        }
    }

    fun stopListening() {
        isListeningRequested = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                _isListening.value = false
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun destroy() {
        isListeningRequested = false
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
                _isListening.value = false
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun getErrorMessage(code: Int): String {
        return when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No voice match found"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Recognition error ($code)"
        }
    }
}
