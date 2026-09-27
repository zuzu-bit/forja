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
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager

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
    /** Explorarea (v4.0): zonele deblocate + locurile unde ai stat; primește fixuri din toate sursele. */
    lateinit var explore: com.forja.app.core.explore.ExploreTracker
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
        explore = com.forja.app.core.explore.ExploreTracker(this)

        // Locația în fundal (dacă utilizatorul a activat-o și permisiunea există).
        com.forja.app.core.location.BgLocation.registerIfReady(this)

        // Harta (MapLibre + OpenFreeMap): inițializarea motorului ÎNAINTE de orice MapView, User-Agent identificabil
        // pe dale/glife/sprite-uri și cache ambient de 150 MB (dalele văzute rămân pe telefon — harta merge și fără net).
        try {
            MapLibre.getInstance(this)
            val ua = "FORJA/${BuildConfig.VERSION_NAME} ($packageName)"
            HttpRequestUtil.setOkHttpClient(
                OkHttpClient.Builder().addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", ua).build())
                }.build()
            )
            OfflineManager.getInstance(this).setMaximumAmbientCacheSize(
                150L * 1024 * 1024,
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {}
                    override fun onError(message: String) {}
                }
            )
        } catch (_: Exception) { }

        appScope.launch { Seed.ensure(db) }
        appScope.launch { com.forja.app.core.media.Media.refresh() }
        appScope.launch {
            // Scanarea galeriei a fost eliminată — anulăm orice programare rămasă.
            try { com.forja.app.feature.nutrition.GalleryScan.cancelDaily(this@ForjaApp) } catch (_: Exception) { }
        }
        appScope.launch {
            // Reminder-e blânde, la ore aleatoare — pornite implicit.
            try {
                if (prefs.nudgesOn.first()) com.forja.app.core.notify.ForjaNudge.schedule(this@ForjaApp)
                else com.forja.app.core.notify.ForjaNudge.cancel(this@ForjaApp)
            } catch (_: Exception) { }
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
        // Sincronizarea în cont nu pornește singură din fundal (Android 14+): starea spune că se reia la deschidere.
        try { com.forja.app.core.sync.CollectionSettings.onProcessStart(this) } catch (_: Exception) { }
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("go", getString(R.string.notif_channel_go), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("sleep", getString(R.string.notif_channel_sleep), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("focus", getString(R.string.notif_channel_focus), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("social", getString(R.string.notif_channel_social), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("explore", getString(R.string.notif_channel_explore), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("cleanup", getString(R.string.notif_channel_cleanup), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("sync", getString(R.string.notif_channel_sync), NotificationManager.IMPORTANCE_LOW))
        // Alarma deșteaptă: IMPORTANCE_HIGH e obligatoriu ca full-screen intent-ul să pornească
        // AlarmActivity cu ecranul stins. Sunetul îl pune AlarmActivity, nu notificarea.
        nm.createNotificationChannel(
            NotificationChannel("alarm", getString(R.string.notif_channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        fun from(context: android.content.Context): ForjaApp = context.applicationContext as ForjaApp
    }
}
