package com.osint.unified.ui.godeye

import android.content.Context
import com.osint.unified.data.AppSettings
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The globe's own storage on the phone: every satellite tile and
 * building tile it has downloaded, kept so it is never downloaded again.
 *
 * In app storage rather than the cache directory, because Android empties
 * the cache whenever it is short of space, and a street fetched last week
 * and silently thrown away is a street paid for twice. Bounded instead by
 * the size chosen in Settings, oldest-used first.
 *
 * This replaced an HTTP cache that kept tiles for thirty days in the
 * cache directory: the right idea, held somewhere the system was free to
 * clear, and expiring things that never change.
 */
internal object GlobeDisk {

    private val used = AtomicLong(-1)
    private val writesSinceTrim = AtomicInteger(0)

    fun root(context: Context): File = File(context.applicationContext.filesDir, "globe")

    /** The file a piece of the globe lives in, whether or not it exists yet. */
    fun file(context: Context, kind: String, z: Int, x: Int, y: Int, ext: String): File =
        File(root(context), "$kind/$z/${x}_$y.$ext")

    /** The bytes, if this piece has been downloaded before. Marks it as used. */
    fun read(file: File): ByteArray? {
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            // For the oldest-first trim. Coarse on purpose: a write to the
            // file system per tile drawn would be its own cost.
            val now = System.currentTimeMillis()
            if (now - file.lastModified() > TOUCH_MS) file.setLastModified(now)
            bytes
        }.getOrNull()
    }

    /** Keeps [bytes]; trims to the limit now and then. Never throws. */
    fun write(context: Context, file: File, bytes: ByteArray) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return
            }
            if (used.get() >= 0) used.addAndGet(bytes.size.toLong())
        }
        if (writesSinceTrim.incrementAndGet() >= TRIM_EVERY) {
            writesSinceTrim.set(0)
            trim(context)
        }
    }

    /** How much the globe is keeping, in bytes. Walks the folder once, then counts. */
    fun usedBytes(context: Context): Long {
        val known = used.get()
        if (known >= 0) return known
        val total = root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        used.set(total)
        return total
    }

    /** Down to nine tenths of the limit, least recently used first. */
    fun trim(context: Context) {
        val limit = AppSettings(context).globeStorageMb.toLong() * 1024 * 1024
        if (usedBytes(context) <= limit) return
        val files = root(context).walkTopDown().filter { it.isFile }.toList()
            .sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        val target = limit * 9 / 10
        for (f in files) {
            if (total <= target) break
            val size = f.length()
            if (f.delete()) total -= size
        }
        used.set(total)
    }

    /** Everything, gone. The globe downloads again as it is used. */
    fun clear(context: Context) {
        root(context).deleteRecursively()
        used.set(0)
    }

    private const val TRIM_EVERY = 64
    private const val TOUCH_MS = 6L * 3600 * 1000
}
