package com.osint.unified.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Moving an existing case database inside the encryption, without ever
 * being in a position to lose it.
 *
 * The plaintext file is only deleted after the encrypted copy has been
 * opened independently and found to hold the same number of rows in every
 * table. Anything that goes wrong anywhere in here leaves the original
 * exactly where it was and the app runs unencrypted, which is what it did
 * yesterday — a database nobody can open is a far worse outcome than one
 * a forensic image could read.
 */
internal object DatabaseEncryption {

    const val PLAIN_NAME = "osint-unified.db"
    const val ENCRYPTED_NAME = "osint-unified-enc.db"
    private const val WORKING_NAME = "osint-unified-enc.db.migrating"

    /**
     * The plaintext database is kept under this name rather than deleted.
     *
     * It is removed only once Room has actually opened the encrypted copy
     * and read from it. Deleting it at the moment the bytes were copied
     * left no way back from any later failure, and the first version of
     * this code had one.
     */
    const val BACKUP_NAME = "osint-unified.db.pre-encryption"

    /**
     * Copies [PLAIN_NAME] into [ENCRYPTED_NAME] if the first exists and the
     * second does not. Returns a description of what happened, for the
     * screen that reports on it; null means there was nothing to do.
     */
    /**
     * What the one-off move did, as three distinct answers.
     *
     * It returned `String?`, where null meant "nothing to do" and a
     * string meant either success or failure — and the caller could
     * not tell which, so it took a failure note and carried on to
     * open an encrypted database that the failure path had just
     * deleted. Room then created a fresh empty one, the probe
     * succeeded on it, and the app reported `encrypted = true` with
     * the note "the copy did not match the original, so nothing was
     * changed" over an empty case list — while the real database sat
     * on disk and was never looked at again, because the next launch
     * short-circuits on the encrypted file existing.
     *
     * A signature that cannot express failure will keep producing
     * that bug. This one can.
     */
    sealed interface Move {
        /** No plaintext database, or the move has already happened. */
        object NothingToDo : Move

        /** The rows are in the encrypted file now. */
        data class Moved(val note: String) : Move

        /** They are not, and the plaintext file still has them. */
        data class Failed(val note: String) : Move
    }

    fun migrateIfNeeded(context: Context, passphrase: ByteArray): Move {
        val plain = context.getDatabasePath(PLAIN_NAME)
        val encrypted = context.getDatabasePath(ENCRYPTED_NAME)
        if (!plain.exists() || encrypted.exists()) return Move.NothingToDo

        val working = File(encrypted.parentFile, WORKING_NAME)
        cleanUp(working)

        return try {
            // Everything in the write-ahead log, folded into the file
            // itself, before anything is copied or renamed.
            //
            // Room runs in WAL mode, so after an unclean shutdown the
            // committed-but-uncheckpointed pages — which is to say the
            // last session's work — live in -wal and not in the
            // database. copyInto ATTACHes a live connection, so the
            // encrypted copy picked them up; renameTo moves one file,
            // so the backup did not. The backup was therefore a strictly
            // older snapshot than the thing it was insuring against, and
            // the fallback that reads it says "nothing was lost".
            val checkpointed = checkpoint(plain)
            val before = inspectPlaintext(plain)
            copyInto(context, working, passphrase, plain, before.version)
            // Opened at the version the file now has. Opening it at any
            // other number makes the helper treat the difference as an
            // upgrade or a downgrade and stamp the file to match — which
            // is precisely what went wrong: the check re-stamped a
            // version-4 database as version 1, Room then replayed every
            // migration against a schema that already had them, and the
            // whole case history ended up stranded in a file nothing
            // would open.
            val after = countTables(context, WORKING_NAME, passphrase, before.version, before.counts.keys)
            if (after != before.counts) {
                cleanUp(working)
                Move.Failed(
                    "the copy did not match the original ($before -> $after), " +
                        "so nothing was changed and your cases are still in the " +
                        "unencrypted file"
                )
            } else if (!working.renameTo(encrypted)) {
                cleanUp(working)
                Move.Failed("the encrypted copy could not be put in place")
            } else {
                // Kept, not deleted. It is removed once Room has opened
                // the encrypted database and read a row out of it.
                val backup = File(plain.parentFile, BACKUP_NAME)
                cleanUp(backup)
                val kept = plain.renameTo(backup)
                // The sidecars go either way; the main file only once
                // there is a backup of it.
                //
                // cleanUp was unconditional, and renameTo's answer was
                // discarded. That is right for -wal and -shm, which are
                // unencrypted copies of case data and must not be left
                // lying in the databases directory whatever happens. It
                // was wrong for the database: a rename that failed —
                // a stale lock, something already at that name — left
                // the app with no backup and no original, and if Room
                // then could not open the encrypted copy there was
                // nothing at all to fall back to.
                if (kept) {
                    // And only now the sidecars, and only if the
                    // checkpoint above actually worked.
                    //
                    // They were deleted unconditionally, before this
                    // branch. A checkpoint that silently failed — the
                    // file still open, the pragma refused — leaves the
                    // last session's committed pages in -wal and
                    // nowhere else, so deleting it destroyed them and
                    // the "backup" became the older snapshot this was
                    // written to stop it being. If the checkpoint did
                    // not take, the sidecars go WITH the backup
                    // instead of into the bin.
                    if (checkpointed) {
                        sidecarsOf(plain).forEach { runCatching { it.delete() } }
                    } else {
                        moveSidecars(plain, backup)
                    }
                    runCatching { plain.delete() }
                    Move.Moved(
                        "moved ${before.counts.values.sum()} rows into an " +
                            "encrypted database"
                    )
                } else {
                    // No backup, so the original stays exactly where
                    // the fallback path looks for it — with its
                    // write-ahead log, which is part of it.
                    cleanUp(encrypted)
                    Move.Failed(
                        "the original database could not be set aside, so it " +
                            "has been left as it was and nothing is encrypted"
                    )
                }
            }
        } catch (e: Exception) {
            cleanUp(working)
            Move.Failed(
                "could not encrypt the existing database (${e.message}); it is " +
                    "being used as it was"
            )
        }
    }

