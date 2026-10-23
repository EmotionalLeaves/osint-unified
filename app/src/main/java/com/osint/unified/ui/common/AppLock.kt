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
            // The lock was turned on and then the screen lock was removed.
            // Refusing to open would be a self-inflicted lockout with no
            // way out, so it opens.
            onUnlocked()
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
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            AppLock.prompt(activity, onUnlocked)
        }
    }
    Box(
        Modifier.fillMaxSize().background(Intel.Base),
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
                    .clickable { AppLock.prompt(activity, onUnlocked) }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }
}
