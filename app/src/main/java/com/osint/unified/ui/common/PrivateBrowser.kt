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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * wiped when it closes. Opening in the real browser is still available, and
 * now says what it costs.
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
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PrivateBrowserDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var title by remember(url) { mutableStateOf(url) }

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
                            "ISOLATED — no cookies, nothing kept",
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
                    Text(
                        "OPEN IN MY BROWSER — signed in, the site will see who you are",
                        style = MaterialTheme.typography.labelSmall,
                        color = Intel.Amber,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clickable {
                                LinkOpener.openInRealBrowser(context, url)
                                onDismiss()
                            }
                    )
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
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
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean = false

                                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                    title = view?.title?.takeIf { it.isNotBlank() }
                                        ?: loadedUrl.orEmpty()
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
            // Nothing survives the visit.
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebView(context).apply {
                    clearCache(true)
                    clearHistory()
                    clearFormData()
                    destroy()
                }
            }
        }
    }
}
