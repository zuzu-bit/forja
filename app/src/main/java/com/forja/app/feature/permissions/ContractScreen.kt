package com.forja.app.feature.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

private val contractDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale("ro"))
private fun fmtSigned(ms: Long): String = contractDate.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** Cum e marcat un rând față de versiunea semnată înainte (v2): nou sau corectat. */
enum class ClauseMark { None, New, Fixed }

/** Un rând al contractului: o propoziție sau două, fără juridisme. */
class ClauseLine(val text: String, val mark: ClauseMark = ClauseMark.None)

/** O secțiune a contractului: eticheta și rândurile ei. */
class Clause(val label: String, val lines: List<ClauseLine>)

private fun keep(t: String) = ClauseLine(t)
private fun new(t: String) = ClauseLine(t, ClauseMark.New)
private fun fixed(t: String) = ClauseLine(t, ClauseMark.Fixed)

/**
 * Contractul v3 (4.4). Fiecare rând spune exact ce face telefonul (auditul din understanding44/data-contract.md §5):
 * fără „pași” (nu există), codul de bare la OpenFoodFacts, Firebase vs Cloudflare, numerele de telefon, galeria pe site
 * 24 h / 500, locația pe site 24 h; nou: găsirea, Inventarul pe site, muzica, antrenamentele, ținta de calorii.
 */
val CONTRACT_CLAUSES: List<Clause> = listOf(
    Clause(
        "Ce se încarcă", listOf(
            keep("Locația și opririle: pozițiile, locurile unde ai stat, zonele pe care le deblochezi."),
            new("Găsirea telefonului: ultima poziție și bateria, doar pentru tine, ca să-l găsești de pe site. Când îl cauți sau îl suni, telefonul arată o notificare. Se păstrează 7 zile."),
            fixed("Mișcarea: alergări, plimbări, ture și antrenamente, cu durata, distanța și caloriile — jurnalul de marș."),
            fixed("Mesele: poza farfuriei și ce scrii merg la analiză pe serverul FORJA și nu rămân acolo. Codul de bare merge la OpenFoodFacts. Jurnalul meselor stă în contul tău."),
            new("Ținta de calorii și de macro, ca pe site să vezi ziua față de ea."),
            fixed("Galeria: fiecare poză ca miniatură de cel mult 512 px, treptat, în loturi de 50, pe Wi-Fi implicit. Site-ul arată ultimele 24 de ore, cel mult 500. Originalul nu pleacă niciodată."),
            fixed("Documentele, la Inventar: PDF-urile de cel mult 4 MB și fragmente scurte din fișierele text merg la analiză pe serverul FORJA. Nu rămân acolo."),
            new("Inventarul: numele dosarelor, câte fișiere are fiecare și locul ales, la fiecare rulare, ca să le vezi pe site. Rămân ultimele 20 de rulări."),
            keep("Activitatea în aplicații: numele aplicațiilor și timpul petrecut în ele — numai dacă dai accesul la utilizare din Setări Android."),
            fixed("Agenda: numerele pleacă pe o conexiune criptată, iar serverul păstrează doar o amprentă a lor, ca prietenii cu FORJA să apară singuri. Numele rămân pe telefon."),
            fixed("Ce asculți: titlul, artistul și aplicația, ca prietenii să vadă pe hartă."),
            new("Muzica: topul săptămânii (titlu, artist, de câte ori) pe site-ul tău. Încercările de pornire (aplicația și rezultatul, fără titluri) merg la serverul FORJA, ca „Pornește muzica” să meargă."),
            keep("Înregistrările nopții: sunetul dormitorului, în bucăți de 30 de minute, urcate dimineața pe Wi-Fi — doar când pornești tu Stingerea cu microfonul."),
            keep("Microfonul live, ziua, nu pornește prin acest contract. Are permisiune și acord separat și rămâne oprit.")
        )
    ),
    Clause(
        "Unde", listOf(
            fixed("Jurnalele (mese, somn, mișcare), poziția pentru prieteni și prietenii tăi: în Google Firebase."),
            fixed("Site-ul, nopțile, găsirea și analiza cu model: pe serverul FORJA, găzduit pe Cloudflare."),
            keep("Analiza cu model se face pe serverul FORJA cu modele Gemini (Google), Groq și Cloudflare. Modelele primesc doar materialul de analizat: miniatura, fragmentul, bucata de sunet.")
        )
    ),
    Clause(
        "Cât se păstrează", listOf(
            fixed("Miniaturile galeriei: 24 de ore pe site, apoi dispar."),
            keep("Înregistrarea nopții: 7 zile pe server, apoi dispare. Pe telefon stă 24 de ore."),
            new("Ultima poziție a telefonului, pentru găsire: 7 zile, un singur punct."),
            fixed("Pozițiile și opririle pe site: 24 de ore. Pentru prieteni rămâne doar ultima poziție."),
            fixed("Jurnalele (mese, somn, mișcare): cât ai contul."),
            keep("Amprenta numărului tău: 30 de zile, reînnoită cât timp contractul e semnat.")
        )
    ),
    Clause(
        "Cine vede", listOf(
            keep("Doar tu, pe site-ul tău, cu contul tău."),
            fixed("Prietenii văd poziția, când nu ești fantomă, starea, kilometrii săptămânii și, dacă lași „Pe hartă” pornit, ce asculți. Familia îți vede poziția și în modul fantomă. Nimic altceva.")
        )
    ),
    Clause(
        "Ce nu se citește niciodată", listOf(
            keep("Mesajele. Parolele. Conținutul ecranului. Apelurile. Nimic din ce scrii în alte aplicații.")
        )
    ),
    Clause(
        "Cum revoci", listOf(
            fixed("Profil → Contract → Revocă. Oprește tot pe loc și cere ștergerea a ce se poate șterge de pe site: sesiunea de sincronizare, telefonul din Găsire și listarea după număr."),
            fixed("Miniaturile expiră singure în 24 de ore, nopțile în 7 zile. Jurnalele și rulările Inventarului rămân în contul tău până îl închizi.")
        )
    )
)

