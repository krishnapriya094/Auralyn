package com.example.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.example.data.model.KnownPersonDto
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit

class DetectionManager(private val context: Context) {

    companion object {
        private const val TAG = "DetectionManager"
        private val MODEL_FILE_CANDIDATES = listOf("best.tflite", "best_float32.tflite", "best_float16.tflite", "model.tflite")
        private const val LABELS_FILE = "labels.txt"
        private const val INPUT_SIZE = 640
        private const val NUM_ANCHORS = 8400
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 10
    }

    private val _results = MutableSharedFlow<List<DetectionResult>>(replay = 1)
    val results: SharedFlow<List<DetectionResult>> = _results

    private val _highestConfidence = MutableStateFlow(0f)
    val highestConfidence: StateFlow<Float> = _highestConfidence.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _modelErrorMessage = MutableStateFlow<String?>(null)
    val modelErrorMessage: StateFlow<String?> = _modelErrorMessage.asStateFlow()

    private var interpreter: Interpreter? = null
    private val labels: List<String> by lazy { loadLabels() }
    private val numClasses: Int by lazy { labels.size.coerceAtLeast(1) }
    private val inferenceMutex = Mutex()
    private var detectedAnchors = NUM_ANCHORS
    private var isTransposedOutput = false
    private var isChannelFirstInput = false
    private var inputWidth = INPUT_SIZE
    private var inputHeight = INPUT_SIZE
    private var dynamicOutputBuffer: Any? = null
    private var activeModelFileName: String? = null

