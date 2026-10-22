package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, KnownPersonEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AuralynDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun knownPersonDao(): KnownPersonDao

    companion object {
        @Volatile
        private var INSTANCE: AuralynDatabase? = null

        fun getDatabase(context: Context): AuralynDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AuralynDatabase::class.java,
                    "auralyn_database"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
