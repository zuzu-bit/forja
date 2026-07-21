package com.forja.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forja.app.core.designsystem.EmberBrush
import com.forja.app.core.designsystem.Molten
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.ForjaButton
import com.forja.app.core.designsystem.components.ForjaTextField

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onGoToRegister: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Wordmark()

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Intră în cont",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Continuă-ți progresul.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(24.dp))

            ForjaTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = "Email",
                keyboardType = KeyboardType.Email
            )
            Spacer(modifier = Modifier.height(12.dp))
            ForjaTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = "Parolă",
                isPassword = true
            )

            state.error?.let { error ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Start)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            ForjaButton(
                text = "Autentificare",
                onClick = { viewModel.login(onLoginSuccess) },
                loading = state.isLoading
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Nu ai cont?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                TextButton(onClick = onGoToRegister) {
                    Text(
                        text = "Creează unul",
                        style = MaterialTheme.typography.labelLarge,
                        color = Molten
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** Wordmark-ul FORJA cu bara-semnătură din metal incandescent. */
@Composable
fun Wordmark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "FORJA",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(EmberBrush)
        )
    }
}