    // Google ML Kit on-device Pose and Face Detectors for robust real-time Person Detection
    private val poseDetector: PoseDetector by lazy {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.08f)
            .build()
        FaceDetection.getClient(options)
    }

    val faceRecognitionManager = FaceRecognitionManager()
    private var enrolledPeople: List<KnownPersonDto> = emptyList()

    fun updateEnrolledPeople(people: List<KnownPersonDto>) {
        enrolledPeople = people
    }

    init {
        initInterpreter()
    }

    private fun initInterpreter() {
        try {
            val assetList = context.assets.list("")?.toList() ?: emptyList()
            val chosenModel = MODEL_FILE_CANDIDATES.firstOrNull { candidate ->
                assetList.contains(candidate)
            }

            if (chosenModel == null) {
                val hasPt = assetList.contains("best.pt")
                val msg = if (hasPt) {
                    "Found 'best.pt' in assets, but Android requires 'best.tflite'. Please export best.pt using: yolo export model=best.pt format=tflite imgsz=640 and upload best.tflite."
                } else {
                    "No .tflite model found in assets. Please upload 'best.tflite' to app/src/main/assets/."
                }
                Log.w(TAG, msg)
                _modelErrorMessage.value = msg
                _isModelReady.value = false
                return
            }

            activeModelFileName = chosenModel
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val interp = Interpreter(loadModelFile(chosenModel), options)
            interpreter = interp

            // Detect input shape: YOLO models exported from PyTorch are [1, 3, 640, 640] (NCHW)
            // whereas standard TF models are [1, 640, 640, 3] (NHWC)
            val inputShape = interp.getInputTensor(0).shape()
            Log.d(TAG, "Model input tensor shape: ${inputShape.contentToString()}")
            if (inputShape.size == 4) {
                if (inputShape[1] == 3) {
                    isChannelFirstInput = true
                    inputHeight = inputShape[2]
                    inputWidth = inputShape[3]
                } else if (inputShape[3] == 3) {
                    isChannelFirstInput = false
                    inputHeight = inputShape[1]
                    inputWidth = inputShape[2]
                }
            }

            // Detect output shape: YOLOv8/YOLO11 is typically [1, 4+numClasses, 8400] or [1, 8400, 4+numClasses]
            val outputShape = interp.getOutputTensor(0).shape()
            Log.d(TAG, "Model output tensor shape: ${outputShape.contentToString()}")

            if (outputShape.size == 3) {
                val dim1 = outputShape[1]
                val dim2 = outputShape[2]
                if (dim1 < dim2) {
                    // Shape: [1, 4+C, 8400]
                    isTransposedOutput = false
                    detectedAnchors = dim2
                    dynamicOutputBuffer = Array(1) { Array(dim1) { FloatArray(dim2) } }
                } else {
                    // Shape: [1, 8400, 4+C]
                    isTransposedOutput = true
                    detectedAnchors = dim1
                    dynamicOutputBuffer = Array(1) { Array(dim1) { FloatArray(dim2) } }
                }
            } else {
                dynamicOutputBuffer = Array(1) { Array(4 + numClasses) { FloatArray(NUM_ANCHORS) } }
            }

            _isModelReady.value = true
            _modelErrorMessage.value = null
            Log.d(TAG, "Interpreter ready with $numClasses classes ($chosenModel, anchors=$detectedAnchors)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite interpreter: ${e.message}", e)
            _modelErrorMessage.value = "TFLite initialization error: ${e.localizedMessage}"
            _isModelReady.value = false
        }
    }

    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val afd = context.assets.openFd(fileName)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
    }

    private fun loadLabels(): List<String> {
        return try {
            context.assets.open(LABELS_FILE).bufferedReader().readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            listOf("chair", "bottle", "table", "book", "box", "lamp", "door", "window")
        }
    }

    suspend fun processImage(bitmap: Bitmap, minConfidence: Float = 0.5f) {
        if (!inferenceMutex.tryLock()) return

        try {
            val allCandidates = mutableListOf<DetectionResult>()
            var highestScore = 0f

            // 1. Run YOLO custom model inference (if ready and loaded)
            val interp = interpreter
            val buf = dynamicOutputBuffer
            if (interp != null && buf != null) {
                try {
                    val inputBuffer = bitmapToByteBuffer(bitmap)
                    interp.run(inputBuffer, buf)

                    @Suppress("UNCHECKED_CAST")
                    val output3D = buf as Array<Array<FloatArray>>
                    val (customCandidates, customScore) = parseOutput(output3D[0], minConfidence)
                    allCandidates.addAll(customCandidates)
                    highestScore = maxOf(highestScore, customScore)
                } catch (e: Exception) {
                    Log.e(TAG, "YOLO inference error: ${e.message}", e)
                }
            }

            // 2. Run Google ML Kit on-device Person Detection (Pose + Face)
            try {
                val personDetections = detectPersons(bitmap)
                allCandidates.addAll(personDetections)
                val personTopScore = personDetections.maxOfOrNull { it.confidence } ?: 0f
                highestScore = maxOf(highestScore, personTopScore)
            } catch (e: Exception) {
                Log.e(TAG, "Person detection error: ${e.message}", e)
            }

            // 3. Merge candidates with non-maximum suppression and temporal smoothing
            val finalDetections = nonMaxSuppression(allCandidates)
            val smoothedDetections = smoothAndTrackDetections(finalDetections)
            _highestConfidence.value = highestScore
            _results.emit(smoothedDetections)
        } finally {
            inferenceMutex.unlock()
        }
    }

    private fun detectPersons(bitmap: Bitmap): List<DetectionResult> {
        val personResults = mutableListOf<DetectionResult>()
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            // 1. Pose detection for full/partial human body
            val poseTask = poseDetector.process(inputImage)
            val pose = Tasks.await(poseTask, 2000, TimeUnit.MILLISECONDS)
            val validLandmarks = pose.allPoseLandmarks.filter { it.inFrameLikelihood > 0.40f }

            if (validLandmarks.size >= 3) {
                val minX = validLandmarks.minOf { it.position.x }
                val maxX = validLandmarks.maxOf { it.position.x }
                val minY = validLandmarks.minOf { it.position.y }
                val maxY = validLandmarks.maxOf { it.position.y }

                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()

                val padX = (maxX - minX) * 0.15f
                val padY = (maxY - minY) * 0.15f

                val left = ((minX - padX) / w).coerceIn(0f, 1f)
                val top = ((minY - padY) / h).coerceIn(0f, 1f)
                val right = ((maxX + padX) / w).coerceIn(0f, 1f)
                val bottom = ((maxY + padY) / h).coerceIn(0f, 1f)

                val avgConfidence = validLandmarks.map { it.inFrameLikelihood }.average().toFloat()
                personResults.add(
                    DetectionResult(
                        label = "person",
                        confidence = avgConfidence.coerceIn(0.65f, 0.98f),
                        boundingBox = RectF(left, top, right, bottom)
                    )
                )
            }

            // 2. Face detection & Familiar Person Identification
            val faceTask = faceDetector.process(inputImage)
            val faces = Tasks.await(faceTask, 2000, TimeUnit.MILLISECONDS)
            for (face in faces) {
                val box = face.boundingBox
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()

                val padX = box.width() * 0.25f
                val padTop = box.height() * 0.25f
                val padBottom = box.height() * 0.75f

                val left = ((box.left - padX) / w).coerceIn(0f, 1f)
                val top = ((box.top - padTop) / h).coerceIn(0f, 1f)
                val right = ((box.right + padX) / w).coerceIn(0f, 1f)
                val bottom = ((box.bottom + padBottom) / h).coerceIn(0f, 1f)
                val faceRect = RectF(left, top, right, bottom)

                // Match against enrolled familiar people
                val match = if (enrolledPeople.isNotEmpty()) {
                    faceRecognitionManager.identifyFace(bitmap, face, enrolledPeople)
                } else null

                val detectedLabel = match?.first?.name ?: "person"
                val detectedConfidence = match?.second ?: 0.90f

                // Link face to existing body detection if face is within body boundaries
                val faceCenterX = faceRect.centerX()
                val faceCenterY = faceRect.centerY()
                val matchingBodyIdx = personResults.indexOfFirst { body ->
                    body.boundingBox?.let { b ->
                        (faceCenterX in (b.left..b.right) && faceCenterY in (b.top..b.bottom)) ||
                        (iou(b, faceRect) > 0.15f)
                    } ?: false
                }

                if (matchingBodyIdx != -1) {
                    val existing = personResults[matchingBodyIdx]
                    if (detectedLabel != "person") {
                        // Upgrade the body detection directly to the enrolled person's name!
                        personResults[matchingBodyIdx] = existing.copy(
                            label = detectedLabel,
                            confidence = maxOf(existing.confidence, detectedConfidence)
                        )
                    }
                } else {
                    personResults.add(
                        DetectionResult(
                            label = detectedLabel,
                            confidence = detectedConfidence,
                            boundingBox = faceRect
                        )
                    )
                }
            }

            // 3. Fallback: If face detector returned no face, but pose detected facial landmarks (nose, eyes)
            if (faces.isEmpty() && validLandmarks.isNotEmpty() && enrolledPeople.isNotEmpty()) {
                val noseLandmark = pose.getPoseLandmark(PoseLandmark.NOSE)
                val leftEyeLandmark = pose.getPoseLandmark(PoseLandmark.LEFT_EYE)
                val rightEyeLandmark = pose.getPoseLandmark(PoseLandmark.RIGHT_EYE)

                val anchor = noseLandmark ?: leftEyeLandmark
                if (anchor != null) {
                    val anchorX = anchor.position.x
                    val anchorY = anchor.position.y

                    val eyeDist = if (leftEyeLandmark != null && rightEyeLandmark != null) {
                        kotlin.math.abs(leftEyeLandmark.position.x - rightEyeLandmark.position.x).coerceAtLeast(40f)
                    } else 80f

                    val boxHalfW = eyeDist * 1.6f
                    val boxHalfH = eyeDist * 2.2f

                    val fLeft = (anchorX - boxHalfW).toInt().coerceIn(0, bitmap.width - 1)
                    val fTop = (anchorY - boxHalfH * 0.7f).toInt().coerceIn(0, bitmap.height - 1)
                    val fRight = (anchorX + boxHalfW).toInt().coerceIn(fLeft + 1, bitmap.width)
                    val fBottom = (anchorY + boxHalfH * 1.3f).toInt().coerceIn(fTop + 1, bitmap.height)
                    val synthesizedBox = Rect(fLeft, fTop, fRight, fBottom)

                    val match = faceRecognitionManager.identifyFace(bitmap, null, enrolledPeople, synthesizedBox)
                    if (match != null) {
                        val detectedLabel = match.first.name
                        val detectedConfidence = match.second
                        if (personResults.isNotEmpty()) {
                            personResults[0] = personResults[0].copy(
                                label = detectedLabel,
                                confidence = maxOf(personResults[0].confidence, detectedConfidence)
                            )
                        } else {
                            val w = bitmap.width.toFloat()
                            val h = bitmap.height.toFloat()
                            personResults.add(
                                DetectionResult(
                                    label = detectedLabel,
                                    confidence = detectedConfidence,
                                    boundingBox = RectF(fLeft / w, fTop / h, fRight / w, fBottom / h)
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit person detection note: ${e.message}")
        }
        return personResults
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val width = inputWidth
        val height = inputHeight
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }

        val totalPixels = width * height
        val byteBuffer = ByteBuffer.allocateDirect(1 * totalPixels * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        val intValues = IntArray(totalPixels)
        scaled.getPixels(intValues, 0, width, 0, 0, width, height)

        if (isChannelFirstInput) {
            // NCHW format: All Red floats, then all Green floats, then all Blue floats
            for (i in 0 until totalPixels) {
                val v = intValues[i]
                byteBuffer.putFloat(((v shr 16) and 0xFF) / 255.0f)
            }
            for (i in 0 until totalPixels) {
                val v = intValues[i]
                byteBuffer.putFloat(((v shr 8) and 0xFF) / 255.0f)
            }
            for (i in 0 until totalPixels) {
                val v = intValues[i]
                byteBuffer.putFloat((v and 0xFF) / 255.0f)
            }
        } else {
            // NHWC format: Interleaved RGB
            for (i in 0 until totalPixels) {
                val v = intValues[i]
                byteBuffer.putFloat(((v shr 16) and 0xFF) / 255.0f)
                byteBuffer.putFloat(((v shr 8) and 0xFF) / 255.0f)
                byteBuffer.putFloat((v and 0xFF) / 255.0f)
            }
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    private fun parseOutput(output: Array<FloatArray>, minConfidence: Float): Pair<List<DetectionResult>, Float> {
        val results = mutableListOf<DetectionResult>()
        var highestRawScore = 0f
        val anchors = detectedAnchors
        val normWidth = inputWidth.toFloat()
        val normHeight = inputHeight.toFloat()

        for (anchor in 0 until anchors) {
            var maxScore = 0f
            var bestClass = 0

            for (c in 0 until numClasses) {
                val score = if (isTransposedOutput) {
                    output[anchor][4 + c]
                } else {
                    output[4 + c][anchor]
                }
                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            if (maxScore > highestRawScore) {
                highestRawScore = maxScore
            }

            if (maxScore < minConfidence) continue

            val rawCx = if (isTransposedOutput) output[anchor][0] else output[0][anchor]
            val rawCy = if (isTransposedOutput) output[anchor][1] else output[1][anchor]
            val rawW = if (isTransposedOutput) output[anchor][2] else output[2][anchor]
            val rawH = if (isTransposedOutput) output[anchor][3] else output[3][anchor]

            // Support both pixel coordinates ([0..640]) and pre-normalized coordinates ([0..1])
            val cx = if (rawCx > 1.0f) rawCx / normWidth else rawCx
            val cy = if (rawCy > 1.0f) rawCy / normHeight else rawCy
            val w = if (rawW > 1.0f) rawW / normWidth else rawW
            val h = if (rawH > 1.0f) rawH / normHeight else rawH

            val left = (cx - w / 2f).coerceIn(0f, 1f)
            val top = (cy - h / 2f).coerceIn(0f, 1f)
            val right = (cx + w / 2f).coerceIn(0f, 1f)
            val bottom = (cy + h / 2f).coerceIn(0f, 1f)

            if (right <= left || bottom <= top) continue

            val label = if (bestClass < labels.size) labels[bestClass] else "class_$bestClass"
            results.add(
                DetectionResult(
                    label = label,
                    confidence = maxScore,
                    boundingBox = RectF(left, top, right, bottom)
                )
            )
        }
        return Pair(results, highestRawScore)
    }

    private fun nonMaxSuppression(candidates: List<DetectionResult>): List<DetectionResult> {
        val sorted = candidates.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty() && kept.size < MAX_DETECTIONS) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { candidate ->
                best.boundingBox?.let { b1 ->
                    candidate.boundingBox?.let { b2 ->
                        iou(b1, b2) > IOU_THRESHOLD
                    } ?: false
                } ?: false
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight <= interLeft || interBottom <= interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        return interArea / (aArea + bArea - interArea)
    }

    private data class TrackedItem(
        var result: DetectionResult,
        var missingFrames: Int = 0
    )

    private val trackedDetections = mutableListOf<TrackedItem>()

    /**
     * Exponential Moving Average (EMA) box coordinate smoothing & 1-frame holdover
     * to eliminate visual jumping and speech stutter across video frames.
     */
    private fun smoothAndTrackDetections(candidates: List<DetectionResult>): List<DetectionResult> {
        val smoothed = mutableListOf<DetectionResult>()
        val unmatchedCandidates = candidates.toMutableList()

        val iterator = trackedDetections.iterator()
        while (iterator.hasNext()) {
            val tracked = iterator.next()
            val oldBox = tracked.result.boundingBox

            val bestCandidate = unmatchedCandidates
                .filter { it.label.equals(tracked.result.label, ignoreCase = true) }
                .minByOrNull { c ->
                    if (oldBox != null && c.boundingBox != null) {
                        val dx = oldBox.centerX() - c.boundingBox.centerX()
                        val dy = oldBox.centerY() - c.boundingBox.centerY()
                        dx * dx + dy * dy
                    } else 1f
                }

            if (bestCandidate != null && oldBox != null && bestCandidate.boundingBox != null) {
                val newBox = bestCandidate.boundingBox
                val centerDistSq = (oldBox.centerX() - newBox.centerX()) * (oldBox.centerX() - newBox.centerX()) +
                        (oldBox.centerY() - newBox.centerY()) * (oldBox.centerY() - newBox.centerY())

                if (centerDistSq < 0.09f) {
                    val alpha = 0.70f
                    val smoothLeft = alpha * newBox.left + (1f - alpha) * oldBox.left
                    val smoothTop = alpha * newBox.top + (1f - alpha) * oldBox.top
                    val smoothRight = alpha * newBox.right + (1f - alpha) * oldBox.right
                    val smoothBottom = alpha * newBox.bottom + (1f - alpha) * oldBox.bottom

                    val smoothConf = alpha * bestCandidate.confidence + (1f - alpha) * tracked.result.confidence
                    val updated = bestCandidate.copy(
                        confidence = smoothConf,
                        boundingBox = RectF(smoothLeft, smoothTop, smoothRight, smoothBottom)
                    )

                    tracked.result = updated
                    tracked.missingFrames = 0
                    smoothed.add(updated)
                    unmatchedCandidates.remove(bestCandidate)
                    continue
                }
            }

            tracked.missingFrames++
            if (tracked.missingFrames <= 1) {
                // Hold for 1 frame to prevent flicker
                val held = tracked.result.copy(confidence = tracked.result.confidence * 0.85f)
                smoothed.add(held)
            } else {
                iterator.remove()
            }
        }

        for (newCandidate in unmatchedCandidates) {
            trackedDetections.add(TrackedItem(newCandidate, missingFrames = 0))
            smoothed.add(newCandidate)
        }

        return smoothed.sortedByDescending { it.confidence }.take(MAX_DETECTIONS)
    }

    fun emitSimulatedDetections(detections: List<DetectionResult>) {
        val highest = detections.maxOfOrNull { it.confidence } ?: 0f
        _highestConfidence.value = highest
        _results.tryEmit(detections)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        try {
            poseDetector.close()
            faceDetector.close()
            faceRecognitionManager.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ML Kit detectors: ${e.message}")
        }
        _isModelReady.value = false
    }
}