/** Nota despre permisiuni — parte din textul integral al contractului. */
private const val PERMISSIONS_NOTE =
    "Permisiunile Android (locație, poze, microfon, agendă) se dau separat, în Echipare. Contractul spune ce facem cu ele; " +
        "fără o permisiune, categoria ei rămâne pe telefon. Ce nu e limitat la Wi-Fi poate consuma internet mobil."

/** Ce s-a corectat în v3, într-un rând (foaia de re-semnare). */
internal const val FIXED_SUMMARY = "Corectat: mișcarea, mesele, galeria, agenda, unde stau datele și cine le vede."

/**
 * Rândurile noi, pe scurt, pentru foaia de re-semnare (textul întreg e la „Tot contractul”): titlu + o propoziție,
 * ca foaia să încapă pe S23 cu „Semnează” la vedere.
 */
internal val RESIGN_POINTS: List<Pair<String, String>> = listOf(
    "Găsirea telefonului" to "Ultima poziție și bateria, doar pentru tine. Când îl cauți, telefonul arată o notificare.",
    "Inventarul pe site" to "Dosarele, câte fișiere și locul ales. Ultimele 20 de rulări.",
    "Muzica" to "Topul săptămânii pe site. Pornirile, fără titluri, la serverul FORJA.",
    "Antrenamente și țintă" to "Antrenamentele terminate și ținta de calorii, pe site."
)

/** Semnătura: versiunea + momentul, pe telefon (Prefs) și în users/{uid}.contract; apoi pornește tot. */
suspend fun signContract(app: ForjaApp): Boolean {
    val uid = app.auth.currentUid ?: return false
    val now = System.currentTimeMillis()
    app.prefs.setContractSigned(now, Prefs.CONTRACT_VERSION, uid)
    try {
        // merge păstrează restul hărții `contract`: o revocare veche ar rămâne lângă semnătura nouă, deci o golim.
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .set(mapOf("contract" to mapOf("version" to Prefs.CONTRACT_VERSION, "at" to now, "revokedAt" to null)), SetOptions.merge()).await()
    } catch (_: Exception) { }
    try { CollectionSettings.enableAll(app) } catch (_: Exception) { }
    return true
}

private const val SIGNED_TOAST = "Semnat. Ce are permisiune pornește acum; restul așteaptă bifele din Echipare."

/** Starea ecranului contractului (fără Android: capturile o construiesc direct). */
data class ContractUi(
    /** DataStore încă necitit: doar antetul, ca să nu clipească un ecran în altul. */
    val loading: Boolean = false,
    /** Semnat la versiunea curentă. */
    val signed: Boolean = false,
    val signedAt: Long = 0L,
    val signedVersion: Int = 0,
    /** Semnat o versiune mai veche: rândurile noi și corectate se marchează. */
    val needsResign: Boolean = false,
    val loggedIn: Boolean = true,
    /** Starea sincronizării și a galeriei, doar când spun ceva. */
    val statusLines: List<String> = emptyList(),
)

