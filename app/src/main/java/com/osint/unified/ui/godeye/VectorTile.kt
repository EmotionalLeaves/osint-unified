package com.osint.unified.ui.godeye

import java.io.IOException
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Buildings out of a Mapbox Vector Tile, and nothing else.
 *
 * The 3D buildings come from OpenFreeMap's OpenMapTiles-schema tiles,
 * which carry OpenStreetMap's footprints with a `render_height` and
 * `render_min_height` worked out from the height and level tags. A
 * vector tile is a protocol buffer; this reads just enough of the
 * format to pull out the `building` layer's outer rings and those
 * heights. No library: the format is small, and the parts used here are
 * a few dozen lines.
 *
 * The spec: github.com/mapbox/vector-tile-spec, version 2.
 */
internal object VectorTile {

    /** One footprint, as latitude/longitude pairs, with its heights in metres. */
    class Building(
        /** lat0, lon0, lat1, lon1, … — the outer ring, not closed. */
        val ring: DoubleArray,
        val height: Float,
        val minHeight: Float,
        /** ARGB, or null for the default. */
        val colour: Int?,
        /** Drawn flat: OpenMapTiles marks parts that should not be extruded. */
        val flat: Boolean
    ) {
        val points: Int get() = ring.size / 2
    }

    /** The heights below this are a building's default, not a measurement. */
    const val DEFAULT_HEIGHT = 8f

    /**
     * Every building in tile [z]/[x]/[y], decoded from [bytes].
     *
     * Throws on bytes that are not a vector tile, so a broken download is
     * never read as a street with no buildings on it.
     */
    fun buildings(bytes: ByteArray, z: Int, x: Int, y: Int): List<Building> {
        val tile = Reader(bytes, 0, bytes.size)
        val out = ArrayList<Building>()
        var sawLayer = false
        while (tile.more()) {
            val (field, wire) = tile.key()
            if (field == 3 && wire == 2) {
                sawLayer = true
                val layer = tile.sub()
                readLayer(layer, z, x, y, out)
            } else {
                tile.skip(wire)
            }
        }
        if (!sawLayer && bytes.isNotEmpty()) throw IOException("not a vector tile")
        return out
    }

    private fun readLayer(layer: Reader, z: Int, x: Int, y: Int, out: MutableList<Building>) {
        // A layer's name can come after its features in the bytes, so the
        // features are kept as slices until the name is known.
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        val features = ArrayList<Reader>()
        while (layer.more()) {
            val (field, wire) = layer.key()
            when {
                field == 1 && wire == 2 -> name = layer.string()
                field == 2 && wire == 2 -> features.add(layer.sub())
                field == 3 && wire == 2 -> keys.add(layer.string())
                field == 4 && wire == 2 -> values.add(readValue(layer.sub()))
                field == 5 && wire == 0 -> extent = layer.varint().toInt()
                else -> layer.skip(wire)
            }
        }
        if (name != "building" || extent <= 0) return
        features.forEach { f -> readFeature(f, keys, values, extent, z, x, y)?.let(out::add) }
    }

    private fun readValue(v: Reader): Any? {
        var value: Any? = null
        while (v.more()) {
            val (field, wire) = v.key()
            value = when {
                field == 1 && wire == 2 -> v.string()
                field == 2 && wire == 5 -> Float.fromBits(v.fixed32())
                field == 3 && wire == 1 -> Double.fromBits(v.fixed64())
                field == 4 && wire == 0 -> v.varint()
                field == 5 && wire == 0 -> v.varint()
                field == 6 && wire == 0 -> v.varint().let { (it ushr 1) xor -(it and 1) }
                field == 7 && wire == 0 -> v.varint() != 0L
                else -> { v.skip(wire); value }
            }
        }
        return value
    }

    private fun readFeature(
        f: Reader,
        keys: List<String>,
        values: List<Any?>,
        extent: Int,
        z: Int,
        x: Int,
        y: Int
    ): Building? {
        var type = 0
        var tags = IntArray(0)
        var geometry = IntArray(0)
        while (f.more()) {
            val (field, wire) = f.key()
            when {
                field == 2 && wire == 2 -> tags = f.packed()
                field == 3 && wire == 0 -> type = f.varint().toInt()
                field == 4 && wire == 2 -> geometry = f.packed()
                else -> f.skip(wire)
            }
        }
        if (type != POLYGON) return null
        var height = DEFAULT_HEIGHT
        var minHeight = 0f
        var colour: Int? = null
        var flat = false
        var i = 0
        while (i + 1 < tags.size) {
            val key = keys.getOrNull(tags[i])
            val value = values.getOrNull(tags[i + 1])
            when (key) {
                "render_height" -> (value as? Number)?.toFloat()?.let { height = it }
                "render_min_height" -> (value as? Number)?.toFloat()?.let { minHeight = it }
                "colour" -> colour = (value as? String)?.let(::parseColour)
                "hide_3d" -> flat = value == true
            }
            i += 2
        }
        val ring = outerRing(geometry, extent, z, x, y) ?: return null
        return Building(ring, height.coerceIn(0f, MAX_HEIGHT), minHeight.coerceIn(0f, MAX_HEIGHT), colour, flat)
    }

