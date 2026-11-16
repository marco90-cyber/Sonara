package com.marco.mlplayer.yt

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.marco.mlplayer.util.AppLog
import java.security.MessageDigest

/**
 * Account Google collegato a YouTube Music. I cookie di sessione arrivano dal WebView di accesso
 * e restano solo nella memoria privata dell'app; da qui si ricava l'intestazione SAPISIDHASH
 * che YouTube Music richiede per le chiamate autenticate.
 */
object YtAuth {
    private const val TAG = "YouTube"
    private const val ORIGIN = "https://music.youtube.com"
    private var prefs: android.content.SharedPreferences? = null

    var cookies by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    val connected: Boolean get() = sapisid() != null && cookies.isNotEmpty()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("yt_auth", Context.MODE_PRIVATE)
        cookies = parse(prefs?.getString("cookies", "").orEmpty())
        AppLog.i(TAG, if (connected) "Account YouTube Music collegato" else "Nessun account collegato")
    }

    fun parse(raw: String): Map<String, String> = raw.split(';').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else {
            val n = it.substring(0, i).trim(); val v = it.substring(i + 1).trim()
            if (n.isBlank() || v.isBlank()) null else n to v
        }
    }.toMap()

    private fun sapisid(): String? =
        cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: cookies["APISID"]

    fun hasSession(c: Map<String, String>): Boolean =
        (c.containsKey("SAPISID") || c.containsKey("__Secure-3PAPISID") || c.containsKey("APISID")) &&
            (c.containsKey("SID") || c.containsKey("__Secure-3PSID"))

    fun connect(raw: String) {
        val c = parse(raw)
        if (!hasSession(c)) return
        cookies = c
        prefs?.edit()?.putString("cookies", raw)?.apply()
        AppLog.i(TAG, "Account collegato (${c.size} cookie)")
    }

    fun cookieHeader(): String? =
        if (cookies.isEmpty()) null else cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** `SAPISIDHASH <ora>_<sha1(ora SAPISID origin)>`, lo schema usato dal sito stesso. */
    fun authorization(): String? {
        val s = sapisid() ?: return null
        val ts = System.currentTimeMillis() / 1000
        val d = MessageDigest.getInstance("SHA-1").digest("$ts $s $ORIGIN".toByteArray())
        return "SAPISIDHASH ${ts}_" + d.joinToString("") { "%02x".format(it) }
    }

    /** Scollega l'account e svuota anche i cookie del WebView, così un nuovo accesso parte pulito. */
    fun signOut() {
        cookies = emptyMap()
        prefs?.edit()?.remove("cookies")?.apply()
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            WebStorage.getInstance().deleteAllData()
        }
        AppLog.i(TAG, "Account scollegato")
    }
}
