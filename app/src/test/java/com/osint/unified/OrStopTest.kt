package com.osint.unified

import com.osint.unified.domain.orStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The distinction eight tools in this app were not making: a source
 * that failed is a result, and the caller stopping us is not.
 */
class OrStopTest {

    @Test
    fun successPassesThrough() {
        assertEquals("ok", runCatching { "ok" }.orStop().getOrNull())
    }

    @Test
    fun anOrdinaryFailureIsStillAFailure() {
        val r = runCatching { throw java.io.IOException("host unreachable") }.orStop()
        assertTrue(r.isFailure)
        assertEquals("host unreachable", r.exceptionOrNull()?.message)
    }

    @Test
    fun cancellationIsRethrownRatherThanReported() {
        try {
            runCatching { throw CancellationException("stopped") }.orStop()
            fail("orStop swallowed a cancellation")
        } catch (expected: CancellationException) {
            assertEquals("stopped", expected.message)
        }
    }

    /**
     * The shape that produced the bug: work cancelled mid-flight, and
     * the caller needing to know the difference between that and a
     * source that answered badly.
     */
    @Test
    fun aCancelledJobDoesNotLookLikeADeadSource() = runTest {
        val job = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.Unconfined
        )
        var reportedAsFailure = false
        var sawCancellation = false
        val running = job.launch {
            try {
                val outcome = runCatching {
                    awaitCancellation()
                }.orStop()
                reportedAsFailure = outcome.isFailure
            } catch (stopped: CancellationException) {
                sawCancellation = true
                throw stopped
            }
        }
        running.cancelAndJoin()
        assertTrue("the stop should arrive as a cancellation", sawCancellation)
        assertTrue("and never as a source failure", !reportedAsFailure)
    }
}
