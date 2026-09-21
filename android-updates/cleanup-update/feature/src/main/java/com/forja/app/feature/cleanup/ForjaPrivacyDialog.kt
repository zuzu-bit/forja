package com.forja.app.feature.cleanup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared, on-demand explanation. Permission requests and consent remain in their own flows. */
@Composable
internal fun ForjaPrivacyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confidențialitate") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrivacySection("Locația ta", "Partajarea pornește doar când o activezi. Prietenii acceptați văd poziția, viteza și bateria în timpul sesiunii. Poți opri oricând; pozițiile fără actualizări dispar de pe hartă după două minute.")
                PrivacySection("Doar voi doi", "Partajarea continuă este vizibilă doar partenerului acceptat. Continuă cu notificare și se reia după întreruperi cât timp acordul este activ. Fiecare persoană își pornește și își oprește propria partajare.")
                PrivacySection("Agenda ta", "Accesul la contacte permite citirea locală a numelor și numerelor. Sincronizarea online compară numerele, fără numele din agendă, numai după acordul tău. Se repetă zilnic cât timp este activată, cu maximum 10.000 de numere pe zi. Prieteniile se acceptă separat.")
                PrivacySection("Găsirea după număr", "Numărul se verifică prin SMS. Poți alege separat dacă persoanele care au numărul tău te găsesc în FORJA și poți opri această opțiune oricând.")
                PrivacySection("Telefonul pierdut", "Numai contul tău poate cere localizarea din site, după activarea pe telefon. Serviciul rămâne pregătit cu notificare și folosește GPS-ul la cererea de găsire. Căutarea durează 5, 15 sau 30 de minute. Ultima poziție se păstrează cel mult 24 de ore. Partenerul nu poate porni căutarea.")
                PrivacySection("Locuri și explorare", "Locurile salvate și zonele explorate sunt private. Se păstrează ultimele 500 de zone explorate. Distanțele sunt estimări GPS.")
                PrivacySection("Conexiunea telefonului", "Actualizările au nevoie de internet, locația Android și permisiunile necesare. GPS-ul oprit nu poate fi pornit din site. Restricțiile bateriei pot întârzia actualizările; după o oprire forțată trebuie redeschisă aplicația. Locația «Tot timpul» permite reluarea după repornirea telefonului.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Am înțeles") } }
    )
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(body)
    }
}
