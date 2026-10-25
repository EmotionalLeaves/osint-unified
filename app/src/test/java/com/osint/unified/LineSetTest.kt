package com.osint.unified

import com.osint.unified.ui.godeye.LineSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The packed border lines, as CI's make-lines-bin.py writes them. */
class LineSetTest {

    private fun pack(vararg lines: List<Pair<Float, Float>>): ByteArray {
        val size = 8 + lines.sumOf { 4 + it.size * 8 }
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("LNS1".toByteArray()); b.putInt(lines.size)
        lines.forEach { l -> b.putInt(l.size); l.forEach { (lon, lat) -> b.putFloat(lon); b.putFloat(lat) } }
        return b.array()
    }

    @Test
    fun linesAndTheirBoxesAreRead() {
        val set = LineSet.read(pack(listOf(1f to 2f, 3f to -4f, 0.5f to 1f), listOf(-170f to 60f, -169f to 61f)))
        assertEquals(2, set.size)
        assertEquals(3, set.counts[0]); assertEquals(3, set.starts[1])
        assertEquals(0.5f, set.boxes[0]); assertEquals(-4f, set.boxes[1])
        assertEquals(3f, set.boxes[2]); assertEquals(2f, set.boxes[3])
        assertEquals(-169f, set.coords[(3 + 1) * 2])
    }

    @Test
    fun aFileThatIsNotOneIsRefused() {
        var refused = false
        try { LineSet.read("{\"rings\":[]}".toByteArray()) } catch (e: IOException) { refused = true }
        assertTrue(refused)
    }
}
