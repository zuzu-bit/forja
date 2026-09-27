package com.forja.app.core.focus

import android.app.Activity
import android.content.Intent
import com.forja.app.MainActivity
import com.forja.app.core.cleanup.OrganizerJobs
import com.forja.app.navigation.Route

/**
 * Ecranele de blocare (Focus, Detox) rulează în sarcini separate, peste aplicația consemnată.
 * „Mă întorc la copac” trebuie să te aducă în FORJA, la ruta cerută — nu să trimită blocarea în spate
 * (asta te lăsa în aplicația blocată, iar paznicul o bloca din nou).
 */
object ReturnToForja {
    fun go(activity: Activity, route: String = Route.FOCUS) {
        try {
            activity.startActivity(
                Intent(activity, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(OrganizerJobs.ROUTE_EXTRA, route)
                }
            )
        } catch (_: Exception) { }
        activity.finishAndRemoveTask()
    }
}
