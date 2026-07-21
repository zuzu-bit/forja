package com.forja.app.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forja.app.core.designsystem.Ember
import com.forja.app.core.designsystem.Molten
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.ForjaCard

@Composable
fun DashboardScreen(
    onLoggedOut: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val user by viewModel.user.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "FORJA",
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 4.sp),
            color = Molten
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Salut, ${user?.name?.takeIf { it.isNotBlank() } ?: "campionule"}",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Astăzi forjăm fundația.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.FitnessCenter,
                title = "Antrenament",
                stage = "Etapa 2",
                active = true
            )
            FeatureCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Restaurant,
                title = "Nutriție",
                stage = "Etapa 2",
                active = false
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Bedtime,
                title = "Somn",
                stage = "Etapa 3",
                active = false
            )
            FeatureCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.Timer,
                title = "Focus",
                stage = "Etapa 5",
                active = false
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(
            onClick = { viewModel.logout(onLoggedOut) },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(
                text = "Deconectare",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun FeatureCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    stage: String,
    active: Boolean
) {
    ForjaCard(modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) Ember else TextSecondary,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (active) "Urmează: $stage" else "În curând · $stage",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
    }
}
