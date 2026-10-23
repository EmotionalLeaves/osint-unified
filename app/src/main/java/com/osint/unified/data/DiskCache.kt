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
        if (System.currentTimeMillis() - f.lastModified() > maxAgeMs) return null
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

    fun write(name: String, body: String) {
        runCatching { fileFor(name).writeText(body) }
    }
}
