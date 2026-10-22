package com.osint.unified.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Where a link goes.
 *
 * Everything the app finds opens in the isolated browser by default: a
 * page the target controls should not be loaded with the investigator's
 * own session cookies attached. Opening in the real browser is still one
 * tap away, from a screen that says what it costs.
 */
object LinkOpener {
    /** The default route for anything the app surfaced. */
    fun open(context: Context, raw: String) {
        PrivateBrowserHost.open(raw)
    }

    /** The signed-in browser, chosen deliberately. */
    fun openInRealBrowser(context: Context, raw: String) {
        val url = sanitize(raw)
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

    /**
     * Makes a URL openable even when its source was sloppy.
     *
     * Catalogue entries and generated links sometimes carry a raw space or
     * another character that is illegal in a URI; Android then refuses the
     * whole thing and the link silently does nothing. Percent-encoding those
     * characters — without touching an already-encoded `%xx` — fixes the link
     * rather than dropping it. Trailing punctuation picked up from prose is
     * also trimmed.
     */
    fun sanitize(raw: String): String {
        var url = raw.trim().trimEnd('.', ',', ')', ']', '}', '"', '\'', ';', '>', '*')
        if (url.isBlank()) return ""
        // Separate the fragment so characters there are encoded too, but the
        // '#' itself survives.
        val illegal = mapOf(
            ' ' to "%20", '"' to "%22", '<' to "%3C", '>' to "%3E",
            '\\' to "%5C", '^' to "%5E", '`' to "%60",
            '{' to "%7B", '|' to "%7C", '}' to "%7D"
        )
        val sb = StringBuilder(url.length + 8)
        var i = 0
        while (i < url.length) {
            val c = url[i]
            // Leave existing percent-escapes alone.
            if (c == '%' && i + 2 < url.length &&
                url[i + 1].isHexDigit() && url[i + 2].isHexDigit()
            ) {
                sb.append(url, i, i + 3); i += 3; continue
            }
            val replacement = illegal[c]
            if (replacement != null) sb.append(replacement) else sb.append(c)
            i++
        }
        url = sb.toString()
        // A URL that is nothing but a scheme is not worth opening.
        return if (url.length <= "https://".length) "" else url
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
