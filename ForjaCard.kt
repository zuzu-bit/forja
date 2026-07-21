package com.forja.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forja.app.core.designsystem.Coal
import com.forja.app.core.designsystem.ForjaTheme
import com.forja.app.core.designsystem.Molten
import com.forja.app.core.navigation.ForjaNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ForjaTheme {
                val initialLoggedIn by viewModel.initialLoggedIn.collectAsStateWithLifecycle()
                val state = initialLoggedIn
                if (state == null) {
                    SplashScreen()
                } else {
                    ForjaNavHost(startLoggedIn = state)
                }
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Coal),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "FORJA",
            style = MaterialTheme.typography.displayLarge,
            color = Molten
        )
    }
}
