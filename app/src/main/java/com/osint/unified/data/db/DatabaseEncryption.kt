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
     * Copies [PLAIN_NAME] into [ENCRYPTED_NAME] if the first exists and the
     * second does not. Returns a description of what happened, for the
     * screen that reports on it; null means there was nothing to do.
     */
    fun migrateIfNeeded(context: Context, passphrase: ByteArray): String? {
        val plain = context.getDatabasePath(PLAIN_NAME)
        val encrypted = context.getDatabasePath(ENCRYPTED_NAME)
        if (!plain.exists() || encrypted.exists()) return null

        val working = File(encrypted.parentFile, WORKING_NAME)
        cleanUp(working)

        return try {
            val before = inspectPlaintext(plain)
            copyInto(context, working, passphrase, plain, before.version)
            val after = countTables(context, WORKING_NAME, passphrase, before.counts.keys)
            if (after != before.counts) {
                cleanUp(working)
                "the copy did not match the original ($before -> $after), " +
                    "so nothing was changed"
            } else if (!working.renameTo(encrypted)) {
                cleanUp(working)
                "the encrypted copy could not be put in place"
            } else {
                // Only now, with a verified encrypted copy in place.
                cleanUp(plain)
                "moved ${before.counts.values.sum()} rows into an encrypted database"
            }
        } catch (e: Exception) {
            cleanUp(working)
            "could not encrypt the existing database (${e.message}); it is " +
                "being used as it was"
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
        tables: Set<String>
    ): Map<String, Int> {
        val helper = open(context, name, passphrase, 0)
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
        val callback = object : SupportSQLiteOpenHelper.Callback(maxOf(version, 1)) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(callback)
            .build()
        return SupportOpenHelperFactory(passphrase).create(configuration)
    }

    /** A half-written file is worse than no file. */
    private fun cleanUp(file: File) {
        runCatching { file.delete() }
        runCatching { File(file.parentFile, "${file.name}-journal").delete() }
        runCatching { File(file.parentFile, "${file.name}-wal").delete() }
        runCatching { File(file.parentFile, "${file.name}-shm").delete() }
    }
}
