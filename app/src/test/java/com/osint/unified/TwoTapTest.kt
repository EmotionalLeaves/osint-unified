package com.osint.unified

import androidx.compose.runtime.mutableStateOf
import com.osint.unified.ui.common.TwoTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The confirmation that was not one: two taps in the same place with
 * nothing between them deleted an investigation.
 */
class TwoTapTest {

    private fun tap(clock: () -> Long, onConfirm: () -> Unit): TwoTap {
        val armedAt = mutableStateOf(0L)
        return TwoTap(armedAt, onConfirm, clock)
    }

    @Test
    fun oneTapOnlyArms() {
        var fired = 0
        val clock = 1_000L
        val action = tap({ clock }) { fired++ }
        action.tap()
        assertTrue(action.armed)
        assertEquals(0, fired)
    }

    @Test
    fun aDoubleTapDoesNotConfirm() {
        var fired = 0
        var at = 1_000L
        val action = tap({ at }) { fired++ }
        action.tap()
        at += 120 // a fast double tap
        action.tap()
        assertEquals("a double tap is one decision, not two", 0, fired)
        assertTrue("and it leaves the button armed", action.armed)
    }

    @Test
    fun aDeliberateSecondTapConfirms() {
        var fired = 0
        var at = 1_000L
        val action = tap({ at }) { fired++ }
        action.tap()
        at += TwoTap.GUARD_MS + 1
        action.tap()
        assertEquals(1, fired)
        assertFalse("and disarms afterwards", action.armed)
    }

    @Test
    fun disarmingUndoesTheArming() {
        var fired = 0
        var at = 1_000L
        val action = tap({ at }) { fired++ }
        action.tap()
        action.disarm()
        assertFalse(action.armed)
        at += 10_000
        action.tap()
        assertEquals("the next tap arms again rather than confirming", 0, fired)
    }
}
