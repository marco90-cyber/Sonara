package com.marco.mlplayer.data

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Paese dei consigliati: YouTube Music propone cose diverse da paese a paese.
 * Di base («Automatico») si usa il paese in cui il telefono si trova adesso (rete mobile, poi SIM, poi lingua del telefono):
 * nessun permesso di posizione.
 */
object Region {
    const val DEFAULT = "IT"

    /** I paesi tra cui scegliere (codici ISO). */
    val CODES = listOf(
        "IT", "US", "GB", "IE", "DE", "AT", "CH", "FR", "BE", "NL", "LU", "ES", "PT", "SE", "NO", "DK", "FI", "IS",
        "PL", "CZ", "SK", "HU", "RO", "BG", "GR", "HR", "SI", "RS", "UA", "TR", "CY", "MT", "EE", "LV", "LT",
        "CA", "MX", "BR", "AR", "CL", "CO", "PE", "UY", "EC", "CR", "PA", "DO",
        "AU", "NZ", "JP", "KR", "IN", "ID", "TH", "VN", "PH", "MY", "SG", "TW", "HK",
        "ZA", "EG", "MA", "DZ", "TN", "NG", "KE", "SA", "AE", "IL", "QA", "KW", "JO",
    )

    /** Nome del paese in italiano. */
    fun name(code: String): String =
        Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ITALIAN).ifBlank { code }

    /** Il paese in cui ci si trova: rete mobile, SIM, lingua del telefono; se non è tra quelli disponibili, l'Italia. */
    fun detect(context: Context): String {
        val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        return listOf(
            runCatching { tm?.networkCountryIso }.getOrNull(),
            runCatching { tm?.simCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstNotNullOfOrNull { it?.uppercase(Locale.ROOT)?.takeIf { c -> c in CODES } } ?: DEFAULT
    }

    /** Il paese da usare: quello scelto, oppure (se è vuoto = automatico) quello in cui ci si trova. */
    fun effective(chosen: String, context: Context): String = chosen.takeIf { it in CODES } ?: detect(context)
}
