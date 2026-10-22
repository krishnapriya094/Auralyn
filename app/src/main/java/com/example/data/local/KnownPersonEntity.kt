package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.KnownPersonDto
import java.util.UUID

@Entity(tableName = "known_persons")
data class KnownPersonEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val userId: String = "",
    val name: String,
    val relationship: String = "",
    val faceFeatures: String = "",
    val thumbnailBase64: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toDto(): KnownPersonDto = KnownPersonDto(
        id = id,
        userId = userId,
        name = name,
        relationship = relationship,
        faceFeatures = faceFeatures,
        thumbnailBase64 = thumbnailBase64,
        timestamp = timestamp
    )
}

fun KnownPersonDto.toEntity(): KnownPersonEntity = KnownPersonEntity(
    id = id.ifEmpty { UUID.randomUUID().toString() },
    userId = userId,
    name = name,
    relationship = relationship,
    faceFeatures = faceFeatures,
    thumbnailBase64 = thumbnailBase64,
    timestamp = if (timestamp != 0L) timestamp else System.currentTimeMillis()
)
