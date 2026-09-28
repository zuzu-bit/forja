package com.forja.app.feature.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.data.Prefs
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.sync.CollectionSettings
import com.forja.app.core.sync.GalleryUploader
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val contractDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale("ro"))
private fun fmtSigned(ms: Long): String = contractDate.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** O secțiune a contractului: eticheta și rândurile ei (fiecare rând = o propoziție sau două, fără juridisme). */
private class Clause(val label: String, val lines: List<String>)

private val CLAUSES = listOf(
    Clause(
        "Ce se încarcă", listOf(
            "Locația și opririle: pozițiile, locurile unde ai stat, zonele pe care le deblochezi.",
            "Activitățile fizice: antrenamente, alergări, pași — jurnalul de marș.",
            "Mesele analizate: poza farfuriei, codul de bare, ce scrii tu la o masă.",
            "Galeria întreagă, indiferent de mărime: fiecare poză ca miniatură de cel mult 512 px, treptat, în loturi de 50, pe Wi-Fi implicit. Originalul nu pleacă niciodată.",
            "Documentele: PDF-urile de cel mult 4 MB și fragmente scurte din fișierele text ale folderului ales la Curățenie, plus copii ale pozelor și fișierelor de aruncat.",
            "Activitatea în aplicații: numele aplicațiilor și timpul petrecut în ele — numai dacă dai accesul la utilizare din Setări Android.",
            "Agenda: doar amprente ale numerelor, ca prietenii cu FORJA să apară singuri. Numele și numerele rămân pe telefon.",
            "Ce asculți: titlul și artistul, ca prietenii să vadă pe hartă.",
            "Înregistrările nopții: sunetul dormitorului, în bucăți de 30 de minute, urcate dimineața pe Wi-Fi — doar când pornești tu Stingerea cu microfonul.",
            "Microfonul live, ziua, nu pornește prin acest contract. Are permisiune și acord separat și rămâne oprit."
        )
    ),
    Clause(
        "Unde", listOf(
            "În contul tău FORJA de pe site și pe serverul FORJA, găzduit pe Cloudflare.",
            "Analiza cu model se face pe serverul FORJA cu modele Gemini (Google), Groq și Cloudflare. Modelele primesc doar materialul de analizat: miniatura, fragmentul, bucata de sunet."
        )
    ),
    Clause(
        "Cât se păstrează", listOf(
            "Copiile de curățenie și miniaturile galeriei: 24 de ore pe site, apoi dispar.",
            "Înregistrarea nopții: 7 zile pe server, apoi dispare. Pe telefon stă 24 de ore.",
            "Jurnalele (mese, somn, activități), locația și opririle: cât ai contul.",
            "Amprenta numărului tău: 30 de zile, reînnoită cât timp contractul e semnat."
        )
    ),
    Clause(
        "Cine vede", listOf(
            "Doar tu, pe site-ul tău, cu contul tău.",
            "Prietenii văd ce văd și acum: poziția, când nu ești fantomă, și stările. Nimic altceva."
        )
    ),
    Clause(
        "Ce nu se citește niciodată", listOf(
            "Mesajele. Parolele. Conținutul ecranului. Apelurile. Nimic din ce scrii în alte aplicații."
        )
    ),
    Clause(
        "Cum revoci", listOf(
            "Profil → Contractul de securitate → Revocă. Oprește tot pe loc și cere ștergerea a ce se poate șterge de pe site: sesiunea de sincronizare și listarea după număr.",
            "Copiile și miniaturile expiră singure în 24 de ore, nopțile în 7 zile. Jurnalele rămân în contul tău până îl închizi."
        )
    )
)

/**
 * Contractul de securitate — un singur acord în locul comutatoarelor. Se citește întreg, se bifează, se semnează.
 * Semnătura = versiunea + momentul, pe telefon (Prefs) și în users/{uid}.contract. Semnat: pornește tot ([CollectionSettings.enableAll]).
 * Revocat: oprește tot și cere ștergerea ([CollectionSettings.disableAll]).
 */