    /** The journal, log and shared-memory files, renamed beside [to]. */
    private fun moveSidecars(from: File, to: File) {
        listOf("-journal", "-wal", "-shm").forEach { suffix ->
            val source = File(from.parentFile, "${from.name}$suffix")
            if (!source.exists()) return@forEach
            val target = File(to.parentFile, "${to.name}$suffix")
            runCatching { target.delete() }
            if (!source.renameTo(target)) runCatching { source.delete() }
        }
    }

    private data class Plaintext(val version: Int, val counts: Map<String, Int>) {
        override fun toString() = counts.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    /** Row counts and schema version, read with the platform's own SQLite. */
    private fun inspectPlaintext(file: File): Plaintext {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            file.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        db.use {
            val tables = ArrayList<String>()
            it.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
                null
            ).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
            val counts = tables.associateWith { table ->
                it.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
            }
            return Plaintext(it.version, counts)
        }
    }

    /**
     * Creates the encrypted file and copies the plaintext database into it.
     *
     * sqlcipher_export does the work; it is given the attached plaintext as
     * its source and the encrypted connection as its destination. The
     * schema version has to be carried across by hand — it is not part of
     * what gets exported, and Room reads it to decide which migrations to
     * run.
     */
    private fun copyInto(
        context: Context,
        working: File,
        passphrase: ByteArray,
        plain: File,
        version: Int
    ) {
        val helper = open(context, working.name, passphrase, version)
        helper.use {
            val db = it.writableDatabase
            db.execSQL("ATTACH DATABASE ? AS plaintext KEY ''", arrayOf(plain.absolutePath))
            db.query("SELECT sqlcipher_export('main', 'plaintext')").use { c -> c.moveToFirst() }
            db.execSQL("PRAGMA main.user_version = $version")
            db.execSQL("DETACH DATABASE plaintext")
        }
    }

    /** Re-opens the encrypted file from scratch and counts it. */
    private fun countTables(
        context: Context,
        name: String,
        passphrase: ByteArray,
        version: Int,
        tables: Set<String>
    ): Map<String, Int> {
        val helper = open(context, name, passphrase, version)
        helper.use {
            val db = it.writableDatabase
            return tables.associateWith { table ->
                db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
            }
        }
    }

