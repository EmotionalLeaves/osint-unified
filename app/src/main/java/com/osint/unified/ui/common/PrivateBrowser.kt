package com.osint.unified.ui.common

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
    fun useProxy(host: String?, port: Int) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        runCatching {
            val proxies = ProxyController.getInstance()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            if (host.isNullOrBlank() || port !in 1..65535) {
                proxies.clearProxyOverride(executor) { }
            } else {
                proxies.setProxyOverride(
                    ProxyConfig.Builder().addProxyRule("$host:$port").build(),
                    executor
                ) { }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
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

    // Back goes back through the page's own history first. Closing the
    // whole browser because somebody followed one link is the behaviour
    // that makes an in-app browser feel like a trap.
    androidx.activity.compose.BackHandler(enabled = true) {
        val web = view
        if (web != null && web.canGoBack()) web.goBack() else onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                            modifier = Modifier.clickable(onClick = onDismiss)
                        )
                    }
                    Text(
                        title.take(120),
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.TextMid,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Action("COPY LINK", Intel.TextMid) {
                            clipboard.setText(AnnotatedString(current))
                        }
                        Spacer(Modifier.width(10.dp))
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
                        Spacer(Modifier.width(10.dp))
                        Action("INVESTIGATE", Intel.Cyan) {
                            PrivateBrowserHost.close()
                            InAppNavigation.investigate(current)
                        }
                        Spacer(Modifier.width(10.dp))
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
        modifier = Modifier.clickable(onClick = onClick)
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
}