/**
 * Contractul de securitate — un singur acord în locul comutatoarelor. Se citește întreg, se bifează, se semnează.
 * Semnătura = versiunea + momentul, pe telefon (Prefs) și în users/{uid}.contract. Semnat: pornește tot ([CollectionSettings.enableAll]).
 * Revocat: oprește tot și cere ștergerea ([CollectionSettings.disableAll]).
 * Semnat, ecranul e scurt: sigiliul, data, starea; textul integral se recitește la cerere („Recitește”).
 */
@Composable
fun ContractScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { ForjaApp.from(context) }
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()

    val signedAt by app.prefs.contractSignedAt.collectAsState(initial = -1L)
    // null = DataStore încă necitit: nu arătăm nici contractul întreg, nici sigiliul, ca să nu clipească unul în altul.
    val signedOrNull by app.prefs.contractSigned.collectAsState(initial = null)
    val version by app.prefs.contractVersion.collectAsState(initial = 0)
    val needsResign by app.prefs.contractNeedsResign.collectAsState(initial = false)
    val syncStatus by app.prefs.syncStatus.collectAsState(initial = "")
    var busy by remember { mutableStateOf(false) }
    val galleryStatus = remember(signedAt, busy) { GalleryUploader.status(context) }

    val ui = ContractUi(
        loading = signedOrNull == null || signedAt < 0L,
        signed = signedOrNull == true && signedAt > 0,
        signedAt = signedAt,
        signedVersion = version,
        needsResign = needsResign,
        loggedIn = app.auth.currentUid != null,
        statusLines = listOf(syncStatus, galleryStatus).filter { it.isNotBlank() },
    )

    ContractContent(
        ui = ui,
        busy = busy,
        onClose = onBack,
        onSign = {
            if (app.auth.currentUid == null) { toast.show("Intră în cont ca să semnezi."); return@ContractContent }
            busy = true
            scope.launch {
                val ok = try { signContract(app) } catch (_: Exception) { false }
                toast.show(if (ok) SIGNED_TOAST else "Nu a mers. Încearcă din nou.")
                busy = false
            }
        },
        onRevoke = {
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
                                .set(mapOf("contract" to mapOf("version" to version, "at" to at, "revokedAt" to System.currentTimeMillis())), SetOptions.merge()).await()
                        } catch (_: Exception) { }
                    }
                    toast.show("Revocat. Nimic nu mai pleacă de pe telefon; ștergerea de pe site e cerută.")
                } catch (_: Exception) {
                    toast.show("Nu a mers. Încearcă din nou.")
                }
                busy = false
            }
        }
    )
}

