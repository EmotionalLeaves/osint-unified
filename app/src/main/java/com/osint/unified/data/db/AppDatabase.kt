package com.osint.unified.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Investigation::class, Result::class, Watch::class, WatchEvent::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun investigationDao(): InvestigationDao
    abstract fun resultDao(): ResultDao
    abstract fun watchDao(): WatchDao
    abstract fun watchEventDao(): WatchEventDao

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

        /**
         * Records where an investigation came from.
         *
         * Added rather than rebuilt, so the cases someone has already run
         * survive the upgrade.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `investigations` ADD COLUMN `parentId` INTEGER")
                db.execSQL("ALTER TABLE `investigations` ADD COLUMN `viaPivot` TEXT")
            }
        }

        /**
         * Gives each watch a history, and stops the same target being
         * watched twice.
         *
         * Duplicate rows already in the database are collapsed before the
         * unique index is created, keeping the oldest of each set — it has
         * the longest record of checks behind it. Doing that the other way
         * round makes the index creation fail and the upgrade crash on
         * launch for exactly the people who used the feature most.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `watch_events` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `watchId` INTEGER NOT NULL,
                        `at` INTEGER NOT NULL,
                        `summary` TEXT NOT NULL,
                        `appeared` TEXT NOT NULL,
                        `vanished` TEXT NOT NULL,
                        `altered` TEXT NOT NULL,
                        `acknowledged` INTEGER NOT NULL,
                        FOREIGN KEY(`watchId`) REFERENCES `watches`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_events_watchId` " +
                        "ON `watch_events` (`watchId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_events_at` " +
                        "ON `watch_events` (`at`)"
                )
                db.execSQL(
                    "DELETE FROM `watches` WHERE `id` NOT IN " +
                        "(SELECT MIN(`id`) FROM `watches` GROUP BY `observable`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_watches_observable` " +
                        "ON `watches` (`observable`)"
                )
                // The one change each watch already remembers becomes the
                // first entry of its log, rather than being thrown away.
                db.execSQL(
                    """
                    INSERT INTO `watch_events`
                        (`watchId`, `at`, `summary`, `appeared`, `vanished`, `altered`, `acknowledged`)
                    SELECT `id`, `lastChangeAt`, `lastChange`, '', '', '', 1
                    FROM `watches`
                    WHERE `lastChangeAt` IS NOT NULL AND `lastChange` != ''
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
    }
}
