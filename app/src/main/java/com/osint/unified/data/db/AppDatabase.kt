package com.osint.unified.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [Investigation::class, Result::class, Watch::class, WatchEvent::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun investigationDao(): InvestigationDao
    abstract fun resultDao(): ResultDao
    abstract fun watchDao(): WatchDao
    abstract fun watchEventDao(): WatchEventDao

    companion object {
        /** The same number as the `version` in the @Database annotation
         *  above, which cannot reference this one. The pre-flight check
         *  refuses a commit where the two disagree. */
        const val SCHEMA_VERSION = 5

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
         * Records what a source actually sent, not what was kept.
         *
         * The stored output is capped, so a hash taken from it later is
         * a hash of a truncated copy — which is the one thing a
         * provenance fingerprint must not be.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `results` ADD COLUMN `outputSha256` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `results` ADD COLUMN `outputBytes` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `results` ADD COLUMN `truncated` INTEGER NOT NULL DEFAULT 0"
                )
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

        /**
         * Whether the case database on this device is actually encrypted,
         * and why not when it is not.
         *
         * Reported rather than assumed: a security property the app claims
         * and does not have is worse than one it does not claim.
         */
        @Volatile var encrypted: Boolean = false
            private set

        @Volatile var encryptionNote: String = "not opened yet"
            private set

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: open(context.applicationContext).also { INSTANCE = it }
            }

        /**
         * Encrypted if this device will have it, plaintext if it will not.
         *
         * An investigation holds who was looked at and everything every
         * source said about them, so it is encrypted at rest with a key
         * sealed by the hardware keystore. But a tool that refuses to open
         * is no use to anyone: if the keystore will not produce a key, or
         * the native library will not load, or the one-off move of an
         * existing database does not verify, the app carries on exactly as
         * it did before and says so on the MORE screen rather than
         * pretending.
         */
        private fun open(app: Context): AppDatabase {
            val passphrase = DatabaseKey.passphrase(app)
            if (passphrase == null) {
                encryptionNote = "this device's keystore would not provide a key"
                return plain(app)
            }
            runCatching { System.loadLibrary("sqlcipher") }

            // Builds r173 to r176 verified the copied database by opening
            // it at the wrong version, which renumbered it, after which
            // Room replayed migrations over a schema that already had them
            // and this method fell back to a new empty database. The real
            // one was still there, intact, with only the number at the top
            // of the file wrong. Putting that number back is the first
            // thing that happens now, before anything else is tried.
            val repaired = DatabaseEncryption.repairVersion(app, passphrase)

            val moved = runCatching {
                DatabaseEncryption.migrateIfNeeded(app, passphrase)
            }.getOrNull()

            val attempt = runCatching {
                val db = Room.databaseBuilder(
                    app, AppDatabase::class.java, DatabaseEncryption.ENCRYPTED_NAME
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                // Opened and read from here on purpose: a key, a library
                // or a migration problem has to surface now, while the
                // plaintext copy is still on disk, and not on the first
                // query from a screen.
                db.openHelper.writableDatabase.query("SELECT count(*) FROM investigations")
                    .use { it.moveToFirst() }
                db
            }
            attempt.getOrNull()?.let { db ->
                encrypted = true
                encryptionNote = when {
                    repaired != null ->
                        "recovered a database that an earlier build had " +
                            "renumbered, and encrypted at rest"
                    moved != null -> moved
                    else -> "encrypted at rest"
                }
                // Only now is the plaintext copy of any existing database
                // no longer needed.
                runCatching { app.getDatabasePath(DatabaseEncryption.BACKUP_NAME).delete() }
                return db
            }

            // The encrypted database would not open. If the plaintext copy
            // is still there, that is the one with the data in it — far
            // better to run unencrypted off real cases than encrypted off
            // an empty file.
            val backup = app.getDatabasePath(DatabaseEncryption.BACKUP_NAME)
            val plainFile = app.getDatabasePath(DatabaseEncryption.PLAIN_NAME)
            val why = attempt.exceptionOrNull()?.message
            if (backup.exists() && !plainFile.exists()) {
                runCatching { backup.renameTo(plainFile) }
            }
            encryptionNote = if (plainFile.exists()) {
                "encryption failed ($why), so the unencrypted copy of your " +
                    "cases is being used — nothing was lost, but nothing is " +
                    "encrypted either"
            } else {
                "encryption failed ($why); the database is readable by " +
                    "anything that can read this app's files"
            }
            return plain(app)
        }

        private fun plain(app: Context): AppDatabase = Room.databaseBuilder(
            app, AppDatabase::class.java, DatabaseEncryption.PLAIN_NAME
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
}
