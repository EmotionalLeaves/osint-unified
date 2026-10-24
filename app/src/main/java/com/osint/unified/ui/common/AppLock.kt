package com.osint.unified.ui.common

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.osint.unified.ui.theme.Intel

/**
 * A lock in front of the app, for people who hand their phone to someone.
 *
 * The database being encrypted protects it from a forensic image of a
 * powered-off phone. It protects nothing at all from the far more ordinary
 * case: the phone is unlocked, in someone else's hand, and this app is one
 * tap away in the recents list. That is what this is for, and it is off
 * unless chosen.
 */
object AppLock {

    private const val BIOMETRIC_OR_CREDENTIAL =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private const val BIOMETRIC_ONLY = BiometricManager.Authenticators.BIOMETRIC_WEAK

    /**
     * The strongest set this device will actually accept, or null.
     *
     * Combining a biometric with the device credential is only supported
     * from Android 11; below that the combined request is simply refused,
     * and asking for it anyway would report "no lock available" on a phone
     * with a perfectly good fingerprint reader. So the combination is
     * tried first and the biometric alone second.
     */
    private fun usable(context: Context): Int? {
        val manager = BiometricManager.from(context)
        listOf(BIOMETRIC_OR_CREDENTIAL, BIOMETRIC_ONLY).forEach { set ->
            val answer = runCatching { manager.canAuthenticate(set) }.getOrNull()
            if (answer == BiometricManager.BIOMETRIC_SUCCESS) return set
        }
        return null
    }

    /**
     * Whether there is genuinely no way for this person to authenticate
     * on this device, as opposed to no way right now.
     *
     * The difference is the whole lock. [prompt] used to open the app
     * whenever [usable] came back null, and null is every status that
     * is not SUCCESS: no enrolment, no hardware — and also
     * HW_UNAVAILABLE, LOCKOUT and LOCKOUT_PERMANENT. So five failed
     * fingerprints put the sensor in lockout, and the next
     * background-and-return opened the app to whoever was holding it,
     * with no prompt at all. A sensor that is momentarily busy did the
     * same on any phone.
     *
     * Only the two permanent answers justify opening. Below Android 11
     * the combined set is refused outright, so that refusal is not an
     * answer about the device either and the biometric-only reply is
     * the one that counts.
     */
    private fun noWayIn(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        val answer = runCatching { manager.canAuthenticate(BIOMETRIC_ONLY) }.getOrNull()
        return answer == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
            answer == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    }

    /** Why the prompt could not be shown, in words, or null if it can. */
    fun unavailableReason(context: Context): String? {
        if (usable(context) != null) return null
        val manager = BiometricManager.from(context)
        return when (runCatching { manager.canAuthenticate(BIOMETRIC_ONLY) }.getOrNull()) {
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                "The fingerprint sensor is busy. Try again in a moment, or " +
                    "lock and unlock the phone itself first."
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                "This phone needs a security update before it will " +
                    "authenticate for an app."
            else ->
                "This phone would not accept an unlock just now. Lock and " +
                    "unlock the phone itself, then try again."
        }
    }

    /**
     * Whether this device can actually unlock.
     *
     * Checked before the setting is offered, not after it is turned on. An
     * app that locks itself on a phone with no way to authenticate is a
     * bricked app, and the person who did it to themselves has no way back
     * in to undo it.
     */
    fun available(context: Context): Boolean = usable(context) != null

    fun prompt(activity: FragmentActivity, onUnlocked: () -> Unit) {
        val allowed = usable(activity) ?: run {
            // The lock was turned on and then the screen lock was
            // removed, or the phone never had a way to authenticate at
            // all. Refusing to open would be a self-inflicted lockout
            // with no way out, so it opens — but only for those two,
            // and not for a sensor that is merely busy or in lockout
            // after failed attempts, which is the state an unwelcome
            // holder of the phone has just put it in.
            if (noWayIn(activity)) onUnlocked()
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock OSINT Unified")
            .setSubtitle("This phone's fingerprint, PIN or pattern")
            .setAllowedAuthenticators(allowed)
            .apply {
                // A biometric-only prompt must offer a way out or the
                // dialog cannot be dismissed; with the device credential
                // in the set the system supplies one and a second button
                // is rejected.
                if (allowed == BIOMETRIC_ONLY) setNegativeButtonText("Cancel")
            }
            .build()
        runCatching { prompt.authenticate(info) }
    }
}

/**
 * What is shown instead of the app until it is unlocked.
 *
 * Deliberately says nothing: no target, no case, no count. The point of
 * the lock is that the screen gives nothing away, and a lock screen
 * reading "3 watched targets changed" would give away the only thing
 * worth hiding.
 */
@Composable
fun AppLockScreen(activity: FragmentActivity, onUnlocked: () -> Unit) {
    var asked by remember { mutableStateOf(false) }
    // Said only when the prompt could not be shown. A lock screen that
    // silently does nothing when tapped is indistinguishable from a
    // broken app, and the way out — lock and unlock the phone itself —
    // is not something anyone guesses.
    var trouble by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            trouble = AppLock.unavailableReason(activity)
            AppLock.prompt(activity, onUnlocked)
        }
    }
    Box(
        // Edge to edge is for the map. This screen replaces the whole
        // window and has no Scaffold behind it, so without the insets
        // its buttons sat under the system bars.
        Modifier.fillMaxSize().safeDrawingPadding().background(Intel.Base),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "LOCKED",
                style = MaterialTheme.typography.titleMedium,
                color = Intel.TextMid
            )
            Text(
                "UNLOCK",
                style = MaterialTheme.typography.labelLarge,
                color = Intel.Base,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Intel.Cyan)
                    .clickable {
                        trouble = AppLock.unavailableReason(activity)
                        AppLock.prompt(activity, onUnlocked)
                    }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            )
            trouble?.let { why ->
                Text(
                    why,
                    style = MaterialTheme.typography.bodySmall,
                    color = Intel.TextMid,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, start = 32.dp, end = 32.dp)
                )
            }
        }
    }
}
