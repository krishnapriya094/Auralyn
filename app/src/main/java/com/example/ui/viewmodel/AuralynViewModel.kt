package com.example.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.auth.AuthManager
import com.example.data.local.AuralynDatabase
import com.example.data.local.KnownPersonEntity
import com.example.data.local.SessionEntity
import com.example.data.local.toEntity
import com.example.data.model.DetectionEventDto
import com.example.data.model.DetectionSessionDto
import com.example.data.model.KnownPersonDto
import com.example.data.repository.AuralynSettings
import com.example.data.repository.FirestoreHistoryRepository
import com.example.data.repository.HistoryRepository
import com.example.data.repository.PreferencesRepository
import com.example.logic.AnnouncementManager
import com.example.logic.DetectionManager
import com.example.logic.DetectionResult
import com.example.logic.VoiceCommandManager
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ScreenDestination {
    WELCOME,
    DETECTION,
    HISTORY,
    FAMILIAR_FACES,
    SETTINGS,
    PREFERENCES,
    HELP
}

enum class VisionFeedMode(val displayName: String) {
    VIDEO_CAPTURE("Video Capture"),
    LIVE_CAMERA("Live Camera")
}

class AuralynViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AuralynDatabase.getDatabase(application)
    private val historyRepository = HistoryRepository(database.sessionDao())
    val preferencesRepository = PreferencesRepository(application)
    private val knownPersonDao = database.knownPersonDao()

    val authManager = AuthManager(application)
    val firestoreHistoryRepository = FirestoreHistoryRepository(application)

    val detectionManager = DetectionManager(application)
    val announcementManager = AnnouncementManager(application)

    private var voiceCommandManager: VoiceCommandManager? = null

    // Screen navigation
    private val _currentScreen = MutableStateFlow(ScreenDestination.WELCOME)
    val currentScreen: StateFlow<ScreenDestination> = _currentScreen.asStateFlow()

    // Settings
    val settings: StateFlow<AuralynSettings> = preferencesRepository.settings

    // Auth state
    val currentUser: StateFlow<FirebaseUser?> = authManager.currentUser
    val isAuthenticating: StateFlow<Boolean> = authManager.isAuthenticating
    val authError: StateFlow<String?> = authManager.authError

    // Local History (Room)
    val historySessions: StateFlow<List<SessionEntity>> = historyRepository.allSessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Cloud History (Firestore)
    @OptIn(ExperimentalCoroutinesApi::class)
    val firestoreSessions: StateFlow<List<DetectionSessionDto>> = authManager.currentUser
        .flatMapLatest { user ->
            if (user != null) {
                firestoreHistoryRepository.observeSessions()
            } else {
                flowOf(emptyList())
            }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live Detection Events (Firestore)
    @OptIn(ExperimentalCoroutinesApi::class)
    val firestoreEvents: StateFlow<List<DetectionEventDto>> = authManager.currentUser
        .flatMapLatest { user ->
            if (user != null) {
                firestoreHistoryRepository.observeRecentDetectionEvents()
            } else {
                flowOf(emptyList())
            }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Local Room database flow of known persons (always works offline & unauthenticated)
    private val localKnownPeopleFlow: Flow<List<KnownPersonDto>> = knownPersonDao.observeAll()
        .map { list -> list.map { it.toDto() } }

    // Enrolled Familiar People (Room DB offline-first + Firestore cloud sync)
    @OptIn(ExperimentalCoroutinesApi::class)
    val knownPeople: StateFlow<List<KnownPersonDto>> = authManager.currentUser
        .flatMapLatest { user ->
            if (user != null) {
                combine(
                    localKnownPeopleFlow,
                    firestoreHistoryRepository.observeKnownPeople()
                ) { localList, cloudList ->
                    val mergedMap = linkedMapOf<String, KnownPersonDto>()
                    localList.forEach { mergedMap[it.name.lowercase().trim()] = it }
                    cloudList.forEach { cloudPerson ->
                        mergedMap[cloudPerson.name.lowercase().trim()] = cloudPerson
                        // Sync cloud person to local Room cache
                        viewModelScope.launch(Dispatchers.IO) {
                            knownPersonDao.insert(cloudPerson.toEntity())
                        }
                    }
                    mergedMap.values.toList()
                }
            } else {
                localKnownPeopleFlow
            }
        }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Detection state
    private val _isDetectionActive = MutableStateFlow(false)
    val isDetectionActive: StateFlow<Boolean> = _isDetectionActive.asStateFlow()

    private val _activeSessionDurationSeconds = MutableStateFlow(0)
    val activeSessionDurationSeconds: StateFlow<Int> = _activeSessionDurationSeconds.asStateFlow()

    private val _currentDetections = MutableStateFlow<List<DetectionResult>>(emptyList())
    val currentDetections: StateFlow<List<DetectionResult>> = _currentDetections.asStateFlow()

    private val sessionDetectedObjects = mutableSetOf<String>()

    private val _visionFeedMode = MutableStateFlow(VisionFeedMode.VIDEO_CAPTURE)
    val visionFeedMode: StateFlow<VisionFeedMode> = _visionFeedMode.asStateFlow()

    private val _videoUri = MutableStateFlow<android.net.Uri?>(null)
    val videoUri: StateFlow<android.net.Uri?> = _videoUri.asStateFlow()

    private val _videoTitle = MutableStateFlow("Sample Video Feed")
    val videoTitle: StateFlow<String> = _videoTitle.asStateFlow()

    private val _isVideoPlaying = MutableStateFlow(true)
    val isVideoPlaying: StateFlow<Boolean> = _isVideoPlaying.asStateFlow()

    // Voice recognition & prompt status
    private val _voiceNotice = MutableStateFlow("Ready")
    val voiceNotice: StateFlow<String> = _voiceNotice.asStateFlow()

    private val _isVoiceListening = MutableStateFlow(false)
    val isVoiceListening: StateFlow<Boolean> = _isVoiceListening.asStateFlow()

    // Flashlight / Torch for low-light object & face detection enhancement
    private val _isTorchEnabled = MutableStateFlow(false)
    val isTorchEnabled: StateFlow<Boolean> = _isTorchEnabled.asStateFlow()

    // Camera Lens Selector (Front / Selfie vs Back)
    private val _useFrontCamera = MutableStateFlow(false)
    val useFrontCamera: StateFlow<Boolean> = _useFrontCamera.asStateFlow()

    // Interactive Photo Test Scanner (for instant testing of enrolled faces on any snapshot/photo)
    private val _testPhotoBitmap = MutableStateFlow<Bitmap?>(null)
    val testPhotoBitmap: StateFlow<Bitmap?> = _testPhotoBitmap.asStateFlow()

    fun toggleTorch() {
        val next = !_isTorchEnabled.value
        _isTorchEnabled.value = next
        announcementManager.speak(if (next) "Flashlight on" else "Flashlight off")
    }

    fun setTorch(enabled: Boolean) {
        _isTorchEnabled.value = enabled
        announcementManager.speak(if (enabled) "Flashlight on" else "Flashlight off")
    }

    fun switchCamera() {
        val next = !_useFrontCamera.value
        _useFrontCamera.value = next
        announcementManager.speak(if (next) "Switched to front selfie camera" else "Switched to back camera")
    }

    fun processTestPhoto(bitmap: Bitmap) {
        _testPhotoBitmap.value = bitmap
        _isDetectionActive.value = true
        viewModelScope.launch {
            detectionManager.processImage(bitmap, minConfidence = 0.25f)
        }
    }

    fun clearTestPhoto() {
        _testPhotoBitmap.value = null
        _currentDetections.value = emptyList()
    }

    private var sessionTimerJob: Job? = null
    private var simulationLoopJob: Job? = null

    init {
        // Attempt silent auto-sign in on startup
        authManager.attemptAutoSignIn(viewModelScope)

        // Pre-populate dummy history if empty
        viewModelScope.launch {
            historyRepository.prepopulateIfEmpty()
        }

        // Listen to detection results and push each detected object to Firebase in real time
        viewModelScope.launch {
            detectionManager.results.collect { results ->
                if (_isDetectionActive.value) {
                    _currentDetections.value = results
                    results.forEach { detection ->
                        sessionDetectedObjects.add(detection.label)
                        val center = detection.boundingBox?.centerX() ?: 0.5f
                        val location = when {
                            center < 0.35f -> "Left"
                            center > 0.65f -> "Right"
                            else -> "Ahead"
                        }
                        // Push individual detected object/person to Firebase Firestore
                        firestoreHistoryRepository.logDetectionEvent(
                            label = detection.label,
                            confidence = detection.confidence,
                            location = location
                        )
                    }
                    announcementManager.announce(results, settings.value)
                }
            }
        }

        // Sync known familiar faces to detection manager
        viewModelScope.launch {
            knownPeople.collect { list ->
                detectionManager.updateEnrolledPeople(list)
            }
        }

        // Initialize voice commands
        initVoiceCommands()
    }

    suspend fun enrollKnownPerson(bitmap: Bitmap, name: String, relationship: String): Result<KnownPersonDto> {
        val result = withContext(Dispatchers.Default) {
            detectionManager.faceRecognitionManager.enrollFace(bitmap, name, relationship)
        }
        result.onSuccess { person ->
            // 1. Immediately insert into local Room database (guaranteed persistence offline/online)
            withContext(Dispatchers.IO) {
                knownPersonDao.insert(person.toEntity())
            }
            // 2. Sync to Firebase Firestore if signed in
            firestoreHistoryRepository.saveKnownPerson(person)

            // 3. Immediately update DetectionManager so live stream detects them without restart
            val currentList = knownPeople.value.toMutableList()
            if (currentList.none { it.name.equals(person.name, ignoreCase = true) }) {
                currentList.add(0, person)
                detectionManager.updateEnrolledPeople(currentList)
            }

            announcementManager.speak("Saved ${person.name} as a familiar face.")
        }
        return result
    }

    fun deleteKnownPerson(personId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            knownPersonDao.deleteById(personId)
            firestoreHistoryRepository.deleteKnownPerson(personId)
        }
    }

    private fun initVoiceCommands() {
        voiceCommandManager = VoiceCommandManager(
            getApplication(),
            onResult = { command ->
                handleVoiceCommand(command)
            },
            onError = { error ->
                _voiceNotice.value = "Voice: $error"
            }
        )
    }

    fun navigateTo(screen: ScreenDestination) {
        _currentScreen.value = screen
    }

    fun startDetection() {
        if (_isDetectionActive.value) return
        _isDetectionActive.value = true
        _activeSessionDurationSeconds.value = 0
        sessionDetectedObjects.clear()
        _voiceNotice.value = "Detection Active"

        announcementManager.speak("Detection started. Scanning surroundings.")

        // Start session timer
        sessionTimerJob?.cancel()
        sessionTimerJob = viewModelScope.launch {
            while (isActive && _isDetectionActive.value) {
                delay(1000)
                _activeSessionDurationSeconds.value += 1
            }
        }
    }

    fun stopDetection() {
        if (!_isDetectionActive.value) return
        _isDetectionActive.value = false
        sessionTimerJob?.cancel()

        val duration = _activeSessionDurationSeconds.value
        val detected = sessionDetectedObjects.toList()

        announcementManager.speak("Detection stopped.")
        _voiceNotice.value = "Detection Paused"

        // Save session to Room database & push to Firebase Firestore
        viewModelScope.launch {
            historyRepository.saveSession(
                durationSeconds = duration,
                detectedObjects = detected
            )
            firestoreHistoryRepository.saveSession(
                durationSeconds = duration,
                detectedObjects = detected
            )
        }
        _currentDetections.value = emptyList()
    }

    fun signInWithGoogle(activity: Activity) {
        authManager.signInWithGoogle(
            activity = activity,
            scope = viewModelScope,
            onSuccess = {
                _voiceNotice.value = "Cloud Sync Active"
            },
            onError = { err ->
                _voiceNotice.value = "Sign-in: $err"
            }
        )
    }

    fun signOut() {
        authManager.signOut(viewModelScope) {
            _voiceNotice.value = "Signed out"
        }
    }

    fun setVisionFeedMode(mode: VisionFeedMode) {
        _visionFeedMode.value = mode
    }

    fun setVideoSource(uri: android.net.Uri?, title: String = "Captured Video") {
        _videoUri.value = uri
        _videoTitle.value = title
        _isVideoPlaying.value = true
    }

    fun toggleVideoPlayPause() {
        _isVideoPlaying.value = !_isVideoPlaying.value
    }

    fun processFrame(bitmap: Bitmap) {
        if (!_isDetectionActive.value) return
        val confThreshold = when (settings.value.detectionConfidence) {
            "low" -> 0.25f
            "high" -> 0.60f
            else -> 0.35f
        }
        viewModelScope.launch {
            detectionManager.processImage(bitmap, minConfidence = confThreshold)
        }
    }

    fun processCameraFrame(bitmap: Bitmap) = processFrame(bitmap)

    fun handleVoiceCommand(command: String) {
        val normalized = command.lowercase().trim()
        _voiceNotice.value = "\"$command\""

        when {
            normalized.contains("start") -> {
                startDetection()
                _currentScreen.value = ScreenDestination.DETECTION
            }
            normalized.contains("stop") -> {
                stopDetection()
            }
            normalized.contains("torch on") || normalized.contains("light on") || normalized.contains("flashlight on") -> {
                setTorch(true)
            }
            normalized.contains("torch off") || normalized.contains("light off") || normalized.contains("flashlight off") -> {
                setTorch(false)
            }
            normalized.contains("flip camera") || normalized.contains("switch camera") || normalized.contains("front camera") || normalized.contains("selfie") -> {
                switchCamera()
            }
            normalized.contains("what do you see") || normalized.contains("describe") || normalized.contains("read") -> {
                queryCurrentScene()
            }
            normalized.contains("setting") -> {
                _currentScreen.value = ScreenDestination.SETTINGS
            }
            normalized.contains("history") -> {
                _currentScreen.value = ScreenDestination.HISTORY
            }
            normalized.contains("faces") || normalized.contains("people") -> {
                _currentScreen.value = ScreenDestination.FAMILIAR_FACES
            }
            normalized.contains("home") || normalized.contains("back") -> {
                _currentScreen.value = ScreenDestination.WELCOME
            }
            normalized.contains("help") -> {
                _currentScreen.value = ScreenDestination.HELP
            }
            else -> {
                announcementManager.speak("Heard: $command. Say Start, Stop, Light on, or What do you see.")
            }
        }
    }

    fun queryCurrentScene() {
        val current = _currentDetections.value
        if (current.isEmpty()) {
            announcementManager.speak("No distinct obstacles or objects detected right now.")
        } else {
            val listText = current.map { 
                "${it.label} ${it.positionDescription} (${it.proximityDescription})" 
            }.distinct().joinToString(", ")
            announcementManager.speak("I see: $listText.")
        }
    }

    fun toggleVoiceListening() {
        if (_isVoiceListening.value) {
            voiceCommandManager?.stopListening()
            _isVoiceListening.value = false
            _voiceNotice.value = "Voice standby"
        } else {
            voiceCommandManager?.startListening()
            _isVoiceListening.value = true
            _voiceNotice.value = "Listening for command..."
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearHistory()
            firestoreHistoryRepository.clearHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        sessionTimerJob?.cancel()
        simulationLoopJob?.cancel()
        detectionManager.close()
        announcementManager.shutdown()
        voiceCommandManager?.destroy()
    }
}
