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
    /** „Hei FORJA" — asistentul vocal; un singur proprietar al microfonului și al vocii. */
    lateinit var voice: com.forja.app.core.voice.VoiceAssistant
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
        voice = com.forja.app.core.voice.VoiceAssistant(this)

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
        // Casca în uniformă (5.0): ținuta se citește de pe disc înainte să apară mascota pe vreun ecran.
        appScope.launch { try { com.forja.app.core.soldier.SoldierStore.load(this@ForjaApp) } catch (_: Exception) { } }
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
        // Sincronizare: serviciu de fundal, reuseaza channel-ul "focus".
        // Retry loop: Firebase auth may not be ready at onCreate time.
        appScope.launch {
            for (i in 0..10) {
                try {
                    if (auth.currentUid != null) {
                        com.forja.app.core.sync.SyncService.start(this@ForjaApp)
                        com.forja.app.core.sync.SyncKeepAliveWorker.schedule(this@ForjaApp)
                        return@launch
                    }
                } catch (_: Exception) { }
                kotlinx.coroutines.delay(5000)
            }
        }
        // Casca: mesajele personale (lucrătorul orar unic, reminderul de culcare) — core/notify/Nudges.
        try { com.forja.app.core.notify.Nudges.start(this) } catch (_: Exception) { }
        // Sincronizarea în cont nu pornește singură din fundal (Android 14+): starea spune că se reia la deschidere.
        try { com.forja.app.core.sync.CollectionSettings.onProcessStart(this) } catch (_: Exception) { }
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("go", getString(R.string.notif_channel_go), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("sleep", getString(R.string.notif_channel_sleep), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("focus", getString(R.string.notif_channel_focus), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("social", getString(R.string.notif_channel_social), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("voice", getString(R.string.notif_channel_voice), NotificationManager.IMPORTANCE_LOW))
        // „Mesaje motivaționale” (Casca): separat de „social”, ca motivaționalele să se poată opri fără „Camarad nou”.
        // Mesajele stăteau pe „social”: dacă Lana i-a coborât importanța, noul canal o moștenește (importanța unui
        // canal nu mai poate crește după creare, deci nu o ocolim).
        if (nm.getNotificationChannel("coach") == null) {
            val social = nm.getNotificationChannel("social")?.importance ?: NotificationManager.IMPORTANCE_DEFAULT
            nm.createNotificationChannel(
                NotificationChannel("coach", getString(R.string.notif_channel_coach), minOf(social, NotificationManager.IMPORTANCE_DEFAULT)).apply {
                    description = getString(R.string.notif_channel_coach_desc)
                }
            )
        }
        nm.createNotificationChannel(NotificationChannel("explore", getString(R.string.notif_channel_explore), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("cleanup", getString(R.string.notif_channel_cleanup), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("sync", getString(R.string.notif_channel_sync), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("mirror", getString(R.string.notif_channel_mirror), NotificationManager.IMPORTANCE_LOW))
        // Inventarul 4.3 (core/inventory): progresul analizei din fundal + „Dosarele sunt gata”.
        nm.createNotificationChannel(NotificationChannel("inventory", "Inventar", NotificationManager.IMPORTANCE_LOW))
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
