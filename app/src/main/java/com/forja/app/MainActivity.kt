package com.forja.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.forja.app.core.designsystem.ForjaTheme
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.components.ForjaTab
import com.forja.app.core.designsystem.components.ForjaTabBar
import com.forja.app.core.designsystem.components.LocalToast
import com.forja.app.core.designsystem.components.ToastHost
import com.forja.app.core.designsystem.components.ToastState
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
import com.forja.app.feature.workout.WorkoutLiveScreen
import com.forja.app.feature.workout.WorkoutScreen
import com.forja.app.navigation.Route
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ForjaTheme { ForjaRoot() }
        }
    }
}

@Composable
private fun ForjaRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val toast = remember { ToastState() }
    val scope = rememberCoroutineScope()

    var splashDone by remember { mutableStateOf(false) }
    var startRoute by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val onboardingDone = app.prefs.onboardingDone.first()
        startRoute = when {
            !onboardingDone -> Route.ONBOARDING
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

    // Energie de la prieteni → toast live.
    LaunchedEffect(startRoute, splashDone) {
        val uid = app.auth.currentUid ?: return@LaunchedEffect
        scope.launch {
            app.friends.energyFlow(uid).collect { toast.show(it) }
        }
    }
}

@Composable
private fun MainNav(app: ForjaApp, startRoute: String, toast: ToastState) {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    val tabFor: Map<String, ForjaTab> = mapOf(
        Route.DASHBOARD to ForjaTab.Azi,
        Route.WORKOUT to ForjaTab.Antrenament,
        Route.MAP to ForjaTab.Harta,
        Route.NUTRITION to ForjaTab.Nutritie,
        Route.SLEEP to ForjaTab.Somn,
        Route.FOCUS to ForjaTab.Focus
    )
    val currentTab = tabFor[route ?: ""]
    val tabsVisible = route in setOf(
        Route.DASHBOARD, Route.WORKOUT, Route.NUTRITION, Route.SLEEP, Route.MAP, Route.FOCUS, Route.PROFILE
    )

    // Fantoma: oglinda locală mereu la zi, respectată de orice publicare.
    LaunchedEffect(Unit) {
        app.prefs.ghostUntilLocal.collect { app.presence.ghostUntilCache = it }
    }

    // Publicarea prezenței cât timp aplicația e în prim-plan (fundalul e treaba BgLocation).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val uid = app.auth.currentUid
            if (uid != null) {
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        app.presence.start(uid) { app.presence.isGhostNow() }
                        com.forja.app.core.location.BgLocation.registerIfReady(app)
                    }
                    Lifecycle.Event.ON_STOP -> app.presence.stop()
                    else -> {}
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
        }
        nav.navigate(target) {
            popUpTo(Route.DASHBOARD) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = startRoute) {
            composable(Route.ONBOARDING) {
                OnboardingScreen(onFinished = {
                    nav.navigate(if (app.auth.isLoggedIn) Route.DASHBOARD else Route.LOGIN) {
                        popUpTo(Route.ONBOARDING) { inclusive = true }
                    }
                })
            }
            composable(Route.LOGIN) {
                AuthScreens(startInLogin = true, onAuthed = {
                    nav.navigate(Route.DASHBOARD) { popUpTo(Route.LOGIN) { inclusive = true } }
                })
            }
            composable(Route.REGISTER) {
                AuthScreens(startInLogin = false, onAuthed = {
                    nav.navigate(Route.DASHBOARD) { popUpTo(Route.REGISTER) { inclusive = true } }
                })
            }
            composable(Route.DASHBOARD) {
                DashboardScreen(
                    onOpenModule = { r -> nav.navigate(r) },
                    onOpenProfile = { nav.navigate(Route.PROFILE) },
                    onOpenMap = { nav.navigate(Route.MAP) },
                    onOpenActivities = { nav.navigate(Route.ACTIVITIES) }
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
            composable(Route.CLEANUP) {
                com.forja.app.feature.cleanup.CleanupScreen(onBack = { nav.popBackStack() })
            }
            composable(Route.PERMISSIONS) {
                com.forja.app.feature.permissions.PermissionsScreen(onBack = { nav.popBackStack() })
            }
            composable(Route.PROFILE) {
                ProfileScreen(
                    onLogout = {
                        app.auth.logout()
                        nav.navigate(Route.LOGIN) { popUpTo(Route.DASHBOARD) { inclusive = true } }
                    },
                    onOpenMapGhost = { nav.navigate(Route.MAP) },
                    onOpenPermissions = { nav.navigate(Route.PERMISSIONS) }
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
    }
}