/** Ecranul contractului, fără stare proprie de date — capturat în ContractShots. */
@Composable
fun ContractContent(ui: ContractUi, busy: Boolean, onClose: () -> Unit, onSign: () -> Unit, onRevoke: () -> Unit) {
    var accepted by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    var reread by remember { mutableStateOf(false) }
    // După semnare sau revocare, confirmarea și bifa se închid.
    LaunchedEffect(ui.signed) { confirmRevoke = false; accepted = false }

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
                SecondaryButton("Închide", onClick = onClose, padV = 8.dp)
            }
            Spacer(Modifier.height(10.dp))
            // Semnat, sub ștampilă vine sigiliul (la stânga, unde coboară colțul ștampilei): îi rezervăm colțul.
            Reveal(index = 0) { StampLabel("CONTRACT DE SECURITATE", modifier = Modifier.stampRoom(top = false, bottom = ui.signed)) }
            Spacer(Modifier.height(12.dp))

            if (ui.loading) {
                // Doar antetul, o clipă, cât se citește semnătura.
            } else if (ui.signed) {
                // ── Semnat: sigiliul, data, versiunea; starea sincronizării doar când spune ceva ──
                Reveal(index = 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Positive.copy(alpha = 0.14f))
                                .border(1.dp, Positive.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = Positive, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Semnat.", style = TitleModule.copy(fontSize = 26.sp, lineHeight = 29.sp))
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${fmtSigned(ui.signedAt)} · v${ui.signedVersion.coerceAtLeast(1)}".uppercase(),
                                style = monoLabel(9, 0.12f).copy(color = Positive)
                            )
                        }
                    }
                }
                ui.statusLines.forEach { line ->
                    Spacer(Modifier.height(6.dp))
                    Text(line, style = BodyTiny.copy(color = TextSecondary))
                }

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(
                        if (reread) "Ascunde" else "Recitește", onClick = { reread = !reread },
                        modifier = Modifier.weight(1f), padV = 10.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton(
                        "Revocă", onClick = { confirmRevoke = true }, textColor = LogoutText,
                        modifier = Modifier.weight(1f), padV = 10.dp
                    )
                }
                if (confirmRevoke) {
                    // Singurul text care trebuie citit aici: ce face revocarea, chiar înainte s-o faci.
                    Spacer(Modifier.height(12.dp))
                    ForjaCard(Modifier.fillMaxWidth(), fill = Surface2) {
                        Text("Oprește tot pe loc și cere ștergerea de pe site.", style = BodySmall.copy(color = EmberHot))
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrimaryButton(if (busy) "Se revocă" else "Da, revoc", onClick = onRevoke, small = true, enabled = !busy, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            SecondaryButton("Renunț", onClick = { confirmRevoke = false }, padV = 10.dp)
                        }
                    }
                }
                if (reread) {
                    Spacer(Modifier.height(18.dp))
                    ContractText(reveal = false, marks = false)
                }
            } else {
                // ── Nesemnat (sau semnat o versiune mai veche): textul integral, la semnare ──
                Reveal(index = 1) {
                    Text(
                        if (ui.needsResign) "Contractul, la zi." else "Citești. Semnezi. Știi.",
                        style = TitleModule.copy(fontSize = 26.sp, lineHeight = 29.sp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Reveal(index = 2) {
                    Text(
                        if (ui.needsResign) "Rândurile noi sunt marcate. Până semnezi, sincronizarea stă; jurnalele merg mai departe."
                        else "Un singur acord, în locul comutatoarelor. Tot ce pleacă de pe telefon e scris aici, fără ocolișuri.",
                        style = Body.copy(fontSize = 14.sp, lineHeight = 19.sp)
                    )
                }

                Spacer(Modifier.height(18.dp))
                ContractText(reveal = true, marks = ui.needsResign)
                Spacer(Modifier.height(18.dp))

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
                    onClick = onSign, enabled = accepted && !busy && ui.loggedIn, modifier = Modifier.fillMaxWidth()
                )
                if (!ui.loggedIn) {
                    Spacer(Modifier.height(8.dp))
                    Text("Intră în cont ca să semnezi. Contractul e legat de contul tău.", style = BodyTiny.copy(color = EmberHot))
                }

                Spacer(Modifier.height(16.dp))
                Text("Nimeni nu te grăbește. Citește tot.", style = BodyTiny.copy(color = TextDim))
            }
        }
    }
}

/** Textul integral: clauzele, fiecare rând o propoziție sau două, plus nota despre permisiuni. */
@Composable
private fun ContractText(reveal: Boolean, marks: Boolean) {
    CONTRACT_CLAUSES.forEachIndexed { i, c ->
        val card = @Composable {
            ForjaCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), stroke = StrokeCard) {
                SectionLabel(c.label.uppercase())
                Spacer(Modifier.height(8.dp))
                c.lines.forEachIndexed { j, line ->
                    if (j > 0) Spacer(Modifier.height(6.dp))
                    ClauseRow(line, marks)
                }
            }
        }
        if (reveal) Reveal(index = 3 + i) { card() } else card()
    }
    Text(PERMISSIONS_NOTE, style = BodyTiny.copy(color = TextDim))
}

/** Un rând: liniuța, textul; cu marcaje, eticheta „NOU” / „CORECTAT” deasupra și textul mai aprins. Liniuța ia culoarea etichetei. */
@Composable
private fun ClauseRow(line: ClauseLine, marks: Boolean) {
    val mark = if (marks) line.mark else ClauseMark.None
    Row {
        Text("—", style = BodySmall.copy(color = if (mark == ClauseMark.New) EmberHot else Accent2))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            if (mark != ClauseMark.None) {
                MarkTag(mark)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                line.text,
                style = BodySmall.copy(color = if (mark == ClauseMark.None) TextSecondary else TextPrimary, lineHeight = 17.sp)
            )
        }
    }
}

@Composable
private fun MarkTag(mark: ClauseMark) {
    val shape = RoundedCornerShape(4.dp)
    val color = if (mark == ClauseMark.New) EmberHot else Accent2
    Box(
        Modifier.clip(shape).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(if (mark == ClauseMark.New) "NOU" else "CORECTAT", style = monoLabel(8, 0.14f).copy(color = color))
    }
}

