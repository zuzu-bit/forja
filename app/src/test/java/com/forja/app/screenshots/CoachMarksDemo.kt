package com.forja.app.screenshots

import androidx.compose.runtime.Composable
import com.forja.app.core.designsystem.components.CoachMarksHost
import com.forja.app.core.designsystem.components.CoachStep
import com.forja.app.feature.inventory.InventorySamples
import com.forja.app.feature.inventory.InventoryStartContent
import com.forja.app.feature.inventory.StartActions
import com.forja.app.feature.inventory.StartCoachSteps

/*
 * Ecran-demonstrație pentru ghidaj: startul ADEVĂRAT al Inventarului (InventoryStartContent cu InventorySamples.start,
 * aceleași ținte coachTarget ca în aplicație) sub pașii lui reali (StartCoachSteps). Doar pentru capturi — gazda
 * internă CoachMarksHost (fără DataStore), `startAt` alege pasul.
 * Între „inv_kind” și „inv_scope” stă un pas fără țintă pe ecran: gazda trebuie să-l sară (de aceea punctele arată
 * 3 pași, nu 4). Așa capturile verifică și ghidajul, și ecranul pe care stă el, nu o machetă rămasă în urmă.
 */

internal val DemoSteps: List<CoachStep> = StartCoachSteps.toMutableList().apply {
    add(1, CoachStep("inv_album", "Pas fără țintă pe ecran: se sare singur."))
}

@Composable
fun CoachMarksDemo(startAt: Int, active: Boolean = true) {
    CoachMarksHost(steps = DemoSteps, active = active, onFinish = {}, startAt = startAt) {
        InventoryStartContent(InventorySamples.start, StartActions())
    }
}
