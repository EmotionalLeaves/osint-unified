package com.osint.unified.ui.godeye

import android.content.Context
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Many lines on the Earth, packed: the detailed country borders and the
 * state and province lines, as written by CI's make-lines-bin.py.
 *
 * Each line carries its own box, so the globe draws only the lines near
 * what it is looking at — a million and a half points are fine in memory
 * and impossible to project every frame.
 */
internal class LineSet(
    /** lon, lat, lon, lat… for every line, one after another. */
    val coords: FloatArray,
    /** Where each line starts in [coords], in points. */
    val starts: IntArray,
    val counts: IntArray,
    /** minLon, minLat, maxLon, maxLat for each line. */
    val boxes: FloatArray
) {
    val size: Int get() = starts.size

    companion object {
        /** Reads the packed form. Throws on anything else. */
        fun read(bytes: ByteArray): LineSet {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 8 || b.get() != 'L'.code.toByte() || b.get() != 'N'.code.toByte() ||
                b.get() != 'S'.code.toByte() || b.get() != '1'.code.toByte()
            ) throw IOException("not a line set")
            val lines = b.int
            if (lines < 0 || lines > MAX_LINES) throw IOException("implausible line count $lines")
            val total = (bytes.size - 8 - lines * 4) / 8
            if (total < 0) throw IOException("truncated line set")
            val coords = FloatArray(total * 2)
            val starts = IntArray(lines)
            val counts = IntArray(lines)
            val boxes = FloatArray(lines * 4)
            var at = 0
            for (i in 0 until lines) {
                val n = b.int
                if (n < 0 || at + n > total) throw IOException("truncated line set")
                starts[i] = at
                counts[i] = n
                var minLon = 999f; var minLat = 999f; var maxLon = -999f; var maxLat = -999f
                for (k in 0 until n) {
                    val lon = b.float
                    val lat = b.float
                    coords[(at + k) * 2] = lon
                    coords[(at + k) * 2 + 1] = lat
                    if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
                    if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                }
                boxes[i * 4] = minLon; boxes[i * 4 + 1] = minLat
                boxes[i * 4 + 2] = maxLon; boxes[i * 4 + 3] = maxLat
                at += n
            }
            return LineSet(coords, starts, counts, boxes)
        }

        private val cache = HashMap<String, LineSet?>()

        /** A bundled set, read once. Null when the build has none. */
        @Synchronized
        fun asset(context: Context, name: String): LineSet? = cache.getOrPut(name) {
            runCatching { context.assets.open(name).use { read(it.readBytes()) } }.getOrNull()
        }

        private const val MAX_LINES = 2_000_000
    }
}
