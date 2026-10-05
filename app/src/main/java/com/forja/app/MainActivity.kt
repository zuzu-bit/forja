package com.forja.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.forja.app.core.data.Prefs
import com.forja.app.core.designsystem.ForjaTheme
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.components.ForjaTab
import com.forja.app.core.designsystem.components.ForjaTabBar
import com.forja.app.core.designsystem.components.LocalToast
import com.forja.app.core.designsystem.components.ToastHost
import com.forja.app.core.designsystem.components.ToastState
import com.forja.app.core.music.MusicRungs
import com.forja.app.core.music.MusicStarter
import com.forja.app.core.voice.VoiceAssistant
import com.forja.app.core.voice.VoiceWakeService
import com.forja.app.feature.auth.AuthScreens
import com.forja.app.feature.dashboard.DashboardScreen
import com.forja.app.feature.focus.FocusScreen
import com.forja.app.feature.map.MapScreen
import com.forja.app.feature.nutrition.NutritionScreen
import com.forja.app.feature.nutrition.ScannerScreen
import com.forja.app.feature.onboarding.OnboardingScreen
import com.forja.app.feature.profile.ProfileScreen
import com.forja.app.feature.sleep.SleepScreen
import com.forja.app.feature.splash.SplashScreen
import com.forja.app.feature.voice.VoiceFab
import com.forja.app.feature.voice.VoiceScreen
import com.forja.app.feature.workout.WorkoutLiveScreen
import com.forja.app.feature.workout.WorkoutScreen
import com.forja.app.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    /** Crește la fiecare intent nou (notificare atinsă cât activitatea trăiește) — MainNav recitește extra-urile. */
    var intentTick by mutableIntStateOf(0)
        private set
    /** „Hei FORJA": cereri „deschide asistentul" / „ascultă acum" venite din tile, notificare sau scurtătură. */
    var voiceOpen by mutableIntStateOf(0)
        private set
    var voiceListen by mutableIntStateOf(0)
        private set

    private fun handleVoiceIntent(intent: android.content.Intent?) {
        when (intent?.action) {
            ACTION_VOICE -> voiceOpen++
            ACTION_VOICE_LISTEN -> { voiceOpen++; voiceListen++ }
        }
    }

    companion object {
        /** Deschide ecranul „Hei FORJA". */
        const val ACTION_VOICE = "com.forja.app.action.VOICE"
        /** Deschide ecranul și ascultă imediat. */
        const val ACTION_VOICE_LISTEN = "com.forja.app.action.VOICE_LISTEN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleVoiceIntent(intent)
        // Saltul muzicii în Spotify pleacă din activitatea asta, pentru rezultat și fără task nou: așa FORJA poate
        // închide singură ecranul Spotify după ce muzica e confirmată (RET_SUB, 4.4.1).
        MusicStarter.host = WeakReference(this)
        enableEdgeToEdge()
        setContent {
            ForjaTheme { ForjaRoot() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentTick++
        handleVoiceIntent(intent)
    }

    /** Rezultatul saltului în player (întotdeauna anulat): îl vrea doar jurnalul RET; restul trece mai departe. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MusicRungs.REQ_HOP) MusicStarter.onHopResult()
    }

    override fun onDestroy() {
        if (MusicStarter.host?.get() === this) MusicStarter.host = null
        super.onDestroy()
    }
}

@Composable
private fun ForjaRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val toast = remember { ToastState() }

    var splashDone by remember { mutableStateOf(false) }
    var startRoute by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Prezentarea se arată o dată pentru fiecare versiune — și conturilor existente (v3.x → v4.0).
        val onboardingDone = app.prefs.onboardingDone.first()
        val introVersion = app.prefs.introSeenVersion.first()
        val needsIntro = !onboardingDone || introVersion < Prefs.INTRO_VERSION
        startRoute = when {
            needsIntro -> Route.ONBOARDING
            !app.auth.isLoggedIn -> Route.LOGIN
            else -> Route.DASHBOARD
        }
    }

    CompositionLocalProvider(LocalToast provides toast) {
        Box(Modifier.fillMaxSize().background(Surface0)) {
            if (startRoute != null && splashDone) {
                MainNav(app, startRoute!!, toast)
            }
            AnimatedVisibility(visible = !splashDone, enter = fadeIn(), exit = fadeOut()) {
                SplashScreen(onDone = { splashDone = true })
            }
            ToastHost(
                toast,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
            )
        }
    }

    // Energie de la prieteni → toast live. Colectăm în chiar corpul efectului: la schimbarea
    // cheilor, colectorul vechi e anulat odată cu efectul — fără dubluri.
    LaunchedEffect(startRoute, splashDone) {
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        app.friends.energyFlow(uid).collect { toast.show(it) }
    }
}

// Tab-urile de jos: între ele doar un fade scurt; restul ecranelor alunecă.
private val tabRoutes = setOf(Route.DASHBOARD, Route.WORKOUT, Route.MAP, Route.NUTRITION, Route.SLEEP, Route.FOCUS, Route.BREATH)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

@Composable
private fun MainNav(app: ForjaApp, startRoute: String, toast: ToastState) {
    val nav: NavHostController = rememberNavController()
    val navScope = rememberCoroutineScope()
    // La intrarea în cont: jurnalele din Room trec la contul acesta (sau se golesc, dacă sunt ale altuia) înainte să se vadă.
    val claimThenOpen: (() -> Unit) -> Unit = { open ->
        navScope.launch {
            app.auth.currentUid?.let { uid ->
                try { withContext(Dispatchers.IO) { com.forja.app.core.data.Journals.claim(app, uid) } } catch (_: Exception) { }
            }
            open()
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    // Inventarul 4.3: o singură instanță a rutei CLEANUP. Pastila, modurile de așteptare și notificarea revin la ea
    // (scot ce e deasupra) și îi cer pagina dorită; altfel o deschid.
    fun openInventory(page: com.forja.app.feature.inventory.InvPage?) {
        if (page != null) com.forja.app.feature.inventory.InventoryLinks.request(page)
        val popped = try { nav.popBackStack(Route.CLEANUP, inclusive = false) } catch (_: Exception) { false }
        if (!popped && nav.currentDestination?.route != Route.CLEANUP) {
            try { nav.navigate(Route.CLEANUP) { launchSingleTop = true } } catch (_: Exception) { }
        }
    }
    // Starea inventarului de pe disc (după o repornire): pastila apare, analiza neterminată se reia în fundal.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try { com.forja.app.core.inventory.Inventory.load(appContext) } catch (_: Exception) { }
        }
    }

    // Ruta cerută din afară (extra „forja_route”): notificarea Curățeniei (WP9) sau ecranele de blocare
    // („Mă întorc la copac” → Focus). Consumată o singură dată, doar când utilizatorul e deja în aplicație.
    val hostActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val intentTick = (hostActivity as? MainActivity)?.intentTick ?: 0
    LaunchedEffect(intentTick) {
        val wanted = hostActivity?.intent?.getStringExtra(com.forja.app.core.cleanup.OrganizerJobs.ROUTE_EXTRA) ?: return@LaunchedEffect
        hostActivity.intent?.removeExtra(com.forja.app.core.cleanup.OrganizerJobs.ROUTE_EXTRA)
        if (startRoute != Route.DASHBOARD) return@LaunchedEffect
        if (wanted != Route.CLEANUP && wanted !in tabRoutes) return@LaunchedEffect
        var tries = 0
        while (nav.currentBackStackEntry == null && tries++ < 40) kotlinx.coroutines.delay(50)
        try {
            if (wanted in tabRoutes) {
                nav.navigate(wanted) {
                    popUpTo(Route.DASHBOARD) { inclusive = false }
                    launchSingleTop = true
                }
            } else if (wanted == Route.CLEANUP) {
                openInventory(null)
            } else {
                nav.navigate(wanted) { launchSingleTop = true }
            }
        } catch (_: Exception) { }
    }

    val tabFor: Map<String, ForjaTab> = mapOf(
        Route.DASHBOARD to ForjaTab.Azi,
        Route.WORKOUT to ForjaTab.Antrenament,
        Route.MAP to ForjaTab.Harta,
        Route.NUTRITION to ForjaTab.Nutritie,
        Route.SLEEP to ForjaTab.Somn,
        Route.FOCUS to ForjaTab.Focus,
        Route.BREATH to ForjaTab.Respiro
    )
    val currentTab = tabFor[route ?: ""]
    val tabsVisible = route in setOf(
        Route.DASHBOARD, Route.WORKOUT, Route.NUTRITION, Route.SLEEP, Route.MAP, Route.FOCUS, Route.BREATH, Route.PROFILE
    )

    // Fantoma: oglinda locală mereu la zi, respectată de orice publicare.
    LaunchedEffect(Unit) {
        app.prefs.ghostUntilLocal.collect { app.presence.ghostUntilCache = it }
    }

    // ── „Hei FORJA" (4.6): deschiderea din tile / notificare / scurtătură, butonul plutitor, navigarea cerută cu vocea ──
    val mainActivity = hostActivity as? MainActivity
    val voiceOpen = mainActivity?.voiceOpen ?: 0
    val voiceListen = mainActivity?.voiceListen ?: 0
    var fabListen by remember { mutableIntStateOf(0) }
    // „Ascultă acum” e consumat aici, nu în ecran: ecranul e recreat la fiecare deschidere și ar reporni ascultarea.
    var listenConsumed by remember { mutableIntStateOf(0) }
    LaunchedEffect(voiceOpen) {
        if (voiceOpen > 0 && nav.currentDestination?.route != Route.VOICE) {
            var tries = 0
            while (nav.currentBackStackEntry == null && tries++ < 40) kotlinx.coroutines.delay(50)
            try { nav.navigate(Route.VOICE) { launchSingleTop = true } } catch (_: Exception) { }
        }
    }
    // Navigarea cerută cu vocea („deschide antrenamentul") și permisiunile cerute de o comandă.
    val voicePerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        app.voice.onPermissionsResult(result)
    }
    LaunchedEffect(Unit) {
        fun goVoice(route: String) {
            try {
                when {
                    route == Route.CLEANUP -> openInventory(null)
                    route == Route.WORKOUT_LIVE -> {
                        // „Începe antrenamentul”: sesiunea live stă peste hub (ieșirea ei revine la hub).
                        if (nav.currentDestination?.route != Route.WORKOUT_LIVE) {
                            nav.navigate(Route.WORKOUT) { popUpTo(Route.DASHBOARD) { inclusive = false }; launchSingleTop = true }
                            nav.navigate(Route.WORKOUT_LIVE) { launchSingleTop = true }
                        }
                    }
                    route in tabRoutes -> nav.navigate(route) { popUpTo(Route.DASHBOARD) { inclusive = false }; launchSingleTop = true }
                    else -> nav.navigate(route) { launchSingleTop = true }
                }
            } catch (_: Exception) { }
        }
        // Ruta cerută cu vocea cât FORJA era închisă (comanda a adus activitatea în față): o luăm la pornire.
        app.voice.takePendingRoute()?.let { route ->
            var tries = 0
            while (nav.currentBackStackEntry == null && tries++ < 40) kotlinx.coroutines.delay(50)
            goVoice(route)
        }
        app.voice.events.collect { e ->
            when (e) {
                is VoiceAssistant.Event.Navigate -> goVoice(e.route)
                is VoiceAssistant.Event.NeedPermissions -> try { voicePerms.launch(e.permissions.toTypedArray()) } catch (_: Exception) { }
            }
        }
    }
    val voiceWakeOn by app.prefs.voiceWakeOn.collectAsState(initial = false)

    // Publicarea prezenței cât timp aplicația e în prim-plan (fundalul e treaba BgLocation).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val uid = app.auth.currentUid
            if (uid != null) {
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        app.presence.start(uid) { app.presence.isGhostNow() }
                        try { com.forja.app.core.notify.Nudges.onAppOpen(app) } catch (_: Exception) { }   // Casca: ultima deschidere
                        com.forja.app.core.location.BgLocation.registerIfReady(app)
                        com.forja.app.core.recovery.LostPhoneRecovery.onAppStart(app)
                        // Sincronizarea în cont se reia doar dintr-o activitate vizibilă și doar dacă a fost pornită de utilizator.
                        try { com.forja.app.core.sync.CollectionSettings.resume(app) } catch (_: Exception) { }
                        // Prieteni din agendă: lucrătorul zilnic există doar cât timp comutatorul e pornit și numărul e scris.
                        try { com.forja.app.core.social.ContactsSync.scheduleIfOn(app) } catch (_: Exception) { }
                        // Contractul semnat: galeria urcă treptat (lucrătorul există doar cât e semnat).
                        try { com.forja.app.core.sync.GalleryUploader.scheduleIfOn(app) } catch (_: Exception) { }
                    }
                    Lifecycle.Event.ON_STOP -> app.presence.stop()
                    else -> {}
                }
            }
            if (event == Lifecycle.Event.ON_START) {
                // O comandă aștepta permisiuni cât timp FORJA nu era pe ecran: le cerem acum.
                try { app.voice.onForeground() } catch (_: Exception) { }
                // „Hei FORJA" mereu la ascultare — repornit din prim-plan (singurul loc sigur pe Android 14+).
                navScope.launch {
                    try {
                        if (app.prefs.voiceWakeOn.first() &&
                            androidx.core.content.ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        ) VoiceWakeService.start(appContext)
                    } catch (_: Exception) { }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun goTab(tab: ForjaTab) {
        val target = when (tab) {
            ForjaTab.Azi -> Route.DASHBOARD
            ForjaTab.Antrenament -> Route.WORKOUT
            ForjaTab.Harta -> Route.MAP
            ForjaTab.Nutritie -> Route.NUTRITION
            ForjaTab.Somn -> Route.SLEEP
            ForjaTab.Focus -> Route.FOCUS
            ForjaTab.Respiro -> Route.BREATH
        }
        nav.navigate(target) {
            popUpTo(Route.DASHBOARD) { inclusive = false }
            launchSingleTop = true
        }
    }

    // ── Tranziții între ecrane (sub „mișcare redusă”: schimbare instantanee) ──
    val reduced = LocalReducedMotion.current
    val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(340, easing = FastOutSlowInEasing)
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        when {
            reduced -> fadeIn(snap())
            isTabSwitch() -> fadeIn(tween(220))
            else -> fadeIn(tween(260)) +
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, slideSpec) { it / 5 }
        }
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        when {
            reduced -> fadeOut(snap())
            isTabSwitch() -> fadeOut(tween(180))
            else -> fadeOut(tween(220)) +
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, slideSpec) { it / 8 }
        }
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        when {
            reduced -> fadeIn(snap())
            isTabSwitch() -> fadeIn(tween(220))
            else -> fadeIn(tween(260)) +
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, slideSpec) { it / 8 }
        }
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        when {
            reduced -> fadeOut(snap())
            isTabSwitch() -> fadeOut(tween(180))
            else -> fadeOut(tween(220)) +
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, slideSpec) { it / 5 }
        }
    }
    // Fluxul de început (prezentare → cont → azi): cross-fade cu o ușoară ridicare.
    val riseEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduced) fadeIn(snap()) else fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 12 }
    }
    val fadeExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduced) fadeOut(snap()) else fadeOut(tween(250))
    }
    // „Echipare” — ca un panou modal: urcă de jos, coboară la închidere.
    val modalEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduced) fadeIn(snap()) else fadeIn(tween(260)) + slideInVertically(slideSpec) { it / 6 }
    }
    val modalExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduced) fadeOut(snap()) else fadeOut(tween(260)) + slideOutVertically(slideSpec) { it / 6 }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = startRoute,
            enterTransition = enter,
            exitTransition = exit,
            popEnterTransition = popEnter,
            popExitTransition = popExit
        ) {
            composable(
                Route.ONBOARDING,
                enterTransition = riseEnter, exitTransition = fadeExit,
                popEnterTransition = riseEnter, popExitTransition = fadeExit
            ) {
                OnboardingScreen(onFinished = {
                    nav.navigate(if (app.auth.isLoggedIn) Route.DASHBOARD else Route.LOGIN) {
                        popUpTo(Route.ONBOARDING) { inclusive = true }
                    }
                })
            }
            composable(
                Route.LOGIN,
                enterTransition = riseEnter, exitTransition = fadeExit,
                popEnterTransition = riseEnter, popExitTransition = fadeExit
            ) {
                AuthScreens(startInLogin = true, onAuthed = {
                    claimThenOpen { nav.navigate(Route.DASHBOARD) { popUpTo(Route.LOGIN) { inclusive = true } } }
                })
            }
            composable(
                Route.REGISTER,
                enterTransition = riseEnter, exitTransition = fadeExit,
                popEnterTransition = riseEnter, popExitTransition = fadeExit
            ) {
                AuthScreens(startInLogin = false, onAuthed = {
                    claimThenOpen { nav.navigate(Route.DASHBOARD) { popUpTo(Route.REGISTER) { inclusive = true } } }
                })
            }
            composable(
                Route.DASHBOARD,
                enterTransition = { if (isTabSwitch()) enter() else riseEnter() },
                // „Azi” e ancora: la întoarcere apare doar prin fade, sub ecranul care pleacă.
                popEnterTransition = { if (reduced) fadeIn(snap()) else fadeIn(tween(260)) }
            ) {
                // „Echipare”: o singură dată pentru fiecare versiune (bifele noi — baterie, alarmă — merită încă o trecere).
                LaunchedEffect(Unit) {
                    if (app.prefs.gearSeenVersion.first() < Prefs.GEAR_VERSION) {
                        app.prefs.setGearSeen()
                        nav.navigate(Route.PERMISSIONS) { launchSingleTop = true }
                    }
                }
                DashboardScreen(
                    onOpenModule = { r -> nav.navigate(r) },
                    onOpenProfile = { nav.navigate(Route.PROFILE) },
                    onOpenMap = { nav.navigate(Route.MAP) },
                    onOpenActivities = { nav.navigate(Route.ACTIVITIES) },
                    onOpenSoldier = { nav.navigate(Route.SOLDIER) { launchSingleTop = true } }
                )
            }
            composable(Route.WORKOUT) {
                WorkoutScreen(onStartLive = { nav.navigate(Route.WORKOUT_LIVE) })
            }
            composable(Route.WORKOUT_LIVE) {
                WorkoutLiveScreen(onExit = { nav.popBackStack(Route.WORKOUT, false) })
            }
            composable(Route.NUTRITION) {
                NutritionScreen(
                    onScan = { nav.navigate(Route.SCANNER) },
                    onPhotograph = { nav.navigate(Route.MEAL_CAMERA) }
                )
            }
            composable(Route.SCANNER) {
                ScannerScreen(onClose = { nav.popBackStack() })
            }
            composable(Route.SLEEP) { SleepScreen() }
            composable(Route.MAP) {
                MapScreen(onOpenActivities = { nav.navigate(Route.ACTIVITIES) })
            }
            composable(Route.ACTIVITIES) {
                com.forja.app.feature.activities.ActivitiesScreen(
                    onOpenDetail = { id -> nav.navigate(Route.activityDetail(id)) },
                    onBack = { nav.popBackStack() }
                )
            }
            composable(
                Route.ACTIVITY_DETAIL,
                arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                com.forja.app.feature.activities.ActivityDetailScreen(activityId = id, onBack = { nav.popBackStack() })
            }
            composable(Route.MEAL_CAMERA) {
                com.forja.app.feature.nutrition.MealCameraScreen(onClose = { nav.popBackStack() })
            }
            composable(Route.FOCUS) {
                FocusScreen(onOpenCleanup = { nav.navigate(Route.CLEANUP) })
            }
            composable(Route.BREATH) {
                com.forja.app.feature.breath.BreathScreen()
            }
            // Inventarul 4.3 (înlocuiește Curățenia): S1 start → S2 rulare → S4 dosare → S5 dosar → S6 aplicare.
            composable(Route.CLEANUP) {
                com.forja.app.feature.inventory.InventoryScreen(
                    onBack = { nav.popBackStack() },
                    onOpenWait = { w ->
                        val target = when (w) {
                            com.forja.app.feature.inventory.InvWait.Zid -> Route.WAIT_ZID
                            com.forja.app.feature.inventory.InvWait.Asalt -> Route.WAIT_ASALT
                            com.forja.app.feature.inventory.InvWait.Music -> Route.WAIT_MUSIC
                        }
                        nav.navigate(target) { launchSingleTop = true }
                    }
                )
            }
            // „Cât aștepți”: moduri pe tot ecranul, deasupra inventarului; pastila lor duce înapoi la el.
            // Jocurile (4.4): ZID și ASALT. Partida vie trăiește în GameSessions, deci pastila poate scoate ruta din stivă.
            composable(
                Route.WAIT_ZID,
                enterTransition = modalEnter, exitTransition = fadeExit,
                popEnterTransition = riseEnter, popExitTransition = modalExit
            ) {
                com.forja.app.feature.games.zid.ZidGameScreen(
                    onOpenInventory = { openInventory(it) },
                    onClose = { nav.popBackStack() }
                )
            }
            composable(
                Route.WAIT_ASALT,
                enterTransition = modalEnter, exitTransition = fadeExit,
                popEnterTransition = riseEnter, popExitTransition = modalExit
            ) {
                com.forja.app.feature.games.asalt.AsaltGameScreen(
                    onOpenInventory = { openInventory(it) },
                    onClose = { nav.popBackStack() }
                )
            }
            composable(
                Route.WAIT_MUSIC,
                enterTransition = modalEnter, exitTransition = fadeExit,
                popEnterTransition = riseEnter, popExitTransition = modalExit
            ) {
                com.forja.app.feature.inventory.InventoryMusicScreen(onOpenInventory = { openInventory(it) })
            }
            composable(
                Route.PERMISSIONS,
                enterTransition = modalEnter, exitTransition = fadeExit,
                popEnterTransition = riseEnter, popExitTransition = modalExit
            ) {
                com.forja.app.feature.permissions.PermissionsScreen(
                    onBack = { nav.popBackStack() },
                    onOpenContract = { nav.navigate(Route.CONTRACT) { launchSingleTop = true } }
                )
            }
            // Contractul de securitate — un singur acord; se recitește și se revocă din Profil.
            composable(
                Route.CONTRACT,
                enterTransition = modalEnter, exitTransition = fadeExit,
                popEnterTransition = riseEnter, popExitTransition = modalExit
            ) {
                com.forja.app.feature.permissions.ContractScreen(onBack = { nav.popBackStack() })
            }
            // Proba ascunsă a pornirii muzicii (Profil → 5 atingeri pe versiune).
            composable(Route.MUSIC_PROBE) {
                com.forja.app.feature.probe.ProbeScreen(onBack = { nav.popBackStack() })
            }
            // „Casca în uniformă” (5.0): Cazarma — gradul, misiunile de azi, garderoba.
            composable(
                Route.SOLDIER,
                enterTransition = modalEnter, exitTransition = fadeExit,
                popEnterTransition = riseEnter, popExitTransition = modalExit
            ) {
                com.forja.app.feature.soldier.SoldierScreen(
                    onBack = { if (!nav.popBackStack()) nav.navigate(Route.DASHBOARD) { launchSingleTop = true } },
                    onOpenRoute = { r ->
                        if (r == Route.CLEANUP) openInventory(null)
                        else if (r in tabRoutes) nav.navigate(r) { popUpTo(Route.DASHBOARD) { inclusive = false }; launchSingleTop = true }
                        else nav.navigate(r) { launchSingleTop = true }
                    }
                )
            }
            // „Hei FORJA" — asistentul vocal (4.6): se deschide ca o foaie, de oriunde.
            composable(
                Route.VOICE,
                enterTransition = modalEnter, exitTransition = fadeExit,
                popEnterTransition = riseEnter, popExitTransition = modalExit
            ) {
                val key = voiceListen + fabListen
                VoiceScreen(
                    listenKey = if (key != listenConsumed) key else 0,
                    onListenConsumed = { listenConsumed = key },
                    onBack = { if (!nav.popBackStack()) nav.navigate(Route.DASHBOARD) { launchSingleTop = true } }
                )
            }
            composable(Route.PROFILE) {
                ProfileScreen(
                    onOpenProbe = { nav.navigate(Route.MUSIC_PROBE) { launchSingleTop = true } },
                    onLogout = {
                        // Oprește sincronizarea și uită alegerile cât timp contul încă e cel legat (înainte de signOut).
                        try { com.forja.app.core.sync.CollectionSettings.logout(app) } catch (_: Exception) { }
                        navScope.launch {
                            // Contul se închide întreg chiar dacă ecranul dispare între timp (recreare): niciodată
                            // „potriviri șterse, dar încă conectat”. Profilul arată „Se deconectează…” cât durează.
                            withContext(NonCancellable) {
                                // Prieteni din agendă: numărul, comutatorul și potrivirile sunt ale ACESTUI cont — nu trec la următorul.
                                // DELETE-ul pe site are nevoie de token, deci înainte de signOut (cel mult 1,5 s; altfel expiră singur în 30 de zile).
                                try { com.forja.app.core.social.ContactsSync.logout(app) } catch (_: Exception) { }
                                // Găsirea: telefonul iese de pe site-ul contului vechi acum, nu după 30 de zile.
                                try { com.forja.app.core.recovery.LostPhoneRecovery.logout(app) } catch (_: Exception) { }
                                app.auth.logout()
                                // Jurnalele din Room rămân pe telefon, cu stăpânul lor: dacă revine același cont, le regăsește;
                                // dacă intră altul, Journals.claim le golește la intrare, înainte de „Azi” (claimThenOpen).
                                // Ieșirea din cont = de la capăt, cu tot cu prezentare și permisiuni. Contractul e al contului: se semnează din nou.
                                app.prefs.resetFirstRun()
                                // Alt om pe același telefon: ghidajele de la prima vizită pornesc din nou.
                                try { com.forja.app.core.designsystem.components.Tutorial.reset(app) } catch (_: Exception) { }
                                // Progresul din ZID și ASALT e al persoanei: următorul om începe de la nivelul 1.
                                try { com.forja.app.core.games.GameStore.reset(app) } catch (_: Exception) { }
                                try { app.prefs.clearContract() } catch (_: Exception) { }
                            }
                            try { nav.navigate(Route.ONBOARDING) { popUpTo(Route.DASHBOARD) { inclusive = true } } } catch (_: Exception) { }
                        }
                    },
                    onOpenMapGhost = { nav.navigate(Route.MAP) },
                    onOpenPermissions = { nav.navigate(Route.PERMISSIONS) },
                    onOpenContract = { nav.navigate(Route.CONTRACT) { launchSingleTop = true } },
                    onOpenVoice = { nav.navigate(Route.VOICE) { launchSingleTop = true } },
                    onOpenSoldier = { nav.navigate(Route.SOLDIER) { launchSingleTop = true } }
                )
            }
        }

        AnimatedVisibility(
            visible = tabsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(), exit = fadeOut()
        ) {
            ForjaTabBar(current = currentTab, onSelect = ::goTab)
        }
        // „Hei FORJA": microfonul plutitor — o atingere, un bip, și spui comanda — de pe orice ecran principal
        // și din sesiunea live de antrenament („pauză”, „am terminat seria”, „următorul exercițiu”).
        AnimatedVisibility(
            visible = tabsVisible || route == Route.WORKOUT_LIVE,
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(), exit = fadeOut()
        ) {
            VoiceFab(
                wakeOn = voiceWakeOn,
                onClick = {
                    fabListen++
                    nav.navigate(Route.VOICE) { launchSingleTop = true }
                },
                modifier = Modifier.navigationBarsPadding().padding(end = 16.dp, bottom = if (tabsVisible) 96.dp else 24.dp)
            )
        }
        // Contractul v3: cine a semnat v2 vede o singură dată, pe „Azi”, rândurile noi și „Semnează”.
        com.forja.app.feature.permissions.ContractResignHost(active = route == Route.DASHBOARD) { nav.navigate(Route.CONTRACT) { launchSingleTop = true } }

        // Pastila globală a inventarului: oriunde, cât timp rularea e activă (nu pe ecranele Inventarului, unde
        // progresul e deja pe ecran, și nu în modurile „Cât aștepți”, care o au în antet); la final: „Gata” + toast.
        val inventoryRoutes = setOf(Route.CLEANUP, Route.WAIT_ZID, Route.WAIT_ASALT, Route.WAIT_MUSIC)
        val noPillRoutes = setOf(Route.ONBOARDING, Route.LOGIN, Route.REGISTER, Route.SCANNER, Route.MEAL_CAMERA)
        // În jocuri toastul ar acoperi antetul și rândurile de sus ale tablei: acolo pastila din antet devine „Gata”,
        // iar pauza și finalul arată „Dosarele sunt gata”. Vibrația de final rămâne pe orice rută.
        val noToastRoutes = setOf(Route.CLEANUP, Route.WAIT_ZID, Route.WAIT_ASALT)
        com.forja.app.feature.inventory.InventoryPillHost(
            visibleOnRoute = route != null && route !in inventoryRoutes && route !in noPillRoutes,
            toastOnRoute = route != null && route !in noToastRoutes && route !in noPillRoutes,
            onOpen = { openInventory(it) },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}
