package com.osint.unified

import com.osint.unified.data.db.Result as ResultRow
import com.osint.unified.domain.Findings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun theGalleryPathSaysTheSameThingAsTheUrlPath() {
        // Until today the gallery path emitted "GPS: (none)" for a
        // stripped photograph, which classified HIT — the same picture
        // read from a URL classified ABSENT. Both sentences now.
        assertEquals(
            Findings.Kind.ABSENT,
            Findings.kindOf(
                row(
                    "No EXIF metadata in this image. The camera may have written " +
                        "none, or something along the way stripped it."
                )
            )
        )
        // And it no longer rests on the word "camera" landing inside a
        // forty-four character window — which it did, with eleven
        // characters to spare, until "metadata" was a noun the
        // classifier knew.
        assertEquals(
            Findings.Kind.ABSENT,
            Findings.kindOf(row("No EXIF metadata in this image."))
        )
        // The claim it must never make again.
        assertEquals(
            Findings.Kind.HIT,
            Findings.kindOf(row("GPS: (none)\nBytes: 184320"))
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

/**
 * A scan that was killed part-way.
 *
 * The backend sends these as HTTP 206 with a `PARTIAL:` first line.
 * Nothing on this side read either until now: 206 is inside
 * `isSuccessful`, and the sentence matches no absence and no
 * unreachable phrase, so forty of four hundred subdomains were stored
 * as a complete set.
 */
class PartialResultTest {

    private fun row(status: String, raw: String) = ResultRow(
        id = 1,
        investigationId = 1,
        toolId = "subfinder",
        toolName = "subfinder",
        input = "example.com",
        status = status,
        rawOutput = raw,
        startedAt = 0L
    )

    @Test
    fun theBackendsFirstLineIsRecognised() {
        assertTrue(Findings.saysPartial("PARTIAL: subfinder was stopped after 60000 ms.\na.example.com"))
        assertTrue(Findings.saysPartial("  partial: cut off\nrest"))
        assertFalse(Findings.saysPartial("a.example.com\nb.example.com"))
        // The word has to open the body, not merely appear in it.
        assertFalse(Findings.saysPartial("Results are PARTIAL: see below"))
    }

    @Test
    fun aFragmentIsAFindingAndNeverAnAbsence() {
        val fragment = row(Findings.STATUS_PARTIAL, "PARTIAL: stopped.\na.example.com")
        assertEquals(Findings.Kind.HIT, Findings.kindOf(fragment))
        // The row it would have been without a status of its own: no
        // phrase here says absent or unreachable, so the prose path
        // called it a complete finding.
        val mislabelled = row("success", "PARTIAL: subfinder was stopped after 60000 ms.\na.example.com")
        assertEquals(Findings.Kind.HIT, Findings.kindOf(mislabelled))
        // Which is why the status, not the sentence, has to carry it.
        assertTrue(Findings.sort(listOf(fragment)).hits.isNotEmpty())
    }

    @Test
    fun aFragmentAndAnAbsenceAreBothWorthKeepingThroughAFailedRerun() {
        assertTrue(Findings.holdsAnAnswer("success"))
        assertTrue(Findings.holdsAnAnswer(Findings.STATUS_PARTIAL))
        assertTrue(Findings.holdsAnAnswer(Findings.STATUS_ABSENT))
        assertFalse(Findings.holdsAnAnswer(Findings.STATUS_UNREACHABLE))
        assertFalse(Findings.holdsAnAnswer("running"))
        assertFalse(Findings.holdsAnAnswer("error"))
        assertFalse(Findings.holdsAnAnswer("cancelled"))
    }
}
