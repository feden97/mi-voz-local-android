package com.qwen.tts.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VoiceProfileEntity::class, GenerationEntity::class],
    version = 4,
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generations ADD COLUMN readingText TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generations ADD COLUMN languageId INTEGER")
                db.execSQL("ALTER TABLE generations ADD COLUMN editLabel TEXT")
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
