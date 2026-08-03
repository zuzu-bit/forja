package com.forja.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.forja.app.core.data.AuthRepository
import com.forja.app.core.data.FriendsRepository
import com.forja.app.core.data.Prefs
import com.forja.app.core.data.PresenceRepository
import com.forja.app.core.data.db.ForjaDatabase
import com.forja.app.core.data.db.Seed
import com.forja.app.core.network.ForjaApi
import com.forja.app.core.network.GeminiFood
import com.forja.app.core.network.OpenFoodFacts
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.persistentCacheSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class ForjaApp : Application(), coil.ImageLoaderFactory {

    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .components { add(com.forja.app.core.media.MediaInterceptor) }
            .build()

    lateinit var db: ForjaDatabase
    lateinit var prefs: Prefs
    lateinit var auth: AuthRepository
    lateinit var friends: FriendsRepository
    lateinit var presence: PresenceRepository
    lateinit var foodApi: OpenFoodFacts
    lateinit var geminiFood: GeminiFood
    lateinit var forjaApi: ForjaApi
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        // Cache offline Firestore — aplicația merge și fără net, se sincronizează la revenire.
        FirebaseFirestore.getInstance().firestoreSettings = firestoreSettings {
            setLocalCacheSettings(persistentCacheSettings { })
        }
        db = ForjaDatabase.get(this)
        prefs = Prefs(this)
        auth = AuthRepository()
        friends = FriendsRepository()
        presence = PresenceRepository(this)
        foodApi = OpenFoodFacts()
        geminiFood = GeminiFood()
        forjaApi = ForjaApi()

        // Locația în fundal (dacă utilizatorul a activat-o și permisiunea există).
        com.forja.app.core.location.BgLocation.registerIfReady(this)

        // osmdroid: user agent + cache intern (fără permisiuni de stocare).
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = getDir("osmdroid", MODE_PRIVATE)
        Configuration.getInstance().osmdroidTileCache = getDir("osmdroid_tiles", MODE_PRIVATE)

        appScope.launch { Seed.ensure(db) }
        appScope.launch { com.forja.app.core.media.Media.refresh() }
        appScope.launch {
            // Scanarea galeriei a fost eliminată — anulăm orice programare rămasă.
            try { com.forja.app.feature.nutrition.GalleryScan.cancelDaily(this@ForjaApp) } catch (_: Exception) { }
        }
        appScope.launch {
            // Paznicul Focus/Detox repornește dacă era activ (ucis de sistem, update etc.).
            try {
                val focusOn = prefs.focusActive.first()
                val detoxOn = prefs.detoxUntil.first() > System.currentTimeMillis()
                if (focusOn || detoxOn) {
                    com.forja.app.core.focus.FocusMonitorService.start(this@ForjaApp)
                }
            } catch (_: Exception) { }
        }
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("go", getString(R.string.notif_channel_go), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("sleep", getString(R.string.notif_channel_sleep), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("focus", getString(R.string.notif_channel_focus), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("social", getString(R.string.notif_channel_social), NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        fun from(context: android.content.Context): ForjaApp = context.applicationContext as ForjaApp
    }
}
