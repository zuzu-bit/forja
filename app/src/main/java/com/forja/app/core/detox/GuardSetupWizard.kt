package com.forja.app.core.detox

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*

/**
 * Ghidajul în 3 pași pentru pornirea serviciului de Accesibilitate FORJA.
 * Reutilizat din Focus (Paznicul Detox) și din Asistentul vocal (Hei FORJA).
 *
 * [contextLabel] — numele entității care folosește serviciul („paznicul Detox", „asistentul vocal").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardSetupWizard(
    step: Int,
    contextLabel: String,
    onOpenApps: () -> Unit,
    onNext: () -> Unit,
    onOpenAccess: () -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("PASUL $step DIN 3", style = monoLabel(9, 0.14f).copy(color = Accent2))
            Spacer(Modifier.height(6.dp))
            when (step) {
                1 -> {
                    Text("Încearcă să pornești $contextLabel", style = TitleModule.copy(fontSize = 20.sp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "În Accesibilitate, atinge „FORJA · Accesibilitate” și pornește-l. Android îl blochează: „Setare restricționată”.",
                        style = Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "E normal. Blocajul deschide pasul 2.",
                        style = BodySmall.copy(color = Accent2, lineHeight = 19.sp)
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Deschide Accesibilitatea", onClick = onOpenAccess, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Am încercat", onClick = onNext, modifier = Modifier.fillMaxWidth())
                }
                2 -> {
                    Text("Deblochează setările", style = TitleModule.copy(fontSize = 20.sp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Setări → Aplicații → FORJA.",
                        style = Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("1.  Sus-dreapta: ⋮", style = BodySmall.copy(color = TextSecondary, lineHeight = 19.sp))
                    Spacer(Modifier.height(6.dp))
                    Text("2.  „Permite setările restricționate” (Allow restricted settings). Dacă cere codul, introdu-l.", style = BodySmall.copy(color = TextSecondary, lineHeight = 19.sp))
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Deschide Aplicații", onClick = onOpenApps, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Am făcut", onClick = onNext, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    Text("Pornește $contextLabel", style = TitleModule.copy(fontSize = 20.sp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Înapoi în Accesibilitate: pornește „FORJA · Accesibilitate”. Acum merge.",
                        style = Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary)
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Deschide Accesibilitatea", onClick = onOpenAccess, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Am terminat", onClick = onClose, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