    /**
     * The first ring of a polygon, which the spec makes the exterior.
     * Holes are dropped: a courtyard drawn as roof is a smaller wrong
     * than the cost of cutting it out on every frame.
     */
    private fun outerRing(geometry: IntArray, extent: Int, z: Int, x: Int, y: Int): DoubleArray? {
        var cx = 0
        var cy = 0
        var i = 0
        val pts = ArrayList<Int>()
        while (i < geometry.size) {
            val command = geometry[i] and 0x7
            val count = geometry[i] ushr 3
            i++
            when (command) {
                MOVE_TO, LINE_TO -> {
                    if (command == MOVE_TO && pts.isNotEmpty()) return finish(pts, extent, z, x, y)
                    repeat(count) {
                        if (i + 1 >= geometry.size) return@repeat
                        cx += zigzag(geometry[i]); cy += zigzag(geometry[i + 1])
                        i += 2
                        pts.add(cx); pts.add(cy)
                    }
                }
                CLOSE_PATH -> return finish(pts, extent, z, x, y)
                else -> return null
            }
        }
        return if (pts.size >= 6) finish(pts, extent, z, x, y) else null
    }

    private fun finish(pts: List<Int>, extent: Int, z: Int, x: Int, y: Int): DoubleArray? {
        if (pts.size < 6) return null
        val n = 1 shl z
        val out = DoubleArray(pts.size)
        var k = 0
        while (k < pts.size) {
            val fx = (x + pts[k].toDouble() / extent) / n
            val fy = (y + pts[k + 1].toDouble() / extent) / n
            out[k] = Math.toDegrees(atan(sinh(PI * (1 - 2 * fy))))
            out[k + 1] = fx * 360.0 - 180.0
            k += 2
        }
        return out
    }

    private fun zigzag(v: Int): Int = (v ushr 1) xor -(v and 1)

    private fun parseColour(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        val rgb = when (hex.length) {
            6 -> hex.toLongOrNull(16)
            3 -> hex.map { "$it$it" }.joinToString("").toLongOrNull(16)
            else -> null
        } ?: return null
        return (0xFF000000L or rgb).toInt()
    }

    /** Protocol-buffer reading over a slice of a byte array. */
    private class Reader(private val b: ByteArray, private var pos: Int, private val end: Int) {
        fun more() = pos < end

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (pos >= end) throw IOException("truncated varint")
                val byte = b[pos++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("varint too long")
            }
        }

        fun key(): Pair<Int, Int> {
            val k = varint()
            return (k ushr 3).toInt() to (k and 7).toInt()
        }

        fun sub(): Reader {
            val len = varint().toInt()
            if (len < 0 || pos + len > end) throw IOException("truncated field")
            val r = Reader(b, pos, pos + len)
            pos += len
            return r
        }

        fun string(): String {
            val r = sub()
            return String(b, r.pos, r.end - r.pos, Charsets.UTF_8)
        }

        fun packed(): IntArray {
            val r = sub()
            val out = ArrayList<Int>()
            while (r.more()) out.add(r.varint().toInt())
            return out.toIntArray()
        }

        fun fixed32(): Int {
            if (pos + 4 > end) throw IOException("truncated fixed32")
            var v = 0
            for (i in 0 until 4) v = v or ((b[pos + i].toInt() and 0xFF) shl (8 * i))
            pos += 4
            return v
        }

        fun fixed64(): Long {
            if (pos + 8 > end) throw IOException("truncated fixed64")
            var v = 0L
            for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return v
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> sub()
                5 -> pos += 4
                else -> throw IOException("unknown wire type $wire")
            }
            if (pos > end) throw IOException("truncated")
        }
    }

    private const val POLYGON = 3
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val CLOSE_PATH = 7

    /** Taller than anything built; caps a bad tag rather than drawing a spike to space. */
    private const val MAX_HEIGHT = 900f
}
