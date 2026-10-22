package com.example.logic

import android.graphics.Bitmap
import android.graphics.Matrix
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
        private const val THUMBNAIL_SIZE = 128
        private const val MAX_ENROLL_DIM = 960
    }

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.10f)
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * Enrolls a new familiar face from a reference photo.
     */
    fun enrollFace(
        sourceBitmap: Bitmap,
        name: String,
        relationship: String
    ): Result<KnownPersonDto> {
        return try {
            val bitmap = downscaleIfNeeded(sourceBitmap, MAX_ENROLL_DIM)

            // Try detection with standard orientation, then 90, 270 degrees
            var detectedFace: Face? = null
            var workingBitmap: Bitmap = bitmap

            val rotations = listOf(0, 90, 270)
            for (rotation in rotations) {
                val candidateBitmap = if (rotation == 0) bitmap else rotateBitmap(bitmap, rotation.toFloat())
                val inputImage = InputImage.fromBitmap(candidateBitmap, 0)
                val facesTask = detector.process(inputImage)
                val faces = try {
                    Tasks.await(facesTask, 5000, TimeUnit.MILLISECONDS)
                } catch (e: Exception) {
                    emptyList<Face>()
                }

                if (faces.isNotEmpty()) {
                    detectedFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    workingBitmap = candidateBitmap
                    break
                }
            }

            val cropped: Bitmap
            val features: FloatArray

            if (detectedFace != null) {
                cropped = cropFace(workingBitmap, detectedFace.boundingBox)
                features = extractFaceFeatures(cropped, detectedFace)
                Log.d(TAG, "Face isolated with landmarks for $name")
            } else {
                Log.w(TAG, "No distinct ML Kit face landmark found; using center portrait crop for $name")
                cropped = cropCenterPortrait(workingBitmap)
                features = extractFaceFeatures(cropped, null)
            }

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
     * Identifies a detected face against enrolled known people.
     */
    fun identifyFace(
        frameBitmap: Bitmap,
        face: Face?,
        knownPeople: List<KnownPersonDto>,
        faceBox: Rect? = null
    ): Pair<KnownPersonDto, Float>? {
        if (knownPeople.isEmpty()) return null

        try {
            val box = face?.boundingBox ?: faceBox ?: return null
            val cropped = cropFace(frameBitmap, box)
            val detectedFeatures = extractFaceFeatures(cropped, face)

            var bestPerson: KnownPersonDto? = null
            var bestScore = -1f

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

            // Adaptive similarity threshold:
            // If user only enrolled 1 person, threshold is 0.28f so they are recognized smoothly.
            // If multiple people, threshold is 0.35f to distinguish between them.
            val threshold = if (knownPeople.size == 1) 0.28f else 0.35f

            Log.d(TAG, "identifyFace: best candidate '${bestPerson?.name}' score=$bestScore, threshold=$threshold")

            return if (bestPerson != null && bestScore >= threshold) {
                val normalizedConfidence = 0.75f + ((bestScore - threshold) / (1f - threshold).coerceAtLeast(0.1f)) * 0.23f
                Pair(bestPerson, normalizedConfidence.coerceIn(0.75f, 0.98f))
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Face identification skipped: ${e.message}")
            return null
        }
    }

    /**
     * Multi-modal feature extraction combining color distribution, spatial projections,
     * and geometric proportions (58 total dimensions).
     */
    private fun extractFaceFeatures(faceCrop: Bitmap, face: Face?): FloatArray {
        val features = FloatArray(58)
        var idx = 0

        val scaled = Bitmap.createScaledBitmap(faceCrop, 32, 32, true)
        val pixels = IntArray(32 * 32)
        scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)

        // 1. Color Histogram (16 bins: 8 Hue, 4 Saturation, 4 Value) - translation and rotation invariant
        val colorHist = FloatArray(16)
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f

            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val df = mx - mn

            var h = 0f
            if (df > 0.001f) {
                h = when (mx) {
                    r -> ((g - b) / df) % 6f
                    g -> ((b - r) / df) + 2f
                    else -> ((r - g) / df) + 4f
                }
                h = ((h / 6f) % 1f + 1f) % 1f
            }

            val s = if (mx <= 0.001f) 0f else df / mx
            val v = mx

            val hBin = (h * 7.99f).toInt().coerceIn(0, 7)
            val sBin = (s * 3.99f).toInt().coerceIn(0, 3)
            val vBin = (v * 3.99f).toInt().coerceIn(0, 3)

            colorHist[hBin] += 1f
            colorHist[8 + sBin] += 1f
            colorHist[12 + vBin] += 1f
        }
        normalizeL2(colorHist)
        for (ch in colorHist) {
            features[idx++] = ch * 1.4f
        }

        // 2. Spatial 4x4 regional luminance averages (16 bins)
        val grid4x4 = Bitmap.createScaledBitmap(faceCrop, 4, 4, true)
        val gridLums = FloatArray(16)
        var gIdx = 0
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val p = grid4x4.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                gridLums[gIdx++] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            }
        }
        normalizeL2(gridLums)
        for (gl in gridLums) {
            features[idx++] = gl
        }

        // 3. Horizontal and Vertical projection profiles (16 bins: 8 horiz, 8 vert)
        val grid8x8 = Bitmap.createScaledBitmap(faceCrop, 8, 8, true)
        val horizProfile = FloatArray(8)
        val vertProfile = FloatArray(8)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val p = grid8x8.getPixel(x, y)
                val lum = ((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f
                horizProfile[y] += lum
                vertProfile[x] += lum
            }
        }
        normalizeL2(horizProfile)
        normalizeL2(vertProfile)
        for (hp in horizProfile) features[idx++] = hp
        for (vp in vertProfile) features[idx++] = vp

        // 4. Geometric Landmark Ratios (10 slots)
        if (face != null) {
            val boxW = face.boundingBox.width().toFloat().coerceAtLeast(1f)
            val boxH = face.boundingBox.height().toFloat().coerceAtLeast(1f)

            val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
            val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position

            if (leftEye != null && rightEye != null) {
                features[idx++] = (distance(leftEye.x, leftEye.y, rightEye.x, rightEye.y) / boxW)
            } else features[idx++] = 0.35f

            if (leftEye != null && nose != null) {
                features[idx++] = (distance(leftEye.x, leftEye.y, nose.x, nose.y) / boxH)
            } else features[idx++] = 0.30f

            if (nose != null && mouthBottom != null) {
                features[idx++] = (distance(nose.x, nose.y, mouthBottom.x, mouthBottom.y) / boxH)
            } else features[idx++] = 0.25f

            features[idx++] = (boxW / boxH).coerceIn(0.5f, 2f) * 0.5f
        }

        while (idx < 58) {
            features[idx++] = 0.35f
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
        return dot.coerceIn(-1f, 1f)
    }

    private fun parseFeatureVector(str: String): FloatArray {
        return try {
            str.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (e: Exception) {
            FloatArray(0)
        }
    }

    private fun cropFace(source: Bitmap, box: Rect): Bitmap {
        val padX = (box.width() * 0.15f).toInt()
        val padY = (box.height() * 0.15f).toInt()

        val left = (box.left - padX).coerceIn(0, source.width - 1)
        val top = (box.top - padY).coerceIn(0, source.height - 1)
        val right = (box.right + padX).coerceIn(left + 1, source.width)
        val bottom = (box.bottom + padY).coerceIn(top + 1, source.height)

        val w = right - left
        val h = bottom - top

        val cropped = Bitmap.createBitmap(source, left, top, w, h)
        return Bitmap.createScaledBitmap(cropped, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
    }

    private fun cropCenterPortrait(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val left = (source.width - size) / 2
        val top = (source.height - size) / 4
        val cropped = Bitmap.createBitmap(source, left.coerceIn(0, source.width - 1), top.coerceIn(0, source.height - 1), size, size)
        return Bitmap.createScaledBitmap(cropped, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
    }

    private fun downscaleIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
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
