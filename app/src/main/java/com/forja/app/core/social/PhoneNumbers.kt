package com.forja.app.core.social

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Normalizare E.164 fără libphonenumber: `+` se păstrează, `00` devine `+`, `0…` primește prefixul țării
 * (din SIM sau din rețea; România implicit). Rezultatul e validat strict: `^\+[1-9][0-9]{7,14}$`.
 */
object PhoneNumbers {
    private val E164 = Regex("^\\+[1-9][0-9]{7,14}$")

    /** ISO 3166-1 alpha-2 → prefix telefonic. RO primul: e țara casei. */
    val countryCodes: Map<String, String> = mapOf(
        "RO" to "40", "MD" to "373", "IT" to "39", "ES" to "34", "DE" to "49", "FR" to "33", "GB" to "44",
        "US" to "1", "CA" to "1", "AT" to "43", "BE" to "32", "NL" to "31", "HU" to "36", "BG" to "359",
        "GR" to "30", "PT" to "351", "IE" to "353", "PL" to "48", "CZ" to "420", "SK" to "421", "SE" to "46",
        "NO" to "47", "DK" to "45", "FI" to "358", "CH" to "41", "TR" to "90", "IL" to "972", "AU" to "61",
        "UA" to "380", "RS" to "381", "HR" to "385", "SI" to "386", "LU" to "352", "CY" to "357", "AE" to "971",
        "NZ" to "64", "JP" to "81", "BR" to "55", "MX" to "52", "IN" to "91", "LT" to "370", "LV" to "371", "EE" to "372"
    )

    const val DEFAULT_CC = "40"

    fun isValid(value: String?): Boolean = value != null && E164.matches(value)

    /** Prefixul țării: SIM-ul întâi, apoi rețeaua, apoi România. */
    fun defaultCountryCode(context: Context): String {
        val tm = try { context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager } catch (_: Exception) { null }
        val iso = listOfNotNull(
            try { tm?.simCountryIso } catch (_: Exception) { null },
            try { tm?.networkCountryIso } catch (_: Exception) { null }
        ).map { it.trim().uppercase() }.firstOrNull { it.length == 2 && countryCodes.containsKey(it) }
        return iso?.let { countryCodes[it] } ?: DEFAULT_CC
    }

    /**
     * Numărul din SIM, doar dacă READ_PHONE_NUMBERS e acordat (FORJA nu îl cere; rămâne gol altfel).
     * Multe SIM-uri nu îl expun deloc — atunci utilizatorul îl scrie.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "HardwareIds")
    fun simNumber(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) return null
        val tm = try { context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager } catch (_: Exception) { null } ?: return null
        val raw = try { tm.line1Number } catch (_: Exception) { null }
        return normalize(raw, defaultCountryCode(context))
    }

    /**
     * `+40 721 000 001`, `0040721000001`, `0721 000 001`, `(0721) 000-001` → `+40721000001`.
     * Un număr fără `+` și fără `0` în față se ia ca fiind deja cu prefix dacă începe cu [cc] și e lung; altfel primește [cc].
     * Întoarce null dacă nu iese un E.164 valid.
     */
    fun normalize(raw: String?, cc: String = DEFAULT_CC): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length < 6) return null
        val candidate = when {
            plus -> "+$digits"
            digits.startsWith("00") -> "+" + digits.drop(2)
            digits.startsWith("0") -> "+" + cc + digits.drop(1)
            digits.startsWith(cc) && digits.length >= cc.length + 9 -> "+$digits"
            else -> "+$cc$digits"
        }
        return candidate.takeIf { E164.matches(it) }
    }
}
