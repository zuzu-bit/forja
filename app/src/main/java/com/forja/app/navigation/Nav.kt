package com.forja.app.navigation

object Route {
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val DASHBOARD = "dashboard"
    const val WORKOUT = "workout"
    const val WORKOUT_LIVE = "workout_live"
    const val NUTRITION = "nutrition"
    const val SCANNER = "scanner"
    const val SLEEP = "sleep"
    const val MAP = "map"
    const val FOCUS = "focus"
    const val BREATH = "breath"
    const val PROFILE = "profile"
    const val ACTIVITIES = "activities"
    const val ACTIVITY_DETAIL = "activity/{id}"
    const val MEAL_CAMERA = "meal_camera"
    const val CLEANUP = "cleanup"            // Inventarul 4.3 (S1–S6); ruta rămâne „cleanup” pentru notificări și legături
    const val WAIT_ZID = "inventory_zid"         // „Cât aștepți” · ZID (jocul cu piese, 4.4)
    const val WAIT_ASALT = "inventory_asalt"     // „Cât aștepți” · ASALT (nicovala și scânteia, 4.4)
    const val WAIT_MUSIC = "inventory_music"     // „Cât aștepți” · Muzică (S3c)
    const val PERMISSIONS = "permissions"
    const val VOICE = "voice"                // „Hei FORJA" — asistentul vocal (4.6)
    const val CONTRACT = "contract"
    const val MUSIC_PROBE = "music_probe"    // proba ascunsă a pornirii muzicii (Profil → 5 atingeri pe versiune)
    fun activityDetail(id: Long) = "activity/$id"
}
