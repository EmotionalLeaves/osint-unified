package com.osint.unified.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/**
 * DELETE, then CONFIRM, in the same place — without a double tap
 * counting as both.
 *
 * The two-tap confirm was already here, and it was not a confirmation.
 * Arm and confirm occupied one 48dp target in one position with nothing
 * between them, so two quick taps on DELETE destroyed an investigation
 * and cascaded its results and dismissals, with no undo. A tap arriving
 * within [GUARD_MS] is a double tap on the first button, not a decision
 * about the second, and is ignored.
 *
 * It also disarms itself after [WINDOW_MS]. A destructive button left
 * armed indefinitely — across a scroll, a rotation, a trip to another
 * screen and back — is a trap for the next tap, whatever that tap was
 * meant for.
 */
@Stable
class TwoTap internal constructor(
    private val armedAt: MutableState<Long>,
    private val onConfirm: () -> Unit,
    private val now: () -> Long
) {
    val armed: Boolean get() = armedAt.value != 0L

    fun tap() {
        val at = armedAt.value
        when {
            at == 0L -> armedAt.value = now()
            now() - at < GUARD_MS -> Unit
            else -> {
                armedAt.value = 0L
                onConfirm()
            }
        }
    }

    fun disarm() {
        armedAt.value = 0L
    }

    companion object {
        /** Below this, the second tap is the first tap happening twice. */
        const val GUARD_MS = 600L

        /** After this, an armed button goes back to being a safe one. */
        const val WINDOW_MS = 6_000L
    }
}

/**
 * [key] identifies the thing being destroyed, so scrolling a reused row
 * onto a different investigation does not carry the arming with it.
 */
@Composable
fun rememberTwoTap(key: Any?, onConfirm: () -> Unit): TwoTap {
    // saveable(key = …), not saveable(inputs).
    //
    // rememberSaveable's first vararg is `inputs`; the registry key
    // stays the positional composite hash. Inside a plain forEach —
    // which is how a case's targets are drawn — every iteration has
    // the same composite hash, so armed state was restored
    // POSITIONALLY: arm REMOVE on the third target, rotate while the
    // list has shifted by one, and the armed row is a different
    // target whose next tap unfiles it.
    val armedAt = rememberSaveable(key, key = "two-tap-$key") { mutableStateOf(0L) }
    // The latest lambda, not the one captured when this was keyed.
    val latest = rememberUpdatedState(onConfirm)
    val action = remember(key, armedAt) {
        TwoTap(armedAt, { latest.value() }, { System.currentTimeMillis() })
    }
    LaunchedEffect(armedAt.value) {
        val at = armedAt.value
        if (at == 0L) return@LaunchedEffect
        // The remainder, not the whole window: the timestamp is saved,
        // so after a rotation the button must not get a fresh six
        // seconds of being armed.
        delay((TwoTap.WINDOW_MS - (System.currentTimeMillis() - at)).coerceAtLeast(0L))
        if (armedAt.value == at) armedAt.value = 0L
    }
    return action
}
