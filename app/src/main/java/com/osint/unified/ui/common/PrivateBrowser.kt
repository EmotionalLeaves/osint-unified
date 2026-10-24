package com.osint.unified.ui.common

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.osint.unified.ui.theme.Intel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where a link opens, and who it tells.
 *
 * Links used to open in Chrome Custom Tabs, which share the installed
 * browser's cookie jar. Tapping a LinkedIn or Instagram profile the app had
 * just found therefore told that person exactly who had looked them up —
 * the single most expensive mistake an investigation tool can make, made by
 * the most innocuous-looking tap in the app.
 *
 * A page opened here carries no cookies in or out, keeps nothing, and is
 * wiped when it closes. It is not anonymity and the banner no longer
 * implies it is: scripts run and the site sees an address. When the proxy
 * is on, that address is the proxy's.
 */
object PrivateBrowserHost {
    /** The URL the isolated browser should show, or null when closed. */
    val url = MutableStateFlow<String?>(null)

    fun open(raw: String) {
        val clean = LinkOpener.sanitize(raw)
        if (clean.isNotBlank()) url.value = clean
    }

    fun close() {
        url.value = null
    }

    /**
     * Sends the in-app browser through the same proxy as everything else,
     * or stops doing so.
     *
     * A WebView does not use the platform's proxy selector — it has its
     * own networking — so without this the one screen that loads a
     * target's own pages would be the one screen going direct while the
     * person believed everything was going through Tor.
     */
    /**
     * One executor, not one per call.
     *
     * This built a `newSingleThreadExecutor()` on every invocation
     * and never shut it down, and its core thread is non-daemon and
     * never times out — so each call parked a thread for the life of
     * the process. The settings screen called this from the
     * onValueChange of both proxy fields, so typing 127.0.0.1 and
     * then 9050 leaked about thirteen threads.
     */
    private val proxyCallbacks: java.util.concurrent.Executor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "webview-proxy").apply { isDaemon = true }
        }

    /**
     * Whether the WebView is actually going through the proxy.
     *
     * Null when nothing has been asked of it. This used to be a
     * `Unit`-returning function that swallowed its failure and kept
     * no state, while `Http.useProxy` succeeded independently — so
     * on a device whose WebView provider lacks PROXY_OVERRIDE, or
     * if ProxyController threw, the settings row read "this sends
     * the app through Tor" and the banner inside the browser said
     * "it still sees an address — the proxy's, when the proxy is
     * on", and this one screen went direct. The class's own doc
     * names that outcome as the thing it exists to prevent.
     */
    @Volatile
    var proxied: Boolean? = null
        private set

    fun useProxy(host: String?, port: Int) {
        val wanted = !host.isNullOrBlank() && port in 1..65535
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // Nothing can be routed, so say so rather than leaving
            // the last answer standing.
            proxied = if (wanted) false else null
            return
        }
        val applied = runCatching {
            val proxies = ProxyController.getInstance()
            val executor = proxyCallbacks
            if (!wanted) {
                proxies.clearProxyOverride(executor) { }
            } else {
                proxies.setProxyOverride(
                    ProxyConfig.Builder().addProxyRule("$host:$port").build(),
                    executor
                ) { }
            }
        }.isSuccess
        proxied = when {
            !wanted -> null
            applied -> true
            else -> false
        }
    }
}

