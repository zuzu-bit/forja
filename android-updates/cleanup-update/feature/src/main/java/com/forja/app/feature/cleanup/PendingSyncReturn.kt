package com.forja.app.feature.cleanup

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import com.google.firebase.auth.FirebaseAuth
import java.lang.ref.WeakReference

/** Returns to the requested setup step after the existing FORJA sign-in flow. Never grants access. */
object PendingSyncReturn {
    const val PREFS = "forja_intro_v25"
    const val EXTRA = "forja_sync_setup"
    private var foreground = WeakReference<Activity>(null)
    private var registered: Application? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var opening = false
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { foreground = WeakReference(activity); maybeReturn() }
        override fun onActivityPaused(activity: Activity) { if (foreground.get() === activity) foreground.clear() }
        override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }

    fun afterLogin(activity: Activity) {
        activity.getSharedPreferences(PREFS, 0).edit().putBoolean("pending_sync", true).apply()
        install(activity)
    }

    /** Called by the pinned MainActivity onPostResume patch, including after process recreation. */
    @JvmStatic fun onHostResume(activity: Activity) {
        if (activity.getSharedPreferences(PREFS, 0).getBoolean("pending_sync", false)) {
            install(activity)
            foreground = WeakReference(activity)
            maybeReturn()
        }
    }

    private fun install(activity: Activity) {
        if (registered != null) return
        registered = activity.application
        registered!!.registerActivityLifecycleCallbacks(callbacks)
        val listener = FirebaseAuth.AuthStateListener { maybeReturn() }
        authListener = listener
        FirebaseAuth.getInstance().addAuthStateListener(listener)
    }

    private fun maybeReturn() {
        val activity = foreground.get() ?: return
        if (opening || activity.isFinishing || activity.isDestroyed || activity.javaClass.name != "com.forja.app.MainActivity") return
        if (FirebaseAuth.getInstance().currentUser == null) return
        val prefs = activity.getSharedPreferences(PREFS, 0)
        if (!prefs.getBoolean("pending_sync", false)) { unregister(); return }
        opening = true
        activity.runOnUiThread {
            if (foreground.get() !== activity || activity.isFinishing) { opening = false; return@runOnUiThread }
            runCatching {
                activity.startActivity(Intent().setClassName(activity.packageName, "com.forja.app.feature.research.ResearchExportActivity").putExtra(EXTRA, true))
                prefs.edit().putBoolean("pending_sync", false).apply()
                unregister()
            }.onFailure { opening = false }
        }
    }

    private fun unregister() {
        authListener?.let { FirebaseAuth.getInstance().removeAuthStateListener(it) }
        registered?.unregisterActivityLifecycleCallbacks(callbacks)
        authListener = null; registered = null; foreground.clear(); opening = false
    }
}
