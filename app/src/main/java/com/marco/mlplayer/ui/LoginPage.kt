package com.marco.mlplayer.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.marco.mlplayer.util.AppLog
import com.marco.mlplayer.yt.YtAuth

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=" +
        "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Dit%26next%3D" +
        "https%253A%252F%252Fmusic.youtube.com%252F&hl=it"

private fun readCookies(): String? = runCatching {
    val cm = CookieManager.getInstance()
    cm.flush()
    val map = LinkedHashMap<String, String>()
    for (d in listOf("https://music.youtube.com", "https://www.youtube.com", "https://accounts.google.com", "https://youtube.com")) {
        map.putAll(YtAuth.parse(cm.getCookie(d).orEmpty()))
    }
    if (map.isEmpty()) null else map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}.getOrNull()

/** Accesso a Google nel WebView: appena compaiono i cookie di sessione l'account è collegato. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginPage(env: AppEnv) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Accedi a YouTube Music") },
            navigationIcon = {
                IconButton(onClick = { env.overlay = Overlay.None }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                }
            },
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Google rifiuta lo user agent standard dei WebView ("browser non sicuro"): tolgo i marcatori
                    val ua = settings.userAgentString.orEmpty()
                    settings.userAgentString = if (ua.isNotBlank()) {
                        ua.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
                    } else {
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    var done = false
                    fun check() {
                        if (done) return
                        val raw = readCookies() ?: return
                        if (YtAuth.hasSession(YtAuth.parse(raw))) {
                            done = true
                            YtAuth.connect(raw)
                            env.message = "Account collegato"
                            env.overlay = Overlay.None
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { check() }
                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) { check() }
                    }
                    AppLog.i("YouTube", "Apro l'accesso a Google")
                    loadUrl(LOGIN_URL)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
