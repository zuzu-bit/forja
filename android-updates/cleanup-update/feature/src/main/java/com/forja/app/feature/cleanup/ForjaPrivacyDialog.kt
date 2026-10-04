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
                PrivacySection("Locația ta", "Partajarea pornește doar când o activezi. Destinatarii și accesul la istoric se aleg separat în Cine mă vede. Fantomă păstrează numai excepțiile alese pentru locația curentă; Oprește toate partajările revocă inclusiv accesul la istoric. Pozițiile fără actualizări dispar de pe hartă după două minute. Pentru sesiunile vechi, neconfigurate, se păstrează vizibilitatea către prietenii acceptați.")
                PrivacySection("Doar voi doi", "Partajarea continuă este vizibilă doar partenerului acceptat. Continuă cu notificare și se reia după întreruperi cât timp acordul este activ. Fiecare persoană își pornește și își oprește propria partajare.")
                PrivacySection("Agenda ta", "Accesul la contacte permite citirea locală a numelor și numerelor. Sincronizarea online compară numerele, fără numele din agendă, numai după acordul tău. Se repetă zilnic cât timp este activată, cu maximum 10.000 de numere pe zi. Prieteniile se acceptă separat.")
                PrivacySection("Găsirea după număr", "Numărul se verifică prin SMS. Poți alege separat dacă persoanele care au numărul tău te găsesc în FORJA și poți opri această opțiune oricând.")
                PrivacySection("Telefonul pierdut", "Numai contul tău poate cere localizarea din site, după activarea pe telefon. Serviciul rămâne pregătit cu notificare și folosește GPS-ul la cererea de găsire. Căutarea durează 5, 15 sau 30 de minute. Ultima poziție se păstrează cel mult 24 de ore. Partenerul nu poate porni căutarea.")
                PrivacySection("Locuri și explorare", "Explorarea personală pornește cu acord separat și notificare. Traseele, zonele și vizitele se păstrează în cont până le ștergi, independent de partajare. O zonă este o celulă de 200 m proiectați, nu dovada parcurgerii tuturor străzilor. O vizită cere peste 5 ore susținute de poziții cu precizie de cel mult 50 m, în raza de 100 m; intervalele fără GPS de peste 5 minute și salturile nu se numără. Punctele offline rămân pe telefon; serverul acceptă cel mult 7 zile de întârziere. Vechea explorare grosieră rămâne separată, limitată la ultimele 500 de celule.")
                PrivacySection("Harta 2D și 3D", "OpenFreeMap, OpenMapTiles și OpenStreetMap furnizează fundalul și clădirile. Furnizorul hărții primește cererile pentru zona afișată; nu primește tokenul contului sau istoricul FORJA. 3D folosește înălțimile disponibile ale clădirilor. Dacă randarea sau rețeaua nu sunt disponibile, harta 2D rămâne utilizabilă.")
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