@Composable
fun ContractScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { ForjaApp.from(context) }
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()

    val signedAt by app.prefs.contractSignedAt.collectAsState(initial = -1L)
    val signed by app.prefs.contractSigned.collectAsState(initial = false)
    val loggedIn = app.auth.currentUid != null
    var accepted by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }

    fun sign() {
        val uid = app.auth.currentUid ?: run { toast.show("Intră în cont ca să semnezi."); return }
        busy = true
        scope.launch {
            val now = System.currentTimeMillis()
            try {
                app.prefs.setContractSigned(now, Prefs.CONTRACT_VERSION)
                try {
                    FirebaseFirestore.getInstance().collection("users").document(uid)
                        .set(mapOf("contract" to mapOf("version" to Prefs.CONTRACT_VERSION, "at" to now)), SetOptions.merge()).await()
                } catch (_: Exception) { }
                try { CollectionSettings.enableAll(app) } catch (_: Exception) { }
                toast.show("Semnat. Ce are permisiune pornește acum; restul așteaptă bifele din Echipare.")
            } catch (_: Exception) {
                toast.show("Nu a mers. Încearcă din nou.")
            }
            busy = false
            confirmRevoke = false
        }
    }

    fun revoke() {
        busy = true
        scope.launch {
            val uid = app.auth.currentUid
            val at = signedAt
            try {
                try { CollectionSettings.disableAll(app) } catch (_: Exception) { }
                app.prefs.clearContract()
                if (uid != null) {
                    try {
                        FirebaseFirestore.getInstance().collection("users").document(uid)
                            .set(mapOf("contract" to mapOf("version" to Prefs.CONTRACT_VERSION, "at" to at, "revokedAt" to System.currentTimeMillis())), SetOptions.merge()).await()
                    } catch (_: Exception) { }
                }
                toast.show("Revocat. Nimic nu mai pleacă de pe telefon; ștergerea de pe site e cerută.")
            } catch (_: Exception) {
                toast.show("Nu a mers. Încearcă din nou.")
            }
            accepted = false
            confirmRevoke = false
            busy = false
        }
    }

    Box(Modifier.fillMaxSize().topoBackground(decor = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 30.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton("Închide", onClick = onBack, padV = 8.dp)
            }
            Spacer(Modifier.height(10.dp))
            Reveal(index = 0) { StampLabel("CONTRACT DE SECURITATE") }
            Spacer(Modifier.height(12.dp))
            Reveal(index = 1) {
                Text("Citești. Semnezi. Știi.", style = TitleModule.copy(fontSize = 26.sp, lineHeight = 29.sp))
            }
            Spacer(Modifier.height(6.dp))
            Reveal(index = 2) {
                Text(
                    "Un singur acord, în locul comutatoarelor. Tot ce pleacă de pe telefon e scris aici, fără ocolișuri.",
                    style = Body.copy(fontSize = 14.sp, lineHeight = 19.sp)
                )
            }

            Spacer(Modifier.height(18.dp))
            CLAUSES.forEachIndexed { i, c ->
                Reveal(index = 3 + i) {
                    ForjaCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), stroke = StrokeCard) {
                        SectionLabel(c.label.uppercase())
                        Spacer(Modifier.height(8.dp))
                        c.lines.forEachIndexed { j, line ->
                            if (j > 0) Spacer(Modifier.height(6.dp))
                            Row {
                                Text("—", style = BodySmall.copy(color = Accent2))
                                Spacer(Modifier.width(8.dp))
                                Text(line, style = BodySmall.copy(color = TextSecondary, lineHeight = 17.sp))
                            }
                        }
                    }
                }
            }

            Text(
                "Permisiunile Android (locație, poze, microfon, agendă) se dau separat, în Echipare. Contractul spune ce facem cu ele; fără o permisiune, categoria ei rămâne pe telefon. Ce nu e limitat la Wi-Fi poate consuma internet mobil.",
                style = BodyTiny.copy(color = TextDim)
            )
            Spacer(Modifier.height(18.dp))

            if (signed && signedAt > 0) {
                ForjaCard(Modifier.fillMaxWidth(), fill = Surface2) {
                    Text("Semnat pe ${fmtSigned(signedAt)} · versiunea ${Prefs.CONTRACT_VERSION}", style = BodyStrong.copy(fontSize = 14.sp, color = Positive))
                    val galleryStatus = remember(signedAt, busy) { GalleryUploader.status(context) }
                    if (galleryStatus.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(galleryStatus, style = BodyTiny.copy(color = TextSecondary))
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!confirmRevoke) {
                        SecondaryButton("Revocă contractul", onClick = { confirmRevoke = true }, modifier = Modifier.fillMaxWidth(), padV = 10.dp)
                    } else {
                        Text(
                            "Revocarea oprește tot pe loc și cere ștergerea de pe site. Semnătura dispare de pe telefon.",
                            style = BodyTiny.copy(color = EmberHot)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrimaryButton(if (busy) "Se revocă" else "Da, revoc", onClick = ::revoke, small = true, enabled = !busy, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            SecondaryButton("Renunț", onClick = { confirmRevoke = false }, padV = 10.dp)
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().pressable({ accepted = !accepted }, scaleDown = 0.99f, haptic = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContractCheckbox(on = accepted)
                    Spacer(Modifier.width(12.dp))
                    Text("Am citit și îmi asum", style = BodyStrong.copy(fontSize = 15.sp))
                }
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    if (busy) "Se semnează" else "Semnez",
                    onClick = ::sign, enabled = accepted && !busy && loggedIn, modifier = Modifier.fillMaxWidth()
                )
                if (!loggedIn) {
                    Spacer(Modifier.height(8.dp))
                    Text("Intră în cont ca să semnezi. Contractul e legat de contul tău.", style = BodyTiny.copy(color = EmberHot))
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Nimeni nu te grăbește. Citește tot.", style = BodyTiny.copy(color = TextDim))
        }
    }
}

/** Căsuța de bifat: pătrat 22dp, colțuri 4dp; plină cu bifă când e gata. */
@Composable
private fun ContractCheckbox(on: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(22.dp)
            .clip(shape)
            .background(if (on) Positive else Surface2)
            .border(1.dp, if (on) Positive else StrokeCardStrong, shape),
        contentAlignment = Alignment.Center
    ) {
        PopIn(visible = on, fromScale = 0.4f) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}
