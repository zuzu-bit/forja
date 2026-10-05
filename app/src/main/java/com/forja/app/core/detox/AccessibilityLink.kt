package com.forja.app.core.detox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Drumul cel mai scurt către pagina serviciului FORJA din Setări → Accesibilitate (un singur serviciu pentru
 * Focus/Detox și pentru comenzile vocale pe ecran). Android nu lasă nicio aplicație să-l pornească singură:
 * utilizatorul îl pornește acolo. Pe unele telefoane pagina directă nu există — atunci se deschide lista.
 */
object AccessibilityLink {
    const val SERVICE_LABEL = "FORJA · Accesibilitate"

    fun intents(context: Context): List<Intent> {
        val cn = ComponentName(context, ForjaGuardService::class.java)
        val flat = cn.flattenToString()
        fun args() = Bundle().apply { putString(":settings:fragment_args_key", flat) }
        return listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(":settings:fragment_args_key", flat)
                putExtra(":settings:show_fragment_args", args())
                putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
            },
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", flat)
                putExtra(":settings:show_fragment_args", args())
            },
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
    }

    /** Deschide pagina serviciului (sau lista); false dacă telefonul nu are nici setările de accesibilitate. */
    fun open(context: Context): Boolean {
        for (i in intents(context)) {
            try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) { }
        }
        return false
    }
}
