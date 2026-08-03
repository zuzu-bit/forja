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
    const val CLEANUP = "cleanup"
    const val PERMISSIONS = "permissions"
    fun activityDetail(id: Long) = "activity/$id"
}
