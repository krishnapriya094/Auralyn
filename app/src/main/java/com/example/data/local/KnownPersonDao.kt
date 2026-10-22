package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownPersonDao {
    @Query("SELECT * FROM known_persons ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<KnownPersonEntity>>

    @Query("SELECT * FROM known_persons ORDER BY timestamp DESC")
    suspend fun getAll(): List<KnownPersonEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: KnownPersonEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(persons: List<KnownPersonEntity>)

    @Query("DELETE FROM known_persons WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM known_persons")
    suspend fun deleteAll()
}
