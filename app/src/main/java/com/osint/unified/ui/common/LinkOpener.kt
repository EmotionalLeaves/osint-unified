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
        var url = raw.trim().trimEnd('.', ',', ']', '}', '"', '\'', ';', '>', '*')
        // A closing parenthesis only goes if nothing opened one.
        //
        // It was in the list above, which broke every Wikipedia article
        // with a disambiguator — /wiki/Foo_(bar) opened /wiki/Foo_(bar
        // and 404ed — while the case it was there for is a URL at the
        // end of a parenthetical sentence, which has no '(' in it.
        while (url.endsWith(')') && url.count { it == '(' } < url.count { it == ')' }) {
            url = url.dropLast(1)
        }
        if (url.isBlank()) return ""
        // http and https, and nothing else.
        //
        // This is the only scheme check on the path that matters.
        // shouldOverrideUrlLoading guards every navigation the private
        // browser makes except the first one — and the first one is the
        // one carrying a string from outside the app. GeoBlip.url comes
        // straight from the news, EONET and GDACS feeds and from the
        // user's own custom overlay, so a feed answering with
        // `data:text/html,...` rendered its HTML inside a dialog
        // captioned NO COOKIES · NOTHING KEPT · SCRIPTS RUN, and
        // `javascript:` ran.
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return ""
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
        //
        // Measured against the scheme actually present, not against the
        // longer of the two: `http://x` is eight characters and was
        // silently dropped, as was any template that resolved to a bare
        // host. A link that does nothing when tapped is worse than one
        // that goes somewhere unhelpful, because nothing tells the
        // person it was discarded.
        val afterScheme = url.substringAfter("://", "")
        return if (afterScheme.isBlank()) "" else url
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