/** Truncated text that says it is truncated. */
private fun ellipsise(s: String, max: Int): String =
    if (s.length <= max) s else s.take(max - 1) + "\u2026"

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivateBrowserDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var title by remember(url) { mutableStateOf(url) }
    var current by remember(url) { mutableStateOf(url) }
    var progress by remember(url) { mutableStateOf(0) }
    var blocked by remember(url) { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<WebView?>(null) }
    var saved by remember(url) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Inside the Dialog, not outside it.
        //
        // Declared out here the handler registers against the
        // Activity's dispatcher, and the dialog window has focus and
        // consumes the press itself — straight to onDismissRequest.
        // So following two links into a site and pressing back closed
        // the whole browser, which is exactly the trap the comment
        // below says this exists to prevent. A Dialog hosts its own
        // back dispatcher, and a handler declared in its content is
        // the one that gets asked first.
        //
        // Back goes back through the page's own history first.
        // Closing the whole browser because somebody followed one
        // link is the behaviour that makes an in-app browser feel
        // like a trap.
        androidx.activity.compose.BackHandler(enabled = true) {
            val web = view
            if (web != null && web.canGoBack()) web.goBack() else onDismiss()
        }
        Surface(Modifier.fillMaxSize(), color = Intel.Base) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Intel.Surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "NO COOKIES · NOTHING KEPT · SCRIPTS RUN",
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.Success,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "CLOSE",
                            style = MaterialTheme.typography.labelMedium,
                            color = Intel.Cyan,
                            // Fifteen dp of unpadded text, and the only
                            // way out of the browser once back has
                            // started walking page history.
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    // The address, not the page's title.
                    //
                    // This monospace line under the banner reads as
                    // an address bar and showed `document.title`,
                    // which the page chooses — so a page titled
                    // "https://accounts.google.com/signin" displayed
                    // as that, in the one place a person looks to
                    // see where they are. The real address was only
                    // reachable through COPY LINK.
                    Text(
                        ellipsise(current, 120),
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextHi,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    if (title.isNotBlank() && title != current) {
                        Text(
                            ellipsise(title, 120),
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.TextLow,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                    // Four labels and three spacers in a plain Row
                    // measured about 296dp, against the 296dp a 320dp
                    // phone has: MY BROWSER and INVESTIGATE were
                    // measured at zero width and were simply not
                    // there, and SNAPSHOT — the one feature that
                    // preserves evidence before a target deletes it —
                    // went next at the stock "large" font setting.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Action("COPY LINK", Intel.TextMid) {
                            clipboard.setText(AnnotatedString(current))
                        }
                        Action(if (saved) "SAVED" else "SNAPSHOT", if (saved) Intel.Success else Intel.Cyan) {
                            val web = view ?: return@Action
                            // The page as it is now, kept in the case. A
                            // finding whose page the target deletes
                            // tomorrow stops being a finding; this is the
                            // cheapest thing that stops that happening,
                            // and it is why Bellingcat built an archiver.
                            web.evaluateJavascript(
                                "(function(){return document.documentElement.outerHTML})()"
                            ) { encoded ->
                                val html = runCatching {
                                    org.json.JSONTokener(encoded).nextValue() as? String
                                }.getOrNull()
                                if (!html.isNullOrBlank()) {
                                    InAppNavigation.snapshot(current, title, html)
                                    saved = true
                                }
                            }
                        }
                        Action("INVESTIGATE", Intel.Cyan) {
                            PrivateBrowserHost.close()
                            InAppNavigation.investigate(current)
                        }
                        Action("MY BROWSER", Intel.Amber) {
                            LinkOpener.openInRealBrowser(context, current)
                            onDismiss()
                        }
                    }
                    Text(
                        "Your own browser is signed in: the site would see who " +
                            "you are. This one is not, but it still sees an " +
                            "address — the proxy's, when the proxy is on.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextLow,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    // And whether it actually is. The line above
                    // promised the proxy without anything checking
                    // that this WebView had taken it.
                    if (PrivateBrowserHost.proxied == false) {
                        Text(
                            "The proxy is on for the rest of the app and this " +
                                "browser could not take it — this device's " +
                                "WebView does not support it. Pages opened here " +
                                "go direct, from your own address.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.Danger,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    blocked?.let {
                        Text(
                            "Blocked a link to $it — only web pages open here.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Intel.Amber,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        color = Intel.Cyan,
                        trackColor = Intel.Hairline,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            view = this
                            CookieManager.getInstance().setAcceptCookie(false)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = false
                            settings.databaseEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.savePassword = false
                            settings.saveFormData = false
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(v: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                /**
                                 * Only web pages, and only here.
                                 *
                                 * Returning false for everything let a
                                 * page navigate to intent:, market: or
                                 * tel: and hand the person to another app
                                 * — out of the isolated browser, out of
                                 * this app, and in the case of intent:
                                 * into whatever the page chose.
                                 */
                                override fun shouldOverrideUrlLoading(
                                    v: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val scheme = request?.url?.scheme?.lowercase()
                                    if (scheme == "http" || scheme == "https") {
                                        blocked = null
                                        return false
                                    }
                                    blocked = scheme ?: "an unnamed scheme"
                                    return true
                                }

                                override fun onPageFinished(v: WebView?, loadedUrl: String?) {
                                    title = v?.title?.takeIf { it.isNotBlank() }
                                        ?: loadedUrl.orEmpty()
                                    loadedUrl?.let { current = it }
                                    progress = 100
                                }
                            }
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    DisposableEffect(url) {
        onDispose {
            // Nothing survives the visit — including the view itself,
            // which used to be left running while a throwaway WebView was
            // created purely to be destroyed in its place.
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                view?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    clearCache(true)
                    clearHistory()
                    clearFormData()
                    (parent as? android.view.ViewGroup)?.removeView(this)
                    destroy()
                }
                view = null
            }
        }
    }
}

@Composable
private fun Action(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        // These were bare text with no padding at all: about 15dp
        // tall, which is a third of what a thumb needs.
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

/**
 * How a screen that has no view model asks the app to go somewhere.
 *
 * The isolated browser is shown above everything, outside the navigation
 * when-block, so it has no other way to say "investigate this". Keeping
 * the request here means a page can become a case without the person
 * leaving the browser, copying the address and typing it into the search
 * box.
 */
object InAppNavigation {
    val investigateTarget = MutableStateFlow<Pair<String, Long>?>(null)

    fun investigate(target: String) {
        investigateTarget.value = target to System.currentTimeMillis()
    }

    /** A page captured into the open case. */
    data class Snapshot(
        val url: String,
        val title: String,
        val html: String,
        val at: Long = System.currentTimeMillis()
    )

    val snapshotTaken = MutableStateFlow<Snapshot?>(null)

    fun snapshot(url: String, title: String, html: String) {
        snapshotTaken.value = Snapshot(url, title, html)
    }

    /**
     * Said by whoever acted on a request, because nothing else will.
     *
     * These are process-wide and were never cleared, so the last
     * request stayed here for the life of the app. That is harmless
     * while the collector remembers having handled it and ruinous the
     * moment it does not: the privacy lock disposes the content
     * subtree, the de-duplication marker goes with it, and the next
     * composition sees a request that is still sitting here and acts
     * on it again. A full fan-out on the last page browsed, and the
     * last snapshot filed a second time, on every unlock.
     */
    fun clearInvestigate() {
        investigateTarget.value = null
    }

    fun clearSnapshot() {
        snapshotTaken.value = null
    }
}
