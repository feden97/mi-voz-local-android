package com.qwen.tts.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VoiceProfileEntity::class, GenerationEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class QwenDatabase : RoomDatabase() {
    abstract fun qwenDao(): QwenDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generations ADD COLUMN modelId TEXT")
                db.execSQL("ALTER TABLE generations ADD COLUMN modelName TEXT")
                db.execSQL("ALTER TABLE generations ADD COLUMN backend TEXT")
                db.execSQL("ALTER TABLE generations ADD COLUMN cpuThreads INTEGER")
                db.execSQL("ALTER TABLE generations ADD COLUMN cloningMode TEXT")
                db.execSQL("ALTER TABLE generations ADD COLUMN reportPath TEXT")
                db.execSQL("ALTER TABLE generations ADD COLUMN totalMillis INTEGER")
                db.execSQL("ALTER TABLE generations ADD COLUMN chunkCount INTEGER")
                db.execSQL("ALTER TABLE generations ADD COLUMN status TEXT")
                db.execSQL("ALTER TABLE generations ADD COLUMN completedChunks INTEGER")
            }
        }

        @Volatile
        private var INSTANCE: QwenDatabase? = null

        fun getDatabase(context: Context): QwenDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QwenDatabase::class.java,
                    "qwen_tts_database",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