    /**
     * An encrypted connection that creates and migrates nothing.
     *
     * Room owns the schema; this is only here to move bytes, so the
     * callbacks are deliberately empty and the version is whatever the
     * caller is carrying across.
     */
    private fun open(
        context: Context,
        name: String,
        passphrase: ByteArray,
        version: Int
    ): SupportSQLiteOpenHelper {
        // onCreate is permitted — that is how the empty encrypted file is
        // made — but a version mismatch throws rather than being waved
        // through. The helper writes the new version number at the end of
        // the upgrade transaction, so a callback that returns quietly is a
        // callback that renumbers the database. That is the whole of the
        // bug in r173: a check that opened a version-4 file as version 1
        // left it stamped 1, and Room then replayed four migrations over a
        // schema that already had them.
        val callback = object : SupportSQLiteOpenHelper.Callback(maxOf(version, 1)) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                throw VersionMismatch(oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                throw VersionMismatch(oldVersion, newVersion)
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(callback)
            .build()
        return SupportOpenHelperFactory(passphrase).create(configuration)
    }

    /**
     * Puts the schema version back where the schema says it should be.
     *
     * Builds r173 to r176 stamped the encrypted database as version 1
     * while its tables were at version 3 or 4. Room then replayed the
     * migrations, hit "table already exists", and the app fell back to a
     * new empty database while the real one sat there unopened. Nothing
     * was corrupted — only the number at the top of the file was wrong —
     * so it can be put back by looking at which tables and columns are
     * actually present.
     *
     * Returns the version it set, or null if there was nothing to fix.
     */
    fun repairVersion(context: Context, passphrase: ByteArray): Int? = runCatching {
        if (!context.getDatabasePath(ENCRYPTED_NAME).exists()) return@runCatching null
        val claimed = probeVersion(context, passphrase) ?: return@runCatching null

        open(context, ENCRYPTED_NAME, passphrase, claimed).use { helper ->
            val db = helper.writableDatabase
            fun hasTable(name: String): Boolean =
                db.query(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf<Any>(name)
                ).use { c -> c.moveToFirst() }
            fun hasColumn(table: String, column: String): Boolean =
                runCatching {
                    db.query("PRAGMA table_info(`$table`)").use { c ->
                        val index = c.getColumnIndex("name")
                        if (index < 0) return@runCatching false
                        while (c.moveToNext()) {
                            if (c.getString(index) == column) return@runCatching true
                        }
                        false
                    }
                }.getOrDefault(false)

            val actual = when {
                hasTable("cases") -> 7
                hasTable("dismissals") -> 6
                hasColumn("results", "outputSha256") -> 5
                hasTable("watch_events") -> 4
                hasColumn("investigations", "parentId") -> 3
                hasTable("watches") -> 2
                hasTable("investigations") -> 1
                else -> return@runCatching null
            }
            if (actual == claimed) null else {
                db.execSQL("PRAGMA user_version = $actual")
                actual
            }
        }
    }.getOrNull()

    /** Raised instead of silently renumbering a database. */
    private class VersionMismatch(val from: Int, val to: Int) :
        RuntimeException("database is version $from, opened as $to")

    /**
     * What version the encrypted file is, found by asking.
     *
     * There is no way to read it without opening the file, and opening it
     * at the wrong version is what rewrites it — so each candidate is
     * tried and the one that opens without a mismatch is the answer. The
     * mismatches throw before the helper reaches the line that would
     * renumber anything.
     */
    private fun probeVersion(context: Context, passphrase: ByteArray): Int? {
        for (candidate in AppDatabase.SCHEMA_VERSION downTo 1) {
            val found = runCatching {
                open(context, ENCRYPTED_NAME, passphrase, candidate).use {
                    it.writableDatabase.version
                }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    /** A half-written file is worse than no file. */
    private fun cleanUp(file: File) {
        runCatching { file.delete() }
        sidecarsOf(file).forEach { runCatching { it.delete() } }
    }

    /** The journal, write-ahead log and shared-memory files beside a database. */
    private fun sidecarsOf(file: File): List<File> = listOf(
        File(file.parentFile, "${file.name}-journal"),
        File(file.parentFile, "${file.name}-wal"),
        File(file.parentFile, "${file.name}-shm"),
    )

    /**
     * Fold a database's write-ahead log back into the file.
     *
     * TRUNCATE rather than PASSIVE: PASSIVE gives up if any other
     * connection is reading, and the whole point here is that after
     * this call the single file is the whole database.
     */
    private fun checkpoint(file: File): Boolean = runCatching {
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            // The pragma's own answer: the first column is 1 when the
            // checkpoint was blocked. runCatching alone made a refusal
            // indistinguishable from success, and the caller then
            // deleted the log it had failed to fold in.
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                if (!c.moveToFirst()) return@runCatching false
                c.getInt(0) == 0
            }
        }
    }.getOrDefault(false)
}
