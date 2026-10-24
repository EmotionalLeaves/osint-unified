package com.osint.unified

import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Findings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three sentences ExifReader can return, run through the
 * classifier.
 *
 * Two of them used to come back HIT — a refusal to read a 30 MB
 * file, and a fetch that timed out, both counted among "N of M
 * sources returned something" and drawn as findings about the
 * picture. They are one-liners and they failed before the fix.
 */
class ExifSentencesTest {

    private fun row(output: String) = ResultRow(
        id = 1,
        investigationId = 1,
        toolId = "exif",
        toolName = "EXIF / GPS",
        input = "https://example.com/a.jpg",
        status = "success",
        rawOutput = output,
        startedAt = 0L,
        finishedAt = 1L
    )

    @Test
    fun aRefusalToReadIsNotAFinding() {
        assertEquals(
            Findings.Kind.UNREACHABLE,
            Findings.kindOf(row("Could not read https://example.com/a.jpg: timeout"))
        )
    }

    @Test
    fun anImageWithNoMetadataIsAnAbsenceAndNotAFinding() {
        assertEquals(
            Findings.Kind.ABSENT,
            Findings.kindOf(
                row(
                    "No EXIF metadata in this JPEG. The camera may have written " +
                        "none, or something along the way stripped it."
                )
            )
        )
    }

    @Test
    fun anImageWithACoordinateIsAFinding() {
        assertEquals(
            Findings.Kind.HIT,
            Findings.kindOf(row("GPS: 51.5074, -0.1278\nMake: Apple\nModel: iPhone 15"))
        )
    }
}
