package com.forja.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.forja.app.core.designsystem.ForjaTheme
import com.forja.app.feature.cleanup.SleepHub
import com.forja.app.feature.cleanup.SocialMapScreen

/** Test-only host, packaged under a different application ID and never in the production APK. */
class TestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForjaTheme {
                if (intent.getStringExtra("screen") == "sleep") SleepHub() else SocialMapScreen()
            }
        }
    }
}
