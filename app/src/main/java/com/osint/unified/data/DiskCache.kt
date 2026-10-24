package com.osint.unified.data

import android.content.Context
import java.io.File

/**
 * A small on-disk cache for feeds that are large and slow to change.
 *
 * Two sources need this. Celestrak's orbital catalogue is seven megabytes
 * and they rate-limit repeat callers — a health check caught it returning
 * 403 after a handful of fetches — and the IRIS station list is fourteen
 * megabytes of essentially static infrastructure. Re-downloading either on
 * a poll interval wastes the user's data and risks being blocked.
 *
 * Caching them on disk means a restart does not refetch, and a source that
 * is temporarily refusing keeps working from the last good copy.
 */
class DiskCache(private val context: Context) {

    private fun fileFor(name: String) = File(context.cacheDir, "feed-$name")

    /** The cached body if it exists and is younger than [maxAgeMs]. */
    fun read(name: String, maxAgeMs: Long): String? {
        val f = fileFor(name)
        if (!f.exists() || f.length() < 64) return null
        // coerceAtLeast, as `ageMillis` two functions below already
        // does. Without it a clock that has moved backwards makes
        // the difference negative, the test never fires, and a
        // stale entry is served as fresh for ever — in the one
        // function whose whole job is deciding freshness.
        val age = (System.currentTimeMillis() - f.lastModified()).coerceAtLeast(0L)
        if (age > maxAgeMs) return null
        return runCatching { f.readText() }.getOrNull()
    }

    /** The cached body whatever its age — the fallback when a source is
     *  refusing or unreachable. Stale data clearly beats an empty layer. */
    fun readStale(name: String): String? {
        val f = fileFor(name)
        if (!f.exists() || f.length() < 64) return null
        return runCatching { f.readText() }.getOrNull()
    }

    /**
     * How old the cached copy is, or null if there is not one.
     *
     * [readStale] hands back whatever is on disk with no indication of
     * its age, which is right for a last resort and wrong for a caller
     * that then reports success. This lets one decide how old is too
     * old.
     */
    fun ageMillis(name: String): Long? {
        val f = fileFor(name)
        if (!f.exists()) return null
        val stamp = f.lastModified()
        if (stamp <= 0L) return null
        return (System.currentTimeMillis() - stamp).coerceAtLeast(0L)
    }

    /**
     * Writes to a temporary file and renames it over the target.
     *
     * `writeText` truncates and then writes, so a process killed
     * partway through the seven-megabyte Celestrak catalogue left a
     * truncated file — and [read] validates only that the file is
     * at least 64 bytes and young enough, so the half-written body
     * was served for the whole day, and by [readStale] for ever
     * after that. "A source that is temporarily refusing keeps
     * working from the last good copy", which is this class's
     * stated purpose, became "keeps working from a corrupt copy".
     *
     * A rename within one filesystem is atomic, so a reader sees
     * either the old file or the whole new one and never half of
     * either.
     *
     * Returns whether it worked. It used to return Unit and swallow
     * everything, so a full disk or an unwritable cache directory
     * was invisible: every caller believed it had cached, and
     * re-downloaded seven and fourteen megabytes on every poll with
     * nothing said to the data meter or to the person paying for
     * it.
     */
    fun write(name: String, body: String): Boolean {
        val target = fileFor(name)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                // Some filesystems refuse a rename over an existing
                // file. Deleting first is a smaller window than
                // writing in place, which is the whole window.
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("could not replace $name")
            }
            true
        }.getOrElse {
            runCatching { tmp.delete() }
            false
        }
    }
}
