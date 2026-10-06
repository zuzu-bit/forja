package com.forja.app.core.mirror

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.forja.app.core.detox.ForjaGuardService
import com.forja.app.core.recovery.LostPhoneRecovery
import com.forja.app.core.sync.CollectionSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * „Ecranul pe site” (5.1) — comutatorul și condițiile, fără UI. Telefonul e același din Găsire (contractul semnat îl
 * înrolează): site-ul îl recunoaște după aceeași identitate și secret. Cât e pornit AICI (opt-in separat, din Profil →
 * Telefonul meu) ȘI cineva privește de pe site, [ScreenMirrorService] deschide o legătură, trimite cadre ale ecranului
 * (prin serviciul FORJA din Accesibilitate, ca asistentul vocal) și execută comenzile. Nimic nu se păstrează; cât e
 * privit, o notificare permanentă spune „FORJA · Ecranul tău e pe site” cu „Oprește”.
 *
 * Capturarea folosește [android.accessibilityservice.AccessibilityService.takeScreenshot] (Android 11+). Fără root, fără
 * MediaProjection (care ar cere un dialog de fiecare dată și nu merge din fundal): exact ce are deja serviciul de
 * accesibilitate pornit pentru Focus și „Hei FORJA”.
 */
object ScreenMirror {
    const val CHANNEL = "mirror"
    private const val PREF = "forja_screen_mirror_v1"
    private const val KEY_ON = "on"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Telefonul poate trimite ecranul: Android 11+ (takeScreenshot din accesibilitate). */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Utilizatorul a pornit „Ecranul pe site” din Profil. Implicit oprit. */
    fun enabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ON, false)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply()
        if (!on) ScreenMirrorService.stop(c)
    }

    /** Serviciul FORJA din Accesibilitate e pornit (același ca pentru Focus / „Hei FORJA”). */
    fun accessibilityOn(c: Context): Boolean = ForjaGuardService.instance != null || ForjaGuardService.isEnabled(c)

    /** Totul e la locul lui ca site-ul să poată cere ecranul: suportat, pornit, contract semnat, accesibilitate pornită. */
    fun ready(c: Context): Boolean = supported() && enabled(c) && CollectionSettings.contractOn(c) && accessibilityOn(c)

    /** Ce trimitem în bătaia găsirii, ca site-ul să știe de ce (nu) merge ecranul (DESIGN-5.1 §3; totul opțional). */
    fun capability(c: Context): JsonObject = buildJsonObject {
        put("supported", supported())
        // „pornit” în înțelesul site-ului: pornit din Profil ȘI accesibilitatea e activă (altfel nu putem captura).
        put("enabled", enabled(c) && accessibilityOn(c))
        put("android", Build.VERSION.SDK_INT)
    }

    /**
     * Răspunsul bătăii cere ecranul (`screen.wanted`): dacă e totul pornit, deschidem legătura. [screen] e obiectul din
     * răspunsul serverului sau null. Nu pornește nimic dacă utilizatorul nu a pornit „Ecranul pe site” — e opt-in.
     */
    fun onBeat(c: Context, wanted: Boolean) {
        if (wanted && ready(c)) ScreenMirrorService.start(c)
        else if (!wanted) ScreenMirrorService.idleStop(c)
    }

    /** Legătura cere identitatea telefonului din Găsire (același id + secret); null dacă nu e înrolat. */
    fun device(c: Context) = LostPhoneRecovery.device(c)
}
