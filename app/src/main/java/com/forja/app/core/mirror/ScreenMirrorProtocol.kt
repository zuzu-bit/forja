package com.forja.app.core.mirror

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * „Ecranul pe site” (5.1) — partea fără Android: ce comandă a trimis site-ul (sau terminalul `forja`, sau un alt agent),
 * tradusă în ce face telefonul, cu coordonatele așezate pe ecranul lui real. Gramatica și validarea stau pe server
 * (server/screen-mirror.mjs); aici doar citim JSON-ul primit și punem cifrele la locul lor. Totul se testează pe JVM
 * (ScreenMirrorProtocolTest), fără niciun import Android.
 */

/** O comandă de executat pe telefon, cu pixelii deja calculați pentru ecranul real. */
sealed class MirrorAction {
    data class Tap(val x: Int, val y: Int) : MirrorAction()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Long) : MirrorAction()
    /** back · home · recents · notifications · quick_settings · lock */
    data class Key(val name: String) : MirrorAction()
    data class Type(val text: String) : MirrorAction()
    data class Open(val app: String) : MirrorAction()
    /** O comandă „Hei FORJA” rulată prin asistentul vocal (fără voce), al cărei răspuns se trimite înapoi ca text. */
    data class Say(val text: String) : MirrorAction()
    object Read : MirrorAction()
    object Apps : MirrorAction()
    object Shot : MirrorAction()
    data class Scroll(val down: Boolean) : MirrorAction()
    object Info : MirrorAction()
    /** Comanda nu e înțeleasă (serverul ar fi trebuit s-o respingă): răspundem cu un refuz, nu facem nimic. */
    data class Unsupported(val why: String) : MirrorAction()
}

object ScreenMirrorProtocol {
    val KEYS = setOf("back", "home", "recents", "notifications", "quick_settings", "lock")
    const val MIN_SWIPE_MS = 50L
    const val MAX_SWIPE_MS = 5000L

    /**
     * O coordonată de la server: `unit` = „frac” (0–1, fracție din ecran) sau „px” (pixeli reali, ca la adb). O așezăm pe
     * [size] (lățimea sau înălțimea ecranului real), mărginită în ecran. Fracția se înmulțește; pixelii se iau ca atare.
     */
    fun place(value: Double, unit: String?, size: Int): Int {
        if (size <= 0) return 0
        val raw = if (unit == "px") value else value * size
        val px = Math.round(raw).toInt()
        return px.coerceIn(0, (size - 1).coerceAtLeast(0))
    }

    /** JSON-ul comenzii (`{t:'cmd', id, kind, …}`) → [MirrorAction], cu coordonatele pe ecranul [screenW]×[screenH]. */
    fun action(cmd: JsonObject, screenW: Int, screenH: Int): MirrorAction {
        fun str(k: String) = cmd[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        fun dbl(k: String) = cmd[k]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
        fun int(k: String) = cmd[k]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
        val unit = str("unit")
        return when (str("kind")) {
            "tap" -> {
                val x = dbl("x") ?: return MirrorAction.Unsupported("tap fără coordonate")
                val y = dbl("y") ?: return MirrorAction.Unsupported("tap fără coordonate")
                MirrorAction.Tap(place(x, unit, screenW), place(y, unit, screenH))
            }
            "swipe" -> {
                val x1 = dbl("x1"); val y1 = dbl("y1"); val x2 = dbl("x2"); val y2 = dbl("y2")
                if (x1 == null || y1 == null || x2 == null || y2 == null) return MirrorAction.Unsupported("glisare fără coordonate")
                val ms = (int("ms")?.toLong() ?: 300L).coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
                MirrorAction.Swipe(place(x1, unit, screenW), place(y1, unit, screenH), place(x2, unit, screenW), place(y2, unit, screenH), ms)
            }
            "key" -> {
                val name = str("name")
                if (name == null || name !in KEYS) MirrorAction.Unsupported("tastă necunoscută") else MirrorAction.Key(name)
            }
            "type" -> str("text")?.let { MirrorAction.Type(it) } ?: MirrorAction.Unsupported("scrie fără text")
            "open" -> str("app")?.let { MirrorAction.Open(it) } ?: MirrorAction.Unsupported("deschide fără aplicație")
            "say" -> str("text")?.let { MirrorAction.Say(it) } ?: MirrorAction.Unsupported("spune fără comandă")
            "scroll" -> MirrorAction.Scroll(str("dir") != "up")
            "read" -> MirrorAction.Read
            "apps" -> MirrorAction.Apps
            "shot" -> MirrorAction.Shot
            "info" -> MirrorAction.Info
            else -> MirrorAction.Unsupported("comandă necunoscută")
        }
    }

    /** Dimensiunea cadrului trimis: lățime fixă [target] (telefonul trimite ~360 px), înălțimea pe aceeași proporție. */
    fun frameSize(screenW: Int, screenH: Int, target: Int = 360): Pair<Int, Int> {
        if (screenW <= 0 || screenH <= 0) return target to Math.round(target * 2.1f)
        if (screenW <= target) return screenW to screenH
        val h = Math.round(target.toFloat() * screenH / screenW)
        return target to h.coerceAtLeast(1)
    }

    /** Numele aplicației din față, dintr-un pachet: „com.google.android.youtube” → „youtube”; FORJA rămâne „FORJA”. */
    fun appWord(pkg: String?): String {
        val p = pkg.orEmpty()
        if (p.isBlank()) return ""
        if (p.startsWith("com.forja.app")) return "FORJA"
        return p.substringAfterLast('.').take(24)
    }
}
