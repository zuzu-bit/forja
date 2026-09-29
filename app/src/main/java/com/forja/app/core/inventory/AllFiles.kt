package com.forja.app.core.inventory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * „Acces la toate fișierele” (MANAGE_EXTERNAL_STORAGE, Android 11+), cerut doar la nevoie, din Inventar. Cu el,
 * MediaProvider (AOSP android16-release) tratează FORJA ca „manager”: scriere pe orice element fără ferestrele de
 * acord (checkCallingPermissionGlobal), orice dosar de destinație pentru poze (ensureFileColumns) și mutarea pozelor din
 * dosarele altor aplicații, ca WhatsApp (isUpdateAllowedForOwnedPath). Aplicația e instalată direct (nu din Play),
 * deci nu intră sub regulile Play pentru această permisiune. Starea se recitește la fiecare revenire în ecran.
 */
internal object AllFiles {
    /** Accesul există doar de la Android 11 (API 30); sub el nu e nevoie de el (și nici nu se poate cere). */
    val available: Boolean get() = Build.VERSION.SDK_INT >= 30

    fun granted(): Boolean =
        Build.VERSION.SDK_INT >= 30 && try { Environment.isExternalStorageManager() } catch (_: Exception) { false }

    /**
     * Unde se dă accesul, în ordinea încercărilor: pagina FORJA („package:”), lista tuturor aplicațiilor cu acces, apoi
     * detaliile aplicației (unele versiuni OEM nu au primele două pagini).
     */
    fun intents(ctx: Context): List<Intent> {
        val pkg = Uri.fromParts("package", ctx.packageName, null)
        val out = ArrayList<Intent>(3)
        if (Build.VERSION.SDK_INT >= 30) {
            out += Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
            out += Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        out += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        return out
    }

    /** Prima pagină încercată (cea a FORJA). */
    fun intent(ctx: Context): Intent = intents(ctx).first()
}