/**
 * Locul colțurilor unei ștampile rotite ([StampLabel] se rotește doar la desen, layoutul nu le vede): rezervă sus și/sau jos
 * cât urcă și cât coboară un colț, din mărimea măsurată — ține și la font mărit. Un părinte care derulează nu le mai taie.
 */
private fun Modifier.stampRoom(top: Boolean = true, bottom: Boolean = true, degrees: Float = 6f): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val rad = Math.toRadians(abs(degrees).toDouble())
    val extra = if (!top && !bottom) 0 else ceil((p.width * sin(rad) + p.height * (cos(rad) - 1.0)) / 2.0).toInt().coerceAtLeast(0)
    val t = if (top) extra else 0
    val b = if (bottom) extra else 0
    layout(p.width, p.height + t + b) { p.place(0, t) }
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

// ───────────────────────────── Re-semnarea (o singură foaie după actualizare) ─────────────────────────────

/**
 * Pe ecranul „Azi”, o singură dată după actualizare: cine a semnat contractul v2 vede rândurile noi și semnează v3
 * dintr-o atingere. Închisă, nu mai apare; Profilul arată „versiune nouă” la Contract până la semnare.
 */
@Composable
fun ContractResignHost(active: Boolean, onOpenContract: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { ForjaApp.from(context) }
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val needs by app.prefs.contractNeedsResign.collectAsState(initial = false)
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(active, needs) {
        if (!active || !needs || app.auth.currentUid == null) return@LaunchedEffect
        if (app.prefs.contractPromptVersion.first() >= Prefs.CONTRACT_VERSION) return@LaunchedEffect
        // După ce se așază ecranul, nu peste animația de intrare.
        delay(900)
        app.prefs.setContractPromptSeen()
        open = true
    }
    if (open) {
        ContractResignSheet(
            busy = busy,
            onSign = {
                busy = true
                scope.launch {
                    val ok = try { signContract(app) } catch (_: Exception) { false }
                    busy = false
                    toast.show(if (ok) SIGNED_TOAST else "Nu a mers. Încearcă din nou.")
                    if (ok) open = false
                }
            },
            onReadAll = { open = false; onOpenContract() },
            onDismiss = { open = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContractResignSheet(busy: Boolean, onSign: () -> Unit, onReadAll: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1,
        contentColor = TextPrimary,
        shape = SheetShape,
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        ContractResignContent(busy, onSign, onReadAll, onDismiss)
    }
}

/** Conținutul foii de re-semnare: ce e nou, pe scurt, un rând cu ce s-a corectat, „Semnează”. Capturat în GasireShots. */
@Composable
fun ContractResignContent(busy: Boolean, onSign: () -> Unit, onReadAll: () -> Unit, onLater: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
    ) {
        // Foaia derulează și taie ce iese din ea: colțurile ștampilei rotite își au locul lor, sus și jos.
        StampLabel("CONTRACT · V${Prefs.CONTRACT_VERSION}", appear = false, modifier = Modifier.stampRoom())
        Spacer(Modifier.height(10.dp))
        Text("Contractul, la zi.", style = TitleModule.copy(fontSize = 26.sp, lineHeight = 29.sp))
        Spacer(Modifier.height(6.dp))
        Text("Până semnezi, sincronizarea stă. Jurnalele merg mai departe.", style = Body.copy(fontSize = 14.sp, lineHeight = 19.sp))
        Spacer(Modifier.height(14.dp))
        ForjaCard(Modifier.fillMaxWidth(), fill = Surface2, stroke = StrokeCard, padding = 12.dp) {
            RESIGN_POINTS.forEachIndexed { i, (title, brief) ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                Row {
                    Text("—", style = BodySmall.copy(color = EmberHot))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = BodyStrong.copy(fontSize = 13.sp), modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            MarkTag(ClauseMark.New)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(brief, style = BodySmall.copy(color = TextSecondary, lineHeight = 16.sp))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(FIXED_SUMMARY, style = BodyTiny.copy(color = TextDim))
        Spacer(Modifier.height(18.dp))
        PrimaryButton(if (busy) "Se semnează" else "Semnează", onClick = onSign, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            SecondaryButton("Tot contractul", onClick = onReadAll, modifier = Modifier.weight(1f), padV = 12.dp)
            Spacer(Modifier.width(10.dp))
            SecondaryButton("Mai târziu", onClick = onLater, modifier = Modifier.weight(1f), padV = 12.dp, textColor = TextSecondary)
        }
    }
}
