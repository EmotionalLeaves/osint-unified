package com.osint.unified.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Investigation::class, Result::class, Watch::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun investigationDao(): InvestigationDao
    abstract fun resultDao(): ResultDao
    abstract fun watchDao(): WatchDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * Adds the watch table.
         *
         * Written out rather than falling back to a destructive migration:
         * the existing tables hold investigations someone ran, and losing
         * those to add a feature would be the app deciding its own
         * convenience matters more than their work.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `watches` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `observable` TEXT NOT NULL,
                        `observableType` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastCheckedAt` INTEGER,
                        `digest` TEXT NOT NULL,
                        `lastChange` TEXT NOT NULL,
                        `lastChangeAt` INTEGER,
                        `checks` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "osint-unified.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
