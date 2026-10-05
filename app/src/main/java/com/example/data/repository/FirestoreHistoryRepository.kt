package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.model.DetectionEventDto
import com.example.data.model.DetectionSessionDto
import com.example.data.model.KnownPersonDto
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FirestoreHistoryRepository(
    private val db: FirebaseFirestore
) {
    companion object {
        private const val TAG = "FirestoreHistoryRepo"
    }

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    val currentUserId: String?
        get() = auth.currentUser?.uid

    val isUserSignedIn: Boolean
        get() = auth.currentUser != null

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    /**
     * Pushes an individual detected object/person event to Firestore in real time.
     */
    fun logDetectionEvent(
        label: String,
        confidence: Float,
        location: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val uid = auth.currentUser?.uid ?: return // Silent skip if not signed in yet
        val path = "users/$uid/detection_events"
        val eventsRef = db.collection("users").document(uid).collection("detection_events")

        val payload = mapOf(
            "userId" to uid,
            "label" to label.lowercase().trim(),
            "confidence" to confidence.toDouble(),
            "location" to location,
            "timestamp" to FieldValue.serverTimestamp(),
            "createdAt" to FieldValue.serverTimestamp()
        )

        eventsRef.add(payload)
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                handleFirestoreError(exception, OperationType.CREATE, path)
                onError(exception.localizedMessage ?: "Failed to log detection event")
            }
    }

    /**
     * Saves a completed detection session to Firestore.
     */
    fun saveSession(
        durationSeconds: Int,
        detectedObjects: List<String>,
        onSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val uid = auth.currentUser?.uid ?: return
        val path = "users/$uid/sessions"
        val sessionsRef = db.collection("users").document(uid).collection("sessions")

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

        val payload = mapOf(
            "userId" to uid,
            "timeLabel" to timeLabel,
            "dateHeader" to "Today",
            "durationSeconds" to durationSeconds,
            "durationFormatted" to durationFormatted,
            "detectedObjectsList" to objectsStr,
            "objectsCount" to detectedObjects.distinct().size,
            "status" to "COMPLETED",
            "timestamp" to now,
            "createdAt" to FieldValue.serverTimestamp()
        )

        sessionsRef.add(payload)
            .addOnSuccessListener { docRef ->
                onSuccess(docRef.id)
            }
            .addOnFailureListener { exception ->
                handleFirestoreError(exception, OperationType.CREATE, path)
                onError(exception.localizedMessage ?: "Failed to save session to cloud")
            }
    }

    /**
     * Observes real-time sessions from Firestore for the current user.
     */
    fun observeSessions(): Flow<List<DetectionSessionDto>> = flow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            emit(emptyList())
            return@flow
        }
        val path = "users/$uid/sessions"
        emitAll(
            db.collection("users").document(uid).collection("sessions")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(DetectionSessionDto::class.java)?.copy(id = doc.id)
                    }
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    /**
     * Observes real-time individual detection events for the current user.
     */
    fun observeRecentDetectionEvents(): Flow<List<DetectionEventDto>> = flow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            emit(emptyList())
            return@flow
        }
        val path = "users/$uid/detection_events"
        emitAll(
            db.collection("users").document(uid).collection("detection_events")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(50)
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(DetectionEventDto::class.java)?.copy(id = doc.id)
                    }
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    /**
     * Deletes all sessions and events in Firestore for the current user.
     */
    fun clearHistory(onComplete: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val uid = auth.currentUser?.uid ?: return
        val sessionsPath = "users/$uid/sessions"
        val eventsPath = "users/$uid/detection_events"

        db.collection("users").document(uid).collection("sessions").get()
            .addOnSuccessListener { snapshot ->
                val batch = db.batch()
                snapshot.documents.forEach { doc -> batch.delete(doc.reference) }
                batch.commit()
                    .addOnSuccessListener {
                        db.collection("users").document(uid).collection("detection_events").get()
                            .addOnSuccessListener { evSnapshot ->
                                val evBatch = db.batch()
                                evSnapshot.documents.forEach { doc -> evBatch.delete(doc.reference) }
                                evBatch.commit()
                                    .addOnSuccessListener { onComplete() }
                                    .addOnFailureListener { onComplete() }
                            }
                            .addOnFailureListener { onComplete() }
                    }
                    .addOnFailureListener { e ->
                        handleFirestoreError(e, OperationType.DELETE, sessionsPath)
                        onError(e.localizedMessage ?: "Failed to clear Firestore history")
                    }
            }
            .addOnFailureListener { e ->
                handleFirestoreError(e, OperationType.LIST, sessionsPath)
                onError(e.localizedMessage ?: "Failed to query sessions for deletion")
            }
    }

    /**
     * Observes real-time familiar faces (known people) enrolled by the current user.
     */
    fun observeKnownPeople(): Flow<List<KnownPersonDto>> = flow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            emit(emptyList())
            return@flow
        }
        val path = "users/$uid/known_persons"
        emitAll(
            db.collection("users").document(uid).collection("known_persons")
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        doc.toObject(KnownPersonDto::class.java)?.copy(id = doc.id)
                    }
                }
                .catch { error ->
                    if (error is Exception) handleFirestoreError(error, OperationType.LIST, path)
                    throw error
                }
        )
    }

    /**
     * Enrolls or updates a familiar person in Firestore.
     */
    fun saveKnownPerson(
        person: KnownPersonDto,
        onSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val uid = auth.currentUser?.uid ?: return
        val path = "users/$uid/known_persons"
        val ref = db.collection("users").document(uid).collection("known_persons")

        val payload = mapOf(
            "userId" to uid,
            "name" to person.name.trim(),
            "relationship" to person.relationship.trim(),
            "faceFeatures" to person.faceFeatures,
            "thumbnailBase64" to person.thumbnailBase64,
            "timestamp" to System.currentTimeMillis(),
            "createdAt" to FieldValue.serverTimestamp()
        )

        ref.add(payload)
            .addOnSuccessListener { doc -> onSuccess(doc.id) }
            .addOnFailureListener { e ->
                handleFirestoreError(e, OperationType.CREATE, path)
                onError(e.localizedMessage ?: "Failed to save familiar face")
            }
    }

    /**
     * Deletes a familiar person from Firestore.
     */
    fun deleteKnownPerson(personId: String, onComplete: () -> Unit = {}) {
        val uid = auth.currentUser?.uid ?: return
        val path = "users/$uid/known_persons/$personId"
        db.collection("users").document(uid).collection("known_persons").document(personId)
            .delete()
            .addOnSuccessListener { onComplete() }
            .addOnFailureListener { e ->
                handleFirestoreError(e, OperationType.DELETE, path)
                onComplete()
            }
    }
}
