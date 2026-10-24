package com.osint.unified

import com.osint.unified.domain.ResultFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promise in this class's own header: "any failure falls back to
 * the raw text so the user always sees something".
 *
 * It only kept that promise when something was thrown. A parse that
 * came back empty was reported as an empty result, so a card read
 * "0 subdomains" over fifty hostnames sitting in the raw text.
 */
class ResultFormatterTest {

    @Test
    fun bareHostnamesAreNotZeroSubdomains() {
        // subfinder invoked without -json emits plain lines. The
        // NDJSON reader filters to lines starting with "{", so this
        // parses to nothing at all without throwing.
        val raw = "api.example.com\nmail.example.com\nvpn.example.com\n"
        val summary = ResultFormatter.format("subfinder", raw)
        assertEquals(3, summary.lines.size)
        assertTrue(summary.headline, summary.headline.contains("3"))
    }

    @Test
    fun aTopLevelArrayFallsBackToo() {
        val raw = """[{"host":"a.example.com"},{"host":"b.example.com"}]"""
        val summary = ResultFormatter.format("subfinder", raw)
        assertTrue("fell through to an empty summary", summary.lines.isNotEmpty())
    }

    @Test
    fun realNdjsonStillParses() {
        val raw = """{"host":"api.example.com"}
{"host":"mail.example.com"}"""
        val summary = ResultFormatter.format("subfinder", raw)
        assertEquals(listOf("api.example.com", "mail.example.com"), summary.lines)
        assertEquals("2 subdomains", summary.headline)
    }

    @Test
    fun genuinelyEmptyOutputIsStillEmpty() {
        assertEquals(0, ResultFormatter.format("subfinder", "   ").lines.size)
    }
}
