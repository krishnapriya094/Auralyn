package com.example.logic

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.example.data.model.KnownPersonDto
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class FaceRecognitionManager {

    companion object {
        private const val TAG = "FaceRecognitionManager"
        private const val SIMILARITY_THRESHOLD = 0.68f
        private const val THUMBNAIL_SIZE = 96
        private const val EMBEDDING_GRID = 8
    }

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * Enrolls a new familiar face from a reference photo.
     */
    fun enrollFace(
        bitmap: Bitmap,
        name: String,
        relationship: String
    ): Result<KnownPersonDto> {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val facesTask = detector.process(inputImage)
            val faces = Tasks.await(facesTask, 3000, TimeUnit.MILLISECONDS)

            if (faces.isEmpty()) {
                return Result.failure(Exception("No clear face detected in the photo. Please choose a well-lit photo showing the person's face."))
            }

            // Pick the most prominent face
            val mainFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                ?: return Result.failure(Exception("Could not isolate a distinct face."))

            val box = mainFace.boundingBox
            val cropped = cropFace(bitmap, box)

            val features = extractFaceFeatures(cropped, mainFace)
            val featureString = features.joinToString(",") { "%.4f".format(it) }
            val thumbnailBase64 = bitmapToBase64(cropped)

            Result.success(
                KnownPersonDto(
                    name = name.trim(),
                    relationship = relationship.trim(),
                    faceFeatures = featureString,
                    thumbnailBase64 = thumbnailBase64,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Enrollment error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Identifies a detected face against enrolled known people using cosine similarity.
     */
    fun identifyFace(
        frameBitmap: Bitmap,
        face: Face,
        knownPeople: List<KnownPersonDto>
    ): Pair<KnownPersonDto, Float>? {
        if (knownPeople.isEmpty()) return null

        try {
            val cropped = cropFace(frameBitmap, face.boundingBox)
            val detectedFeatures = extractFaceFeatures(cropped, face)

            var bestPerson: KnownPersonDto? = null
            var bestScore = 0f

            for (person in knownPeople) {
                if (person.faceFeatures.isEmpty()) continue
                val enrolledFeatures = parseFeatureVector(person.faceFeatures)
                if (enrolledFeatures.size != detectedFeatures.size) continue

                val score = cosineSimilarity(detectedFeatures, enrolledFeatures)
                if (score > bestScore) {
                    bestScore = score
                    bestPerson = person
                }
            }

            return if (bestPerson != null && bestScore >= SIMILARITY_THRESHOLD) {
                Pair(bestPerson, bestScore)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Face identification skipped: ${e.message}")
            return null
        }
    }

    private fun extractFaceFeatures(faceCrop: Bitmap, face: Face): FloatArray {
        val features = FloatArray(EMBEDDING_GRID * EMBEDDING_GRID + 10)
        var idx = 0

        // 1. Geometric landmark ratios (scale and translation invariant)
        val boxW = face.boundingBox.width().toFloat().coerceAtLeast(1f)
        val boxH = face.boundingBox.height().toFloat().coerceAtLeast(1f)

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position

        if (leftEye != null && rightEye != null) {
            val eyeDist = distance(leftEye.x, leftEye.y, rightEye.x, rightEye.y) / boxW
            features[idx++] = eyeDist
        } else {
            features[idx++] = 0.35f
        }

        if (leftEye != null && nose != null) {
            val noseDist = distance(leftEye.x, leftEye.y, nose.x, nose.y) / boxH
            features[idx++] = noseDist
        } else {
            features[idx++] = 0.30f
        }

        if (mouthLeft != null && mouthRight != null) {
            val mouthWidth = distance(mouthLeft.x, mouthLeft.y, mouthRight.x, mouthRight.y) / boxW
            features[idx++] = mouthWidth
        } else {
            features[idx++] = 0.40f
        }

        if (nose != null && mouthBottom != null) {
            val noseToMouth = distance(nose.x, nose.y, mouthBottom.x, mouthBottom.y) / boxH
            features[idx++] = noseToMouth
        } else {
            features[idx++] = 0.25f
        }

        // Facial aspect ratio
        features[idx++] = (boxW / boxH).coerceIn(0.5f, 2f)

        // Fill remaining geometric slots
        while (idx < 10) {
            features[idx++] = 0.5f
        }

        // 2. Spatial 8x8 luminance grid embedding
        val scaled = Bitmap.createScaledBitmap(faceCrop, EMBEDDING_GRID, EMBEDDING_GRID, true)
        for (y in 0 until EMBEDDING_GRID) {
            for (x in 0 until EMBEDDING_GRID) {
                val pixel = scaled.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
                features[idx++] = lum
            }
        }

        normalizeL2(features)
        return features
    }

    private fun normalizeL2(vec: FloatArray) {
        var sumSquares = 0f
        for (v in vec) sumSquares += v * v
        val norm = sqrt(sumSquares).coerceAtLeast(1e-6f)
        for (i in vec.indices) vec[i] /= norm
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return dot.coerceIn(0f, 1f)
    }

    private fun parseFeatureVector(str: String): FloatArray {
        return try {
            str.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (e: Exception) {
            FloatArray(0)
        }
    }

    private fun cropFace(source: Bitmap, box: Rect): Bitmap {
        val padX = (box.width() * 0.1f).toInt()
        val padY = (box.height() * 0.1f).toInt()

        val left = (box.left - padX).coerceIn(0, source.width - 1)
        val top = (box.top - padY).coerceIn(0, source.height - 1)
        val right = (box.right + padX).coerceIn(left + 1, source.width)
        val bottom = (box.bottom + padY).coerceIn(top + 1, source.height)

        val w = right - left
        val h = bottom - top

        val cropped = Bitmap.createBitmap(source, left, top, w, h)
        return Bitmap.createScaledBitmap(cropped, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    fun close() {
        try {
            detector.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing FaceDetector: ${e.message}")
        }
    }
}
