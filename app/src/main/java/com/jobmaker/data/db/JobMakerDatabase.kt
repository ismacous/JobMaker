package com.jobmaker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileEntity::class, CandidatureEntity::class, ExplanationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class JobMakerDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun candidatureDao(): CandidatureDao
    abstract fun explanationDao(): ExplanationDao

    companion object {
        @Volatile
        private var instance: JobMakerDatabase? = null

        fun get(context: Context): JobMakerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JobMakerDatabase::class.java,
                    "jobmaker.db",
                ).build().also { instance = it }
            }
    }
}
