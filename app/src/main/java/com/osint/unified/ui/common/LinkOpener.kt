package com.osint.unified.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens links inside the app via Chrome Custom Tabs (an in-app browser tab —
 * back returns straight to the app), so investigations never kick you out to
 * a separate browser. Falls back to a normal view intent if Custom Tabs
 * aren't available.
 */
object LinkOpener {
    fun open(context: Context, raw: String) {
        val url = raw.trim().trimEnd('.', ',', ')', ']', '}', '"', '\'', ';', '>')
        if (url.isBlank()) return
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val opened = runCatching {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
                .apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                .launchUrl(context, uri)
            true
        }.getOrDefault(false)
        if (!opened) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
