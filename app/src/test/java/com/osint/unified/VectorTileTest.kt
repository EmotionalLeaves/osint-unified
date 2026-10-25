package com.osint.unified

import com.osint.unified.ui.godeye.VectorTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The building reader, against tiles encoded here to the vector tile
 * spec: a layer of strings and values, features with tags and
 * zig-zag geometry commands.
 */
class VectorTileTest {

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun key(out: ByteArrayOutputStream, field: Int, wire: Int) = varint(out, ((field shl 3) or wire).toLong())

    private fun bytes(out: ByteArrayOutputStream, field: Int, body: ByteArray) {
        key(out, field, 2); varint(out, body.size.toLong()); out.write(body)
    }

    private fun msg(build: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(build).toByteArray()

    private fun packed(values: List<Int>) = msg { o -> values.forEach { varint(o, it.toLong() and 0xFFFFFFFFL) } }

    private fun zz(v: Int) = (v shl 1) xor (v shr 31)

    /** A square from (1024,1024) to (2048,2048) in a 4096 extent. */
    private val square = listOf(
        (1 and 7) or (1 shl 3), zz(1024), zz(1024),
        (2 and 7) or (3 shl 3), zz(1024), zz(0), zz(0), zz(1024), zz(-1024), zz(0),
        7 or (1 shl 3)
    )

    private fun layer(name: String, features: List<ByteArray>, keys: List<String>, values: List<ByteArray>) = msg { o ->
        bytes(o, 1, name.toByteArray())
        features.forEach { bytes(o, 2, it) }
        keys.forEach { bytes(o, 3, it.toByteArray()) }
        values.forEach { bytes(o, 4, it) }
        key(o, 5, 0); varint(o, 4096)
    }

    private fun feature(tags: List<Int>, type: Int = 3, geometry: List<Int> = square) = msg { o ->
        bytes(o, 2, packed(tags))
        key(o, 3, 0); varint(o, type.toLong())
        bytes(o, 4, packed(geometry))
    }

    private val doubleValue = { d: Double -> msg { o -> key(o, 3, 1); val b = java.lang.Double.doubleToLongBits(d); for (i in 0 until 8) o.write(((b ushr (8 * i)) and 0xFF).toInt()) } }
    private val stringValue = { s: String -> msg { o -> bytes(o, 1, s.toByteArray()) } }
    private val boolValue = { b: Boolean -> msg { o -> key(o, 7, 0); varint(o, if (b) 1 else 0) } }

    private val tile = msg { o ->
        // A layer that is not buildings, first, to be skipped.
        bytes(o, 3, layer("water", listOf(feature(emptyList())), emptyList(), emptyList()))
        bytes(
            o, 3,
            layer(
                "building",
                listOf(
                    feature(listOf(0, 0, 1, 1, 2, 2)),
                    feature(listOf(3, 3)),
                    // A line is not a footprint.
                    feature(emptyList(), type = 2)
                ),
                listOf("render_height", "render_min_height", "colour", "hide_3d"),
                listOf(doubleValue(42.5), doubleValue(6.0), stringValue("#a0b0c0"), boolValue(true))
            )
        )
    }

    @Test
    fun buildingsAreReadWithTheirHeights() {
        val read = VectorTile.buildings(tile, 14, 8192, 8192)
        assertEquals(2, read.size)
        val tall = read[0]
        assertEquals(42.5f, tall.height, 1e-4f)
        assertEquals(6f, tall.minHeight, 1e-4f)
        assertEquals(0xFFA0B0C0.toInt(), tall.colour)
        assertEquals(4, tall.points)
        // The second has only hide_3d, so the default height and no colour.
        assertEquals(VectorTile.DEFAULT_HEIGHT, read[1].height, 1e-4f)
        assertTrue(read[1].flat)
    }

    /** Tile 14/8192/8192 starts at 0,0; a quarter of the way in is a quarter tile east and south. */
    @Test
    fun theCornersLandWhereTheTileSaysTheyAre() {
        val b = VectorTile.buildings(tile, 14, 8192, 8192)[0]
        val tileDeg = 360.0 / (1 shl 14)
        assertEquals(tileDeg / 4, b.ring[1], 1e-9) // lon of first point
        assertTrue("south of the equator", b.ring[0] < 0)
        assertEquals(-tileDeg / 4, b.ring[0], 1e-5) // near the equator, Mercator is square
    }

    @Test
    fun garbageIsNotAnEmptyStreet() {
        var refused = false
        try {
            VectorTile.buildings(byteArrayOf(0x0A, 0x7F, 1, 2), 14, 0, 0)
        } catch (e: IOException) {
            refused = true
        }
        assertTrue(refused)
    }
}
