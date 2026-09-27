package com.forja.app.feature.research

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.forja.app.BuildConfig
import com.forja.app.core.data.CollectionSettings as Config
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Disposable emulator and synthetic account only. No profile/journal is created. */
@RunWith(AndroidJUnit4::class)
class AutomaticCollectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd ->
        java.io.FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }
    }
    private fun sessions(token: String): JSONObject {
        val req = Request.Builder().url(BuildConfig.INSIGHTS_URL + "/v2/sessions").header("Authorization", "Bearer $token").build()
        return OkHttpClient().newCall(req).execute().use { r -> check(r.isSuccessful) { "Session list HTTP ${r.code}" }; JSONObject(r.body!!.string()) }
    }
    @Test fun automaticUploadContinuesOffScreenAndStopsOnDisableAndLogout() {
        val auth = FirebaseAuth.getInstance()
        assertNull("Disposable test must start signed out", auth.currentUser)
        Config.stop(context)
        val marker = UUID.randomUUID().toString()
        val user = Tasks.await(auth.createUserWithEmailAndPassword("forja-qa-$marker@example.invalid", "Qa!$marker"), 30, TimeUnit.SECONDS).user!!
        val token = Tasks.await(user.getIdToken(false), 20, TimeUnit.SECONDS).token!!
        var scenario: ActivityScenario<ResearchExportActivity>? = null
        try {
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
            shell("appops set ${context.packageName} GET_USAGE_STATS allow")
            Config.prefs(context).edit().putString("owner", user.uid).apply()
            Config.save(context, setOf("app_usage", "audio"))
            scenario = ActivityScenario.launch(ResearchExportActivity::class.java)
            scenario.onActivity { Config.resume(it) }
            var received = false
            for (i in 0 until 45) {
                Thread.sleep(2000)
                val rows = sessions(token).getJSONArray("sessions")
                if (rows.length() > 0 && !rows.getJSONObject(0).isNull("data") && rows.getJSONObject(0).getJSONArray("items").length() >= 2) { received = true; break }
            }
            assertTrue("Automatic metrics and audio must reach the online server", received)
            val before = sessions(token).getJSONArray("sessions").getJSONObject(0)
            assertEquals("automatic", before.getString("mode"))
            // Leaving this screen must not end the user-enabled foreground collection.
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(12000)
            val background = sessions(token).getJSONArray("sessions").getJSONObject(0)
            assertTrue(background.getJSONArray("items").length() > before.getJSONArray("items").length())
            assertTrue(shell("dumpsys activity services ${context.packageName}").contains("AutomaticCollectionService"))
            Config.stop(context)
            Thread.sleep(3000)
            val stopped = sessions(token).getJSONArray("sessions").getJSONObject(0)
            Thread.sleep(10000)
            assertEquals(stopped.toString(), sessions(token).getJSONArray("sessions").getJSONObject(0).toString())
            assertFalse(shell("dumpsys activity services ${context.packageName}").contains("AutomaticCollectionService"))
            // Android grants remain, but no selected category means no restart.
            scenario.close()
            scenario = ActivityScenario.launch(ResearchExportActivity::class.java)
            scenario.onActivity { Config.resume(it) }
            Thread.sleep(1500)
            assertFalse(shell("dumpsys activity services ${context.packageName}").contains("AutomaticCollectionService"))
            assertTrue(Config.enabled(context).isEmpty())
            Config.save(context, setOf("app_usage"))
            scenario.onActivity { Config.resume(it) }
            Thread.sleep(3000)
            auth.signOut()
            Thread.sleep(2000)
            assertTrue(Config.enabled(context).isEmpty())
            assertFalse(shell("dumpsys activity services ${context.packageName}").contains("AutomaticCollectionService"))
        } finally {
            Config.stop(context)
            scenario?.close()
            // Token is still valid for cleanup after sign-out; all synthetic server sessions are removed.
            val rows = sessions(token).getJSONArray("sessions")
            for (i in 0 until rows.length()) {
                val id = rows.getJSONObject(i).getString("session_id")
                val req = Request.Builder().url(BuildConfig.INSIGHTS_URL + "/v2/sessions/" + id).header("Authorization", "Bearer $token").delete().build()
                OkHttpClient().newCall(req).execute().use { assertTrue("Remove synthetic session", it.isSuccessful) }
            }
            assertEquals(0, sessions(token).getJSONArray("sessions").length())
            if (auth.currentUser == null) Tasks.await(auth.signInWithEmailAndPassword("forja-qa-$marker@example.invalid", "Qa!$marker"), 30, TimeUnit.SECONDS)
            Tasks.await(auth.currentUser!!.delete(), 20, TimeUnit.SECONDS)
            Config.logout(context)
        }
    }
}
