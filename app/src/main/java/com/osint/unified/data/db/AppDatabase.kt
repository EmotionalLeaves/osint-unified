package com.osint.unified.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        Investigation::class, Result::class, Watch::class,
        WatchEvent::class, Dismissal::class, Case::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun investigationDao(): InvestigationDao
    abstract fun resultDao(): ResultDao
    abstract fun watchDao(): WatchDao
    abstract fun watchEventDao(): WatchEventDao
    abstract fun dismissalDao(): DismissalDao
    abstract fun caseDao(): CaseDao

    companion object {
        /**
         * Written beside the fallback database the first time this
         * app creates an empty one because the encrypted database
         * would not open.
         *
         * Both notes used to decide from "does the fallback have any
         * rows", which is true the moment the user creates one case
         * after the failure — so the long warning naming fifty
         * unreadable cases and saying not to reinstall collapsed into
         * "the keystore would not provide a key", and the other
         * branch began saying "nothing was lost". A row count cannot
         * tell "these are your cases" from "this is one case you made
         * since"; a marker written at the moment of the failure can,
         * and it says so for good.
         */
        private const val FRESH_MARKER = "osint-unified-fallback-is-new"

        /** The same number as the `version` in the @Database annotation
         *  above, which cannot reference this one. The pre-flight check
         *  refuses a commit where the two disagree. */
        const val SCHEMA_VERSION = 7

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
                MigrationSql.v1to2.forEach(db::execSQL)
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
                MigrationSql.v2to3.forEach(db::execSQL)
            }
        }

        /**
         * Several targets held together as one enquiry.
         *
         * Targets keep their results when a case is deleted — the
         * folder goes, the collection does not — so the foreign key
         * nulls rather than cascades.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MigrationSql.v6to7.forEach(db::execSQL)
            }
        }

        /**
         * Somewhere to record that a finding is wrong.
         *
         * Per case, because an address that is noise in one
         * investigation is the subject of another.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MigrationSql.v5to6.forEach(db::execSQL)
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
                MigrationSql.v4to5.forEach(db::execSQL)
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
                MigrationSql.v3to4.forEach(db::execSQL)
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

        /**
         * True when there is an encrypted database on this device that
         * this app can no longer open.
         *
         * The cases are still there and are not coming back without
         * the key. That happens when the keystore entry goes — a
         * lock-screen reset, a restore onto a new device — and the
         * handling was to open a fresh empty database and carry on as
         * though nothing had happened, with one line about it on a
         * settings screen nobody has a reason to visit. Somebody
         * opening the app to a blank case list deserves to be told
         * that the old ones exist and why they cannot be read, not
         * left to conclude the app threw their work away.
         */
        @Volatile var lockedOut: Boolean = false
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
                val sealed = app.getDatabasePath(DatabaseEncryption.ENCRYPTED_NAME)
                // Opened first, then described — see the same decision at
                // the end of this method. Whether the plaintext file
                // exists says nothing, because plain() is what creates
                // it; whether it has anything in it is the question.
                val fallback = plain(app)
                lockedOut = sealed.exists() &&
                    ((plainRows ?: 0L) <= 0L || isFreshFallback(app))
                val alsoBroken = plainFailure
                encryptionNote = if (lockedOut) {
                    "this device's keystore no longer holds the key to your " +
                        "existing cases, so they cannot be opened. They are " +
                        "still on the device, in an encrypted file that only " +
                        "that key could read, and reinstalling the app would " +
                        "delete them. This usually follows a lock-screen " +
                        "reset or a restore onto a new phone. Anything from " +
                        "here on goes into a new database."
                } else if (alsoBroken != null) {
                    // Said, rather than handing back a database whose
                    // first query will throw with nothing explaining
                    // why. The other fallback branch reports this and
                    // this one did not.
                    "this device's keystore would not provide a key, and the " +
                        "unencrypted database would not open either " +
                        "($alsoBroken)"
                } else {
                    "this device's keystore would not provide a key"
                }
                return fallback
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
            }.getOrElse { DatabaseEncryption.Move.Failed(it.message ?: "unknown") }

            // A failed move does not then open an encrypted database.
            //
            // It used to. migrateIfNeeded returned a string for both
            // outcomes, this took it as the note, and Room went on to
            // open a file the failure path had just removed — creating
            // a fresh empty one, passing the probe on it, and
            // reporting "encrypted at rest" with a failure note over
            // an empty case list, while the real database sat beside
            // it and was never read again. The rows are still in the
            // plaintext file, so that is what gets opened.
            if (moved is DatabaseEncryption.Move.Failed) {
                val fallback = plain(app)
                lockedOut = false
                encryptionNote = "${moved.note}. Your cases are being read " +
                    "from the unencrypted file, where they still are."
                return fallback
            }

            // Built and probed separately, so the probe's failure can
            // close what the build opened. Both were inside one
            // runCatching, and a probe that threw dropped the Room
            // instance on the floor: the SQLCipher connection, its WAL
            // and SHM handles and Room's invalidation tracker stayed
            // open on the encrypted file for the life of the process,
            // while the app went on to open a second database beside
            // it.
            val built = runCatching {
                Room.databaseBuilder(
                    app, AppDatabase::class.java, DatabaseEncryption.ENCRYPTED_NAME
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
            }
            val probe = built.getOrNull()?.let { db ->
                // Opened and read from here on purpose: a key, a library
                // or a migration problem has to surface now, while the
                // plaintext copy is still on disk, and not on the first
                // query from a screen.
                runCatching {
                    db.openHelper.writableDatabase
                        .query("SELECT count(*) FROM investigations")
                        .use { it.moveToFirst() }
                }
            }
            // Carried as a database and a reason rather than a
            // kotlin.Result, because the name Result in this package is
            // the Room entity for a tool's output: naming its stdlib
            // members here resolves to that data class and does not
            // compile. tools/shadowed-stdlib.py now says so locally.
            val opened = built.getOrNull()
            val stopped = probe?.exceptionOrNull() ?: built.exceptionOrNull()
            if (opened != null && stopped == null) {
                encrypted = true
                encryptionNote = when {
                    repaired != null ->
                        "recovered a database that an earlier build had " +
                            "renumbered, and encrypted at rest"
                    moved is DatabaseEncryption.Move.Moved -> moved.note
                    else -> "encrypted at rest"
                }
                // Only now is the plaintext copy of any existing database
                // no longer needed.
                runCatching { app.getDatabasePath(DatabaseEncryption.BACKUP_NAME).delete() }
                return opened
            }
            // Built but unusable: close it, or its SQLCipher connection,
            // WAL and SHM handles and invalidation tracker stay open on
            // the encrypted file for the life of the process while a
            // second database is opened beside it.
            if (opened != null) runCatching { opened.close() }

            // The encrypted database would not open. If the plaintext copy
            // is still there, that is the one with the data in it — far
            // better to run unencrypted off real cases than encrypted off
            // an empty file.
            val backup = app.getDatabasePath(DatabaseEncryption.BACKUP_NAME)
            val plainFile = app.getDatabasePath(DatabaseEncryption.PLAIN_NAME)
            val why = stopped?.message
            if (backup.exists() && !plainFile.exists()) {
                runCatching { backup.renameTo(plainFile) }
            }
            val sealed = app.getDatabasePath(DatabaseEncryption.ENCRYPTED_NAME)
            // Which failure this was.
            //
            // Three different things open this database — the keystore
            // key, the SQLCipher library, and Room's schema migrations
            // — and every one of them was reported to the user as
            // "encryption failed". A migration that throws is not an
            // encryption problem: nothing is wrong with the key, the
            // file is readable, and the fix is a build rather than a
            // new phone. Saying the wrong one sends people looking in
            // the wrong place, and in the worst case makes them
            // reinstall over a database that was fine.
            val blame = whatFailed(why)

            // Opened before the note is written, because the note makes
            // a claim about what is in it.
            //
            // "The unencrypted copy of your cases is being used —
            // nothing was lost" used to be decided by the plaintext file
            // merely existing. On the first launch after a failure that
            // file does not exist, so the note was honest; then plain()
            // created an empty one. On the second launch the file
            // existed, so the app told the user nothing had been lost
            // while showing them an empty case list — with the real
            // database sitting intact on disk a few bytes away. The
            // reasonable response to that message is to reinstall, and
            // reinstalling is the one thing that destroys it.
            val fallback = plain(app)
            val rows = plainRows
            // "Has cases" means it had them BEFORE this, not that the
            // user has since made one.
            //
            // Both this and the keystore branch decided from "does the
            // fallback have any rows", so creating a single case after
            // the failure flipped it: the long warning naming fifty
            // unreadable cases and saying not to reinstall collapsed
            // to "the keystore would not provide a key", and the other
            // branch started saying "nothing was lost". The sealed
            // file is still there in both cases, so the warning
            // belongs there until it is not.
            val plainHasCases = rows != null && rows > 0L && !isFreshFallback(app)
            lockedOut = sealed.exists() && !plainHasCases
            encryptionNote = when {
                plainHasCases ->
                    "$blame ($why), so the unencrypted copy of your " +
                        "cases is being used — nothing was lost, but nothing is " +
                        "encrypted either"
                plainFailure != null ->
                    "$blame ($why), and the unencrypted database would " +
                        "not open either ($plainFailure). Your existing cases " +
                        "cannot be read. They have not been deleted — do not " +
                        "reinstall, which would delete them."
                lockedOut ->
                    "the encrypted database on this device would not open " +
                        "— $blame ($why) — so your existing cases cannot be " +
                        "read. They have not been deleted; they are still on " +
                        "this device, and reinstalling the app would delete " +
                        "them. Anything from here on goes into a new database."
                else ->
                    "$blame ($why); the database is readable by " +
                        "anything that can read this app's files"
            }
            return fallback
        }

        /** Names the part that actually failed, from what it threw. */
        private fun whatFailed(why: String?): String {
            val m = why.orEmpty().lowercase()
            return when {
                // The library first, because its failure message
                // contains the word that the key branch matches on:
                // `dlopen failed: library "libsqlcipher.so" not found`
                // was being reported as "the stored key no longer
                // opens this database", which is the mis-blame that
                // sends somebody to reinstall over an intact one.
                m.contains("unsatisfiedlink") || m.contains("dlopen") ||
                    m.contains("library") || m.contains(".so") ->
                    "the encryption library would not load on this device"
                m.contains("migration") || m.contains("schema") ||
                    m.contains("downgrade") || m.contains("version") ->
                    "the database could not be brought up to this build's " +
                        "schema — this is a bug in the app, not a problem " +
                        "with your device"
                m.contains("not a database") || m.contains("encrypted") ||
                    m.contains("sqlcipher") || m.contains("key") ->
                    "the stored key no longer opens this database"
                else -> "the encrypted database could not be opened"
            }
        }

        /**
         * How many investigations the unencrypted database holds, or
         * null if it would not open. Set by [plain].
         */
        /**
         * Whether the unencrypted database is one this app created
         * after a failure, rather than the one holding the user's
         * work. Marked once and never unmarked: a fresh file does not
         * become the original by having things put in it.
         */
        private fun isFreshFallback(app: Context): Boolean =
            File(app.getDatabasePath(DatabaseEncryption.PLAIN_NAME).parentFile, FRESH_MARKER)
                .exists()

        private fun markFreshFallback(app: Context) {
            runCatching {
                File(
                    app.getDatabasePath(DatabaseEncryption.PLAIN_NAME).parentFile,
                    FRESH_MARKER
                ).createNewFile()
            }
        }

        private var plainRows: Long? = null

        /** Why the unencrypted database would not open, if it would not. */
        private var plainFailure: String? = null

        private fun plain(app: Context): AppDatabase {
            val db = Room.databaseBuilder(
                app, AppDatabase::class.java, DatabaseEncryption.PLAIN_NAME
            ).addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
            ).build()
            // Pre-flighted exactly as the encrypted path is, and for a
            // stronger reason: this is the path most likely to be
            // running a database from an older build, and it used to
            // hand back an unopened builder. A migration that throws
            // then surfaced as a crash out of whichever screen queried
            // first, with the note still blaming the keystore. The count
            // is also what the note needs to know whether this file has
            // anything in it.
            plainRows = runCatching {
                db.openHelper.writableDatabase
                    .query("SELECT count(*) FROM investigations")
                    .use { if (it.moveToFirst()) it.getLong(0) else 0L }
            }.getOrElse { failure ->
                plainFailure = failure.message ?: failure::class.java.simpleName
                null
            }
            // Marked the moment an empty one is created while a sealed
            // database exists, so that every later launch knows this
            // file is not the user's work however much goes into it.
            if (plainRows == 0L &&
                app.getDatabasePath(DatabaseEncryption.ENCRYPTED_NAME).exists()
            ) {
                markFreshFallback(app)
            }
            return db
        }
    }
}
