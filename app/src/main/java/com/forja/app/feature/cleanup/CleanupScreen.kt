package com.forja.app.feature.cleanup

import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.forja.app.core.cleanup.Category
import com.forja.app.core.cleanup.CleanupEngine
import com.forja.app.core.cleanup.CleanupScope
import com.forja.app.core.cleanup.DocItem
import com.forja.app.core.cleanup.DocumentOrganizer
import com.forja.app.core.cleanup.MediaItem
import com.forja.app.core.cleanup.OrgItem
import com.forja.app.core.cleanup.OrganizerJobs
import com.forja.app.core.cleanup.OrganizerStatus
import com.forja.app.core.cleanup.SiteHint
import com.forja.app.core.cleanup.ScanProgress
import com.forja.app.core.cleanup.ScopeKind
import com.forja.app.core.cleanup.fmtBytes
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.network.OrganizeVerdictV2
import com.forja.app.core.util.Fmt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RO = Locale("ro")
private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", RO)
private fun fmtDate(ms: Long): String =
    if (ms <= 0) "—" else dateFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * Curățenie de azi — detox digital, v2: alegi scopul, scanarea merge cu pauză și reluare,
 * rezultatele vin grupate pe categorii, tu decizi ce pleacă. Analiza cu model pe serverul FORJA și lucrarea
 * de cont („Și pe site") pornesc implicit după scanare; ambele comutatoare se pot opri, iar atunci spunem clar că
 * analiza rămâne pe telefon. Cu contractul de securitate semnat, site-ul e pornit obligatoriu și în ecran rămâne un
 * singur rând discret „Pe site: N · analizate: M”. Nimic nu se șterge fără dialogul de sistem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val vm: CleanupViewModel = viewModel(viewModelStoreOwner = activity)
    val toast = LocalToast.current
    val state by vm.state.collectAsState()
    val docs by vm.docs.collectAsState()
    val aiOn by vm.aiOn.collectAsState()
    val siteOn by vm.siteOn.collectAsState()
    // Contract semnat: site-ul e pornit obligatoriu; rămâne un singur rând discret „Pe site: N · analizate: M”.
    val contract by vm.contractSigned.collectAsState()
    val siteStatus by vm.siteStatus.collectAsState()
    val pendingTouch by vm.pendingTouch.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { vm.events.collect { toast.show(it) } }

    // Cât timp ecranul e vizibil: mutările aprobate din laptop se cer aici (nu prin notificare) și
    // site-ul e întrebat la 15 s de comenzi noi. „Și pe site” e implicit pornit: după scanare, copiile urcă
    // în cont 24 h; oprit, nu mai urcă nimic în cont (analiza cu model are comutatorul ei, „Sugestii AI”).
    DisposableEffect(Unit) {
        vm.setScreenVisible(true)
        onDispose { vm.setScreenVisible(false) }
    }
    LaunchedEffect(siteOn) {
        while (siteOn) {
            vm.tickSite()
            kotlinx.coroutines.delay(15_000)
        }
    }

    val intentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.onIntentResult(res.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        vm.intentRequests.collect { sender ->
            try { intentLauncher.launch(IntentSenderRequest.Builder(sender).build()) } catch (_: Exception) { vm.onIntentResult(false) }
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionResult() }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onTreePicked(uri)
    }

    var preview by remember { mutableStateOf<MediaItem?>(null) }
    var albumSheet by remember { mutableStateOf(false) }

    // Mascota cu cască: gândește cât scanăm/analizăm, se bucură când a terminat, își cere scuze la eroare.
    val mascotState = mascotStateFor(tab, state, docs)

    Column(
        Modifier.fillMaxSize().topoBackground(decor = false).statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Mascot(state = mascotState, hat = MascotHat.Helmet, size = 72.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                // Ștampila postului deasupra titlului; titlul rămâne numele modulului, cu elipsă la nevoie (mascota ia 72 dp).
                StampLabel("INVENTAR", rotationDeg = -4f)
                Spacer(Modifier.height(6.dp))
                Text("Curățenie de azi", style = TitleModule.copy(fontSize = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "TELEFON UȘOR · MINTE LIMPEDE",
                    style = monoLabel(9, 0.14f).copy(color = Accent2),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            SecondaryButton("Înapoi", onClick = onBack, padV = 8.dp)
        }

        // Taburi
        Row(Modifier.padding(horizontal = 20.dp)) {
            listOf("Poze", "Documente").forEachIndexed { i, label ->
                Box(
                    Modifier
                        .padding(end = 10.dp)
                        .clip(ChipShape)
                        .background(if (i == tab) TabPillActive else Surface2)
                        .pressable({ tab = i })
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(label, style = BodyStrong.copy(fontSize = 13.sp, color = if (i == tab) Accent2 else TextSecondary))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (pendingTouch.isNotEmpty()) {
            PendingTouchCard(items = pendingTouch, busy = (state as? CleanupUiState.Results)?.busy == true, onAllow = { vm.allowPendingMoves() })
        }

        if (tab == 0) {
            when (val s = state) {
                is CleanupUiState.Choose -> {
                    if (!s.hasPermission) {
                        PermissionGate(onAsk = { permLauncher.launch(vm.photoPermissions()) })
                    } else {
                        ScopeChooser(
                            state = s,
                            contract = contract,
                            siteOn = siteOn,
                            loggedIn = vm.loggedIn,
                            modelSends = aiOn && vm.aiAvailable && vm.loggedIn,
                            siteStatus = siteStatus,
                            onSiteOn = { vm.setSiteOn(it) },
                            onCancelSite = { vm.cancelSiteJob() },
                            onScope = { vm.setScope(it) },
                            onPickAlbum = { albumSheet = true },
                            onReset = { vm.resetProgress() },
                            onStart = { vm.startScan() }
                        )
                    }
                }
                is CleanupUiState.Scanning -> ProgressCard(
                    state = s,
                    contract = contract,
                    onPause = { vm.pause() },
                    onResume = { vm.resumeScan() },
                    onCancel = { vm.backToChoose() }
                )
                is CleanupUiState.Results -> ResultsView(
                    vm = vm, state = s, aiOn = aiOn, siteOn = siteOn, contract = contract, siteStatus = siteStatus,
                    mascotState = mascotState,
                    onPreview = { preview = it },
                    onDone = onBack
                )
            }
        } else {
            DocsTab(
                vm = vm, docs = docs, aiOn = aiOn, siteOn = siteOn, contract = contract, siteStatus = siteStatus,
                mascotState = mascotState,
                onPickTree = { try { treeLauncher.launch(null) } catch (_: Exception) { toast.show("Nu pot deschide selectorul de foldere.") } }
            )
        }
    }

    // Sheet: albumele galeriei
    if (albumSheet) {
        val s = state as? CleanupUiState.Choose
        ModalBottomSheet(
            onDismissRequest = { albumSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface1, shape = SheetShape
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).fillMaxHeight(0.85f)) {
                Text("Un album", style = TitleModule.copy(fontSize = 20.sp))
                Spacer(Modifier.height(4.dp))
                Text("Alege albumul pe care îl curățăm azi.", style = BodySmall.copy(color = TextSecondary))
                Spacer(Modifier.height(12.dp))
                val albums = s?.albums ?: emptyList()
                if (s != null && !s.albumsLoaded) {
                    Text("citesc albumele…", style = monoLabel(10, 0.12f).copy(color = TextDim))
                } else if (albums.isEmpty()) {
                    Text("Galeria nu are albume încă.", style = Body)
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(albums, key = { it.bucketId }) { a ->
                        val selected = s?.scope?.kind == ScopeKind.ALBUM && s.scope.bucketId == a.bucketId
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(CardShape)
                                .background(if (selected) TabPillActive else Color.Transparent)
                                .pressable({
                                    if (s != null) vm.setScope(s.scope.copy(kind = ScopeKind.ALBUM, bucketId = a.bucketId, bucketName = a.name))
                                    albumSheet = false
                                })
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(44.dp).clip(ThumbShape).background(Surface2)) {
                                if (a.coverUri != null) AsyncImage(model = a.coverUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, style = BodyStrong.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${a.count} ${if (a.count == 1) "element" else "elemente"} · ${fmtBytes(a.bytes)}", style = BodyTiny.copy(color = TextDim))
                            }
                            if (selected) Icon(Icons.Filled.Check, null, tint = Accent2, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    // Sheet: previzualizare (apăsare lungă pe o poză)
    preview?.let { item ->
        val results = state as? CleanupUiState.Results
        val ai = results?.ai?.suggestions?.get("m:${item.id}")
        val siteHint = results?.site?.get(item.uri.toString())
        ModalBottomSheet(
            onDismissRequest = { preview = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Surface1, shape = SheetShape
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                AsyncImage(
                    model = item.uri, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(CardShape).background(Surface2)
                )
                Spacer(Modifier.height(12.dp))
                Text(item.name, style = BodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val where = item.relativePath.ifBlank { item.bucketName }.trimEnd('/')
                val dims = if (item.width > 0 && item.height > 0) " · ${item.width}×${item.height}" else ""
                Text("${where.ifBlank { "galerie" }} · ${fmtBytes(item.sizeBytes)}$dims", style = BodySmall.copy(color = TextSecondary))
                Text(fmtDate(item.bestTimeMs), style = BodyTiny.copy(color = TextDim))
                if (ai != null) {
                    Spacer(Modifier.height(8.dp))
                    AiVerdictRow(ai, nameOf = { id -> results?.report?.scanned?.firstOrNull { "m:${it.id}" == id }?.name }, style = BodySmall.copy(color = TextSecondary))
                }
                if (siteHint != null) {
                    Spacer(Modifier.height(8.dp))
                    SiteHintLine(siteHint)
                }
                Spacer(Modifier.height(16.dp))
                Row {
                    SecondaryButton("Deschide în Galerie", onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime.ifBlank { "image/*" })
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        } catch (_: Exception) { toast.show("Nicio aplicație nu poate deschide poza.") }
                    }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("Șterge", onClick = { preview = null; vm.deleteItems(listOf(item)) }, modifier = Modifier.weight(1f), textColor = Error)
                }
            }
        }
    }
}

// ─────────────────────────── Mascota și eticheta AI a unui element ───────────────────────────

/** Starea mascotei din antet: gândește cât scanăm/analizăm, fericită când analiza s-a încheiat, tristă la eroare. */
private fun mascotStateFor(tab: Int, state: CleanupUiState, docs: DocsUiState): MascotState {
    val ai = if (tab == 0) (state as? CleanupUiState.Results)?.ai else docs.ai
    return when {
        tab == 0 && state is CleanupUiState.Scanning -> if (state.paused) MascotState.Idle else MascotState.Thinking
        tab == 1 && docs.loading -> MascotState.Thinking
        ai == null -> MascotState.Idle
        ai.loading -> MascotState.Thinking
        ai.failed -> MascotState.Sorry
        ai.requested -> MascotState.Happy
        else -> MascotState.Idle
    }
}

/** Replica mascotei cât timp analiza merge sau după ce s-a încheiat; null când nu are nimic de spus. */
private fun mascotLineFor(ai: AiPanel, what: String, whatOne: String): String? = when {
    ai.loading -> if (what == "poze") "Mă uit la fiecare poză." else "Citesc fiecare fișier."
    ai.failed && ai.done == 0 -> "Nu am reușit să văd ${if (what == "poze") "pozele" else "fișierele"}. Reiau când vrei."
    ai.failed -> "Am văzut ${ai.done} din ${ai.total}. Restul mai încerc la „Reia analiza”."
    ai.requested && ai.deleteCount > 0 -> "Am o propunere pentru ${ai.deleteCount} ${if (ai.deleteCount == 1) whatOne else what}."
    ai.requested && ai.moveFolders.isNotEmpty() -> "Am dosare propuse pentru ${ai.moveFolders.values.sumOf { it.size }} ${if (ai.moveFolders.values.sumOf { it.size } == 1) whatOne else what}."
    ai.requested && ai.done > 0 -> "Nimic de aruncat din ce am văzut."
    else -> null
}

/**
 * Eticheta de o linie sub element: „<dosar> · <motiv>” (din `dosar`/`folder`, apoi `motiv`/`reason`/`rezumat`),
 * plus „duplicat al <nume>” când `duplicatDe` există. Null când modelul n-a spus nimic (element memorat fără verdict).
 */
private fun aiLabel(v: OrganizeVerdictV2, nameOf: (String) -> String?): String? {
    val parts = ArrayList<String>(3)
    v.targetFolder?.let { parts += it }
    val why = v.displayReason.ifBlank { v.rezumat.trim() }
    if (why.isNotBlank()) parts += why
    v.duplicatDe?.trim()?.takeIf { it.isNotBlank() && it != v.id }?.let { other -> parts += "duplicat al ${nameOf(other) ?: "altui element"}" }
    return parts.joinToString(" · ").ifBlank { null }
}

/** Chip mic „AI: șterge · <încredere>” — doar o recomandare; ștergerea trece prin dialogul de sistem. */
@Composable
private fun AiDeleteChip(v: OrganizeVerdictV2, modifier: Modifier = Modifier) {
    val conf = v.displayConfidence
    Box(modifier.clip(ChipShape).background(Error.copy(alpha = 0.92f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
        Text(
            if (conf.isNotBlank()) "AI: șterge · $conf" else "AI: șterge",
            style = monoLabel(7, 0.06f).copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Verdictul unui element pe un rând: chip-ul de ștergere (dacă e cazul) + eticheta de o linie. */
@Composable
private fun AiVerdictRow(v: OrganizeVerdictV2, nameOf: (String) -> String?, style: androidx.compose.ui.text.TextStyle = BodyTiny.copy(color = TextSecondary), maxLines: Int = 2) {
    val label = aiLabel(v, nameOf)
    if (!v.deleteRecommended && label == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (v.deleteRecommended) { AiDeleteChip(v); Spacer(Modifier.width(6.dp)) } else { SuggestionDot(v); Spacer(Modifier.width(6.dp)) }
        Text(label ?: "", style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

// ─────────────────────────── Poze: permisiune, scop, progres ───────────────────────────

@Composable
private fun PermissionGate(onAsk: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Ca să facem curat, FORJA are nevoie de acces la galerie.", style = Body, modifier = Modifier.padding(bottom = 12.dp), textAlign = TextAlign.Center)
        Text(
            "În timpul scanării pozele rămân pe telefon. După, miniaturile pleacă la analiza cu model și copiile în cont, dacă lași cele două comutatoare pornite. Pe Android 14 poți alege și doar câteva poze.",
            style = BodyTiny.copy(color = TextDim), modifier = Modifier.padding(bottom = 12.dp), textAlign = TextAlign.Center
        )
        SecondaryButton("Dă accesul", onClick = onAsk)
    }
}

@Composable
private fun ScopeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(ChipShape)
            .background(if (selected) TabPillActive else Surface2)
            .border(1.dp, if (selected) Accent2.copy(alpha = 0.5f) else StrokeCard, ChipShape)
            .pressable(onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, style = BodyStrong.copy(fontSize = 12.sp, color = if (selected) Accent2 else TextSecondary))
    }
}

// ─────────────────────────── Site: linia de stare, comutatorul, atingerea cerută ───────────────────────────

/** Linia de stare a lucrării curente de pe site (doar când comutatorul e pornit sau lucrarea încă trăiește). */
@Composable
private fun SiteStatusLine(status: OrganizerStatus?, onCancel: (() -> Unit)? = null) {
    if (status == null) return
    val active = status.state !in setOf("cancelled", "complete", "needs_review")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "SITE · ${if (status.origin == "site") "DIN LAPTOP · " else ""}${status.label.uppercase()}",
                style = monoLabel(9, 0.12f).copy(color = if (active) Accent2 else TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (status.message.isNotBlank()) {
                Text(status.message, style = BodyTiny.copy(color = TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (active && onCancel != null) {
            Spacer(Modifier.width(8.dp))
            MonoButton("oprește", onClick = onCancel)
        }
    }
}

/** Contract semnat: un singur rând discret în locul comutatorului, al liniei „SITE · …” și al numărătorilor repetate. */
@Composable
private fun SiteLine(status: OrganizerStatus?, modifier: Modifier = Modifier) {
    val uploaded = status?.uploaded ?: 0
    val analyzed = status?.analyzed ?: 0
    Text("Pe site: $uploaded · analizate: $analyzed", style = BodyTiny.copy(color = TextDim), modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Rândul cu comutatorul „Și pe site (copii 24 h)" — implicit pornit și parte din pornirea curățeniei.
 * Rămâne vizibil ca informație; oprit sau fără cont, spunem exact ce mai pleacă: „Sugestii AI" e alt comutator
 * (`modelSends` = e pornit și poate trimite), deci „analiza rămâne pe telefon" doar când nici acela nu trimite.
 */
@Composable
private fun SiteSwitchRow(siteOn: Boolean, loggedIn: Boolean, modelSends: Boolean, onSiteOn: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Și pe site (copii 24 h)", style = BodyStrong.copy(fontSize = 13.sp))
            Text(
                when {
                    !siteOn && modelSends -> "Oprit: nimic nu urcă în cont. Miniaturile și PDF-urile pleacă totuși la analiza cu model, cât timp „Sugestii AI” e pornit."
                    !siteOn -> "Oprit: analiza rămâne doar pe telefon. Nimic nu pleacă în cont."
                    !loggedIn -> "Nu ești în cont: nimic nu urcă în cont și analiza rămâne pe telefon. Intră în cont ca lucrarea să pornească odată cu curățenia."
                    else -> "Copiile analizate se urcă în contul tău 24 h — așa le poți organiza și de pe laptop. Pornește odată cu curățenia."
                },
                style = BodyTiny.copy(color = TextDim)
            )
        }
        ForjaSwitch(siteOn, onSiteOn)
    }
}

/** Mutări aprobate din laptop care așteaptă acordul Android — o atingere, apoi FORJA le face. */
@Composable
private fun PendingTouchCard(items: List<OrgItem>, busy: Boolean, onAllow: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        ForjaCard(Modifier.fillMaxWidth(), stroke = Accent2.copy(alpha = 0.5f)) {
            Text("Organizarea din laptop așteaptă o atingere", style = BodyStrong.copy(fontSize = 15.sp))
            Spacer(Modifier.height(4.dp))
            Text(
                "${items.size} ${if (items.size == 1) "poză aprobată" else "poze aprobate"} din panoul online. Android cere acordul tău o singură dată; apoi le mutăm și site-ul primește confirmarea.",
                style = BodySmall.copy(color = TextSecondary)
            )
            val names = items.take(3).joinToString(" · ") { it.name }
            if (names.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(names, style = BodyTiny.copy(color = TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Permite mutarea", onClick = onAllow, modifier = Modifier.fillMaxWidth(), small = true, enabled = !busy)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Ce știe site-ul despre un element: copie, propunere (etichetată „Site”), stare. */
@Composable
private fun SiteHintLine(h: SiteHint) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SourceBadge("Site", Accent2)
        Spacer(Modifier.width(8.dp))
        val a = h.analysis
        Text(
            when {
                a != null && a.destination.isNotBlank() -> "mută în ${a.destination}" + (if (a.reason.isNotBlank()) " — ${a.reason}" else "") + (if (a.confidence.isNotBlank()) " (${a.confidence})" else "")
                h.state == "moved" -> "mutată · confirmată pe site"
                h.state == "needs_permission" -> "așteaptă atingerea ta"
                h.error.isNotBlank() -> h.error
                h.uploaded -> "copie pe site 24 h · fără propunere încă"
                else -> "în inventarul de pe site"
            },
            style = BodySmall.copy(color = TextSecondary), maxLines = 3, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScopeChooser(
    state: CleanupUiState.Choose,
    contract: Boolean,
    siteOn: Boolean,
    loggedIn: Boolean,
    modelSends: Boolean,
    siteStatus: OrganizerStatus?,
    onSiteOn: (Boolean) -> Unit,
    onCancelSite: () -> Unit,
    onScope: (CleanupScope) -> Unit,
    onPickAlbum: () -> Unit,
    onReset: () -> Unit,
    onStart: () -> Unit
) {
    val scope = state.scope
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        ForjaCard(Modifier.fillMaxWidth()) {
            Text("Ce curățăm azi?", style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(4.dp))
            Text("Alegi tu cât. Scanarea merge local, cu pauză și reluare.", style = BodySmall.copy(color = TextSecondary))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScopeChip("Toată galeria", scope.kind == ScopeKind.WHOLE_GALLERY) { onScope(scope.copy(kind = ScopeKind.WHOLE_GALLERY)) }
                ScopeChip("Un album", scope.kind == ScopeKind.ALBUM) {
                    if (scope.kind != ScopeKind.ALBUM || scope.bucketId == null) onPickAlbum()
                    else onScope(scope.copy(kind = ScopeKind.ALBUM))
                }
                ScopeChip("Următoarele", scope.kind == ScopeKind.NEXT_BATCH) { onScope(scope.copy(kind = ScopeKind.NEXT_BATCH)) }
            }
            if (scope.kind == ScopeKind.NEXT_BATCH) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(50, 100, 300).forEach { n ->
                        ScopeChip("$n", scope.batchSize == n) { onScope(scope.copy(batchSize = n)) }
                    }
                }
            }
            if (scope.kind == ScopeKind.ALBUM) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (scope.bucketName.isNullOrBlank()) "Niciun album ales încă." else "Album: ${scope.bucketName}",
                        style = BodySmall.copy(color = TextSecondary), modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("alege", style = BodySmall.copy(color = Accent2), modifier = Modifier.pressable(onPickAlbum))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include videoclipurile", style = BodyStrong.copy(fontSize = 13.sp))
                    Text("Doar duplicate și fișiere mari — nu le analizăm cadru cu cadru.", style = BodyTiny.copy(color = TextDim))
                }
                ForjaSwitch(scope.includeVideos) { onScope(scope.copy(includeVideos = it)) }
            }
            if (contract) {
                if (siteStatus != null) {
                    Spacer(Modifier.height(10.dp))
                    SiteLine(siteStatus)
                }
            } else {
                Spacer(Modifier.height(10.dp))
                SiteSwitchRow(siteOn, loggedIn, modelSends, onSiteOn)
                if (siteOn || siteStatus != null) {
                    Spacer(Modifier.height(8.dp))
                    SiteStatusLine(siteStatus, onCancel = onCancelSite)
                }
            }
            val resume = state.resume
            if (resume != null && resume.processed > 0) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Ai rămas la ${resume.processed} ${if (scope.includeVideos) "elemente" else "poze"} · Continuă",
                        style = BodySmall.copy(color = Accent2), modifier = Modifier.weight(1f)
                    )
                    MonoButton("de la început", onClick = onReset)
                }
            }
            Spacer(Modifier.height(14.dp))
            val canStart = scope.kind != ScopeKind.ALBUM || scope.bucketId != null
            PrimaryButton(
                if (resume != null && resume.processed > 0) "Continuă curățenia" else "Începe curățenia",
                onClick = onStart, modifier = Modifier.fillMaxWidth(), enabled = canStart
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "„Ordinea din jur începe cu ordinea dinăuntru.”",
            style = BodyTiny.copy(color = TextDim), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProgressCard(
    state: CleanupUiState.Scanning,
    contract: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val p = state.progress
    val (done, total, label, thumb) = when (p) {
        null -> Quad(0, 0, "pornesc…", null)
        is ScanProgress.Inventory -> Quad(0, 0, "inventariez · ${p.count}", null)
        is ScanProgress.Hashing -> Quad(p.done, p.total, "caut duplicate · ${p.done}/${p.total}", p.currentUri)
        is ScanProgress.Visual -> Quad(p.done, p.total, "verific claritatea · ${p.done}/${p.total}", p.currentUri)
        is ScanProgress.Done -> Quad(1, 1, "gata", null)
    }
    val fraction = if (total > 0) done.toFloat() / total else 0f
    val says = when {
        state.paused -> "Pauză. Progresul e salvat."
        p == null || p is ScanProgress.Inventory -> "Fac inventarul."
        p is ScanProgress.Hashing -> "Caut copiile identice."
        else -> "Mă uit la fiecare poză."
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        MascotSays(says, hat = MascotHat.Helmet, state = if (state.paused) MascotState.Idle else MascotState.Talking)
        Spacer(Modifier.height(12.dp))
        ForjaCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.scope.title.uppercase(), style = monoLabel(9, 0.14f).copy(color = TextDim))
                Spacer(Modifier.height(12.dp))
                ProgressRing(progress = fraction, ringSize = 120.dp, animated = false) {
                    if (total > 0) Text("${(fraction * 100).toInt()}%", style = heroNumeral(28))
                    else Text(if (p is ScanProgress.Inventory) "${p.count}" else "…", style = heroNumeral(28))
                }
                Spacer(Modifier.height(12.dp))
                Text(if (state.paused) "pauză · progresul e salvat" else label, style = monoLabel(10, 0.12f).copy(color = if (state.paused) TextDim else Accent2))
                if (thumb != null && !state.paused) {
                    Spacer(Modifier.height(12.dp))
                    AsyncImage(
                        model = thumb, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(ThumbShape).background(Surface2)
                    )
                }
                val resume = state.resume
                if (state.paused && resume != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Ai verificat ${resume.processed} până acum. Poți închide aplicația liniștit.", style = BodyTiny.copy(color = TextDim), textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                Row {
                    if (state.paused) PrimaryButton("Continuă", onClick = onResume, modifier = Modifier.weight(1f), small = true)
                    else SecondaryButton("Pauză", onClick = onPause, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    SecondaryButton("Renunță", onClick = onCancel, modifier = Modifier.weight(1f), textColor = TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (contract) "Scanarea se face pe telefon. După, miniaturile pleacă automat la analiza cu model."
            else "Scanarea se face pe telefon. După, miniaturile pleacă automat la analiza cu model („Sugestii AI”) și copiile în cont („Și pe site”) — ambele se pot opri.",
            style = BodyTiny.copy(color = TextDim), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
        )
    }
}

private data class Quad(val done: Int, val total: Int, val label: String, val thumb: android.net.Uri?)

// ─────────────────────────── Poze: rezultate ───────────────────────────

/** Un tile din grilă: fie o poză de aruncat, fie „păstratul" unui grup. */
private data class Tile(val item: MediaItem, val reason: String?, val keeper: Boolean)

@Composable
private fun ResultsView(
    vm: CleanupViewModel,
    state: CleanupUiState.Results,
    aiOn: Boolean,
    siteOn: Boolean,
    contract: Boolean,
    siteStatus: OrganizerStatus?,
    mascotState: MascotState,
    onPreview: (MediaItem) -> Unit,
    onDone: () -> Unit
) {
    val report = state.report
    val flagged = report.flaggedItems
    val flaggedBytes = flagged.sumOf { it.sizeBytes }
    // Poze fără categorie locală, dar recomandate la ștergere de model: secțiunea „AI: de aruncat” (doar bifate).
    val aiExtras = remember(report, state.ai.suggestions) { vm.aiDeleteExtras(state) }
    val selectedItems = (flagged + aiExtras).filter { it.id in state.selected }
    val selectedBytes = selectedItems.sumOf { it.sizeBytes }
    val nameOf: (String) -> String = { uri -> report.scanned.firstOrNull { it.uri.toString() == uri }?.name ?: "poză" }
    // „copie a <nume>": id-ul din `duplicatDe` („m:<id>") → numele pozei din raport, calculat o dată pe raport.
    val aiNames: Map<String, String> = remember(report) { report.scanned.associate { "m:${it.id}" to it.name } }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)
        ) {
            item(key = "summary") {
                val says = if (vm.aiAvailable && aiOn) mascotLineFor(state.ai, "poze", "poză") else null
                if (says != null) {
                    MascotSays(says, hat = MascotHat.Helmet, state = if (state.ai.loading) MascotState.Talking else mascotState)
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    "${report.scanned.size} verificate · ${flagged.size + aiExtras.size} de aruncat · ${fmtBytes(flaggedBytes + aiExtras.sumOf { it.sizeBytes })}" +
                        (if (report.warnings.isNotEmpty()) " · ${report.warnings.size} necitite" else ""),
                    style = BodyTiny.copy(color = TextSecondary)
                )
                if (state.jobId != null && siteStatus != null && siteStatus.jobId == state.jobId) {
                    Spacer(Modifier.height(6.dp))
                    if (contract) SiteLine(siteStatus) else SiteStatusLine(siteStatus, onCancel = { vm.cancelSiteJob() })
                }
                Spacer(Modifier.height(10.dp))
            }
            if (report.isEmpty && aiExtras.isEmpty()) {
                item(key = "empty") {
                    ForjaCard(Modifier.fillMaxWidth()) {
                        Text("Nimic de aruncat aici. Telefon curat.", style = BodyStrong.copy(fontSize = 15.sp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                state.ai.loading -> "Modelul se uită încă la poze. Dacă are o propunere, apare aici."
                                report.hasMore -> "Mai sunt poze de verificat — mergem mai departe când vrei."
                                else -> "Ai trecut prin tot ce era de trecut. Ține-o așa."
                            },
                            style = BodySmall.copy(color = TextSecondary)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            for (cat in Category.values()) {
                val items = vm.categoryItems(report, cat)
                if (items.isEmpty()) continue
                val tiles: List<Tile> = when (cat) {
                    Category.DUPLICATE -> report.duplicates.flatMap { g -> listOf(Tile(g.keeper, null, true)) + g.copies.map { Tile(it, "duplicat identic", false) } }
                    Category.SIMILAR -> report.similar.flatMap { g -> listOf(Tile(g.keeper, null, true)) + g.others.map { Tile(it, "aproape identică", false) } }
                    Category.SCREENSHOT -> items.map { Tile(it, "captură", false) }
                    Category.BLURRY -> report.blurry.map { Tile(it.item, "neclară · ${it.score.toInt()}×", false) }
                    Category.TINY -> items.map { Tile(it, if (it.sizeBytes in 1..CleanupEngine.TINY_BYTES) "mică" else "rezoluție mică", false) }
                    Category.LARGE -> items.map { Tile(it, fmtBytes(it.sizeBytes), false) }
                }
                categorySection(vm, state, cat, items, tiles, onPreview, aiNames)
            }
            if (aiExtras.isNotEmpty()) {
                aiExtrasSection(vm, state, aiExtras, onPreview, aiNames)
            }
            if (vm.aiAvailable || (siteOn && state.jobId != null)) {
                item(key = "ai") {
                    AiPanelCard(
                        ai = state.ai, aiOn = aiOn, aiAvailable = vm.aiAvailable,
                        subject = "miniaturi ≤ 512 px",
                        onToggle = { vm.setAiOn(it) },
                        onRetry = { vm.retryAi() },
                        onApplyFolder = { vm.applyAiFolder(it) },
                        siteOn = siteOn, contract = contract, siteJob = state.jobId != null,
                        siteRoot = OrganizerJobs.PHOTO_DESTINATION, site = state.site, nameOf = nameOf,
                        onApplySiteFolder = { vm.applySiteFolder(it) }
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (report.warnings.isNotEmpty()) {
                item(key = "warnings") {
                    Text("Necitite: ${report.warnings.take(3).joinToString(" · ")}", style = BodyTiny.copy(color = TextDim))
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // Footer lipit: acțiuni globale
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            if (state.busy) {
                Text("lucrez…", style = monoLabel(10, 0.12f).copy(color = Accent2), modifier = Modifier.padding(bottom = 8.dp))
            }
            if (selectedItems.isNotEmpty()) {
                PrimaryButton(
                    "Șterge ${selectedItems.size} ${if (selectedItems.size == 1) "poză" else "poze"} · ${fmtBytes(selectedBytes)}",
                    onClick = { vm.deleteSelected() }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy
                )
                Spacer(Modifier.height(8.dp))
            }
            if (report.hasMore) {
                PrimaryButton(
                    "Următoarele ${state.scope.batchSize}", onClick = { vm.nextBatch() },
                    modifier = Modifier.fillMaxWidth(), small = true, enabled = !state.busy
                )
                Spacer(Modifier.height(8.dp))
            }
            Row {
                MonoButton("Gata pe azi", onClick = onDone, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                MonoButton("Alt scop", onClick = { vm.backToChoose() }, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun LazyListScope.categorySection(
    vm: CleanupViewModel,
    state: CleanupUiState.Results,
    cat: Category,
    items: List<MediaItem>,
    tiles: List<Tile>,
    onPreview: (MediaItem) -> Unit,
    aiNames: Map<String, String>
) {
    val ids = items.map { it.id }
    val bytes = items.sumOf { it.sizeBytes }
    val allSelected = ids.all { it in state.selected }
    val targets = items.filter { it.id in state.selected }.ifEmpty { items }
    val targetBytes = targets.sumOf { it.sizeBytes }
    item(key = "h-${cat.name}") {
        SectionLabel("${cat.label} · ${items.size} · ${fmtBytes(bytes)}", color = Accent2)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (allSelected) "Deselectează" else "Selectează tot", onClick = { vm.selectAll(ids, !allSelected) }, padV = 8.dp)
            if (vm.engine.canMove) SecondaryButton("Mută în dosar", onClick = { vm.moveCategory(cat) }, padV = 8.dp)
            SecondaryButton("Șterge ${targets.size} · ${fmtBytes(targetBytes)}", onClick = { vm.deleteCategory(cat) }, padV = 8.dp, textColor = Error)
        }
        if (cat == Category.DUPLICATE || cat == Category.SIMILAR) {
            Spacer(Modifier.height(4.dp))
            Text("Primul din fiecare grup e păstrat; copiile sunt bifate.", style = BodyTiny.copy(color = TextDim))
        }
        Spacer(Modifier.height(8.dp))
    }
    tileRows(cat.name, tiles, state, vm, onPreview, aiNames)
    item(key = "sp-${cat.name}") { Spacer(Modifier.height(14.dp)) }
}

/** Grila de 3 pe rând, cu eticheta AI de o linie sub fiecare poză. */
private fun LazyListScope.tileRows(
    section: String,
    tiles: List<Tile>,
    state: CleanupUiState.Results,
    vm: CleanupViewModel,
    onPreview: (MediaItem) -> Unit,
    aiNames: Map<String, String>
) {
    val rows = tiles.chunked(3)
    items(rows.size, key = { i -> "r-$section-${rows[i].first().item.id}" }) { i ->
        val row = rows[i]
        Row(Modifier.fillMaxWidth()) {
            row.forEach { t ->
                MediaTile(
                    item = t.item, selected = t.item.id in state.selected, reason = t.reason, keeper = t.keeper,
                    badge = state.ai.suggestions["m:${t.item.id}"],
                    siteBadge = state.site[t.item.uri.toString()]?.let { it.analysis != null || it.state == "moved" } == true,
                    onToggle = { vm.toggleSelect(t.item.id) },
                    onLong = { onPreview(t.item) },
                    nameOf = { aiNames[it] },
                    modifier = Modifier.weight(1f)
                )
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** „AI: de aruncat” — poze fără categorie locală, recomandate la ștergere de model. Bifate, nu șterse: tu confirmi. */
private fun LazyListScope.aiExtrasSection(
    vm: CleanupViewModel,
    state: CleanupUiState.Results,
    items: List<MediaItem>,
    onPreview: (MediaItem) -> Unit,
    aiNames: Map<String, String>
) {
    val ids = items.map { it.id }
    val allSelected = ids.all { it in state.selected }
    val targets = items.filter { it.id in state.selected }.ifEmpty { items }
    item(key = "h-AI") {
        SectionLabel("AI: de aruncat · ${items.size} · ${fmtBytes(items.sumOf { it.sizeBytes })}", color = Accent2)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (allSelected) "Deselectează" else "Selectează tot", onClick = { vm.selectAll(ids, !allSelected) }, padV = 8.dp)
            SecondaryButton("Șterge ${targets.size} · ${fmtBytes(targets.sumOf { it.sizeBytes })}", onClick = { vm.deleteItems(targets) }, padV = 8.dp, textColor = Error)
        }
        Spacer(Modifier.height(4.dp))
        Text("Recomandarea modelului, cu motiv. Verifică și confirmă tu.", style = BodyTiny.copy(color = TextDim))
        Spacer(Modifier.height(8.dp))
    }
    tileRows("AI", items.map { Tile(it, null, false) }, state, vm, onPreview, aiNames)
    item(key = "sp-AI") { Spacer(Modifier.height(14.dp)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    item: MediaItem,
    selected: Boolean,
    reason: String?,
    keeper: Boolean,
    badge: OrganizeVerdictV2?,
    onToggle: () -> Unit,
    onLong: () -> Unit,
    modifier: Modifier = Modifier,
    siteBadge: Boolean = false,
    nameOf: (String) -> String? = { null }
) {
    Column(modifier.padding(3.dp)) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(ThumbShape)
            .background(Surface2)
            .then(if (keeper) Modifier.border(1.5.dp, Positive.copy(alpha = 0.8f), ThumbShape) else Modifier)
            .combinedClickable(onClick = onToggle, onLongClick = onLong)
    ) {
        AsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (keeper) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp)
                    .clip(ChipShape).background(Color(0xCC0A0A0B)).padding(horizontal = 5.dp, vertical = 2.dp)
            ) { Text("păstrat", style = monoLabel(7, 0.06f).copy(color = Positive)) }
        } else if (reason != null) {
            Box(
                Modifier.align(Alignment.TopStart).padding(4.dp)
                    .clip(ChipShape).background(Color(0xCC0A0A0B)).padding(horizontal = 5.dp, vertical = 2.dp)
            ) { Text(reason, style = monoLabel(7, 0.06f).copy(color = Accent2), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(
            fmtBytes(item.sizeBytes), style = monoLabel(7, 0.02f).copy(color = Color.White),
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
        )
        if (item.isVideo) {
            Text("VIDEO", style = monoLabel(7, 0.10f).copy(color = Color.White), modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 26.dp))
        }
        if (badge != null && !keeper) {
            Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                if (badge.deleteRecommended) AiDeleteChip(badge, Modifier.widthIn(max = 96.dp)) else SuggestionDot(badge)
            }
        }
        if (siteBadge) {
            Box(
                Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 22.dp)
                    .clip(ChipShape).background(Color(0xCC0A0A0B)).padding(horizontal = 4.dp, vertical = 1.dp)
            ) { Text("SITE", style = monoLabel(6, 0.10f).copy(color = Accent2)) }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                .background(if (selected) Error else Color(0x99000000))
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) { if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
    // Eticheta AI de o linie: „<dosar> · <motiv>” / „duplicat al …”; rândul rămâne rezervat ca grila să stea aliniată.
    val label = badge?.let { aiLabel(it, nameOf) }
    Text(
        label ?: " ",
        style = BodyTiny.copy(fontSize = 9.sp, lineHeight = 12.sp, color = if (badge?.deleteRecommended == true) Error else TextSecondary),
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
    )
    }
}

/** Punctul verdictului AI: roșu = ștergere recomandată (doar bifată), dosar = are un dosar propus, verde = păstrează. */
@Composable
private fun SuggestionDot(s: OrganizeVerdictV2) {
    when {
        s.deleteRecommended -> Box(Modifier.size(10.dp).clip(CircleShape).background(Error).border(1.dp, Color(0x99000000), CircleShape))
        s.targetFolder != null -> Box(
            Modifier.size(18.dp).clip(CircleShape).background(Color(0xCC0A0A0B)), contentAlignment = Alignment.Center
        ) { Icon(Icons.Outlined.FolderOpen, null, tint = Accent2, modifier = Modifier.size(12.dp)) }
        else -> Box(Modifier.size(10.dp).clip(CircleShape).background(Positive).border(1.dp, Color(0x99000000), CircleShape))
    }
}

// ─────────────────────────── Panoul AI (poze și documente) ───────────────────────────

/**
 * Panoul AI: analiza cu model pe serverul FORJA (/v1/organize v2: miniaturi, PDF-uri, fragmente) pornește singură
 * după scanare, pe tot ce s-a scanat; aici rămân comutatorul, linia onestă de progres („Analizez 48 din 210…”),
 * „Reia analiza” când ceva a rămas neanalizat și butoanele „Mută N în <dosar>”. Propunerile site-ului (copiile din
 * 24 h) apar dedesubt, etichetate „Site”.
 */
@Composable
private fun AiPanelCard(
    ai: AiPanel,
    aiOn: Boolean,
    aiAvailable: Boolean,
    subject: String,
    onToggle: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onApplyFolder: (String) -> Unit,
    siteOn: Boolean = false,
    contract: Boolean = false,
    siteJob: Boolean = false,
    siteRoot: String = "",
    site: Map<String, SiteHint> = emptyMap(),
    nameOf: (String) -> String = { it },
    onApplySiteFolder: (String) -> Unit = {}
) {
    ForjaCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Sugestii AI", style = BodyStrong.copy(fontSize = 15.sp))
                Text(
                    if (aiOn) "Analiză cu model pe serverul FORJA, automată după scanare, pe tot ce s-a scanat. Pleacă doar $subject. Modelul propune, tu decizi — nimic nu se șterge singur."
                    else "Oprit: rămân verdictele telefonului. Nimic nu pleacă la analiză.",
                    style = BodyTiny.copy(color = TextSecondary)
                )
            }
            if (aiAvailable) ForjaSwitch(aiOn, onToggle)
        }
        if (aiOn && aiAvailable) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge("FORJA AI")
                if (ai.loading) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(color = Accent2, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
                if (ai.status.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ai.status,
                        style = BodyTiny.copy(color = if (ai.failed) Error else if (ai.loading) Accent2 else TextSecondary),
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                }
            }
            if (ai.failed || ai.unfinished) {
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Reia analiza", onClick = onRetry, modifier = Modifier.fillMaxWidth(), padV = 10.dp)
            }
            if (ai.requested) {
                if (ai.deleteCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${ai.deleteCount} ${if (ai.deleteCount == 1) "element bifat" else "elemente bifate"} la recomandarea modelului, cu motiv. Verifică și confirmă tu.",
                        style = BodyTiny.copy(color = TextDim)
                    )
                }
                val folders = ai.moveFolders
                if (folders.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    folders.entries.sortedByDescending { it.value.size }.forEach { (folder, list) ->
                        PrimaryButton(
                            "Mută ${list.size} în $folder", onClick = { onApplyFolder(folder) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), small = true
                        )
                    }
                }
                if (ai.summary.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(ai.summary, style = BodySmall.copy(color = TextSecondary), maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (ai.provider.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("ANALIZĂ CU MODEL · ${ai.provider.uppercase()}", style = monoLabel(8, 0.12f).copy(color = TextDim2))
                }
            }
        }
        if (siteOn && siteJob) {
            val siteFolders = siteFolders(site, siteRoot)
            if (!contract) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge("Site", Accent2)
                    Spacer(Modifier.width(8.dp))
                    val uploaded = site.values.count { it.uploaded }
                    val analyzed = site.values.count { it.analysis != null }
                    Text("Pe site: $uploaded · analizate: $analyzed", style = BodyTiny.copy(color = TextSecondary), modifier = Modifier.weight(1f))
                }
            }
            if (siteFolders.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                siteFolders.entries.sortedByDescending { it.value.size }.take(6).forEach { (folder, list) ->
                    PrimaryButton(
                        "Mută ${list.size} în $folder (site)", onClick = { onApplySiteFolder(folder) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), small = true
                    )
                }
                site.entries.filter { it.value.analysis != null }.take(6).forEach { (uri, h) ->
                    val a = h.analysis!!
                    Text(
                        "${nameOf(uri)} → ${a.destination}" + (if (a.reason.isNotBlank()) " — ${a.reason}" else ""),
                        style = BodyTiny.copy(color = TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ─────────────────────────── Documente ───────────────────────────

private data class DocRowModel(val item: DocItem, val reason: String?, val keeper: Boolean)

@Composable
private fun DocsTab(
    vm: CleanupViewModel,
    docs: DocsUiState,
    aiOn: Boolean,
    siteOn: Boolean,
    contract: Boolean,
    siteStatus: OrganizerStatus?,
    mascotState: MascotState,
    onPickTree: () -> Unit
) {
    val tree = docs.tree
    if (tree == null) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Alege un folder cu documente (ex: Download).", style = Body, modifier = Modifier.padding(bottom = 6.dp), textAlign = TextAlign.Center)
            Text(
                if (contract) "Android nu ne lasă la toate fișierele fără riscuri — deci alegi tu folderul. Îl ținem minte. PDF-urile ≤ 4 MB și fragmente din fișierele text pleacă automat la analiza cu model."
                else "Android nu ne lasă la toate fișierele fără riscuri — deci alegi tu folderul. Îl ținem minte. PDF-urile ≤ 4 MB și fragmente din fișierele text pleacă automat la analiza cu model, copiile în cont — cât timp lași comutatoarele pornite.",
                style = BodyTiny.copy(color = TextDim), modifier = Modifier.padding(bottom = 14.dp), textAlign = TextAlign.Center
            )
            SecondaryButton("Alege folderul", onClick = onPickTree)
        }
        return
    }
    val report = docs.report
    // „copie a <nume>": id-ul din `duplicatDe` („d:<sha>") → numele fișierului, calculat o dată pe raport.
    val docNames: Map<String, String> = remember(report) { report?.items?.associate { vm.docAiId(it) to it.name } ?: emptyMap() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "📁 ${docs.treeName} · ${report?.items?.size ?: 0} fișiere",
                style = BodySmall.copy(color = TextSecondary), modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text("rescanează", style = BodySmall.copy(color = TextDim), modifier = Modifier.pressable({ vm.rescanDocs() }))
            Spacer(Modifier.width(12.dp))
            Text("alt folder", style = BodySmall.copy(color = Accent2), modifier = Modifier.pressable(onPickTree))
        }
        Spacer(Modifier.height(8.dp))
        if (docs.jobId != null && siteStatus != null && siteStatus.jobId == docs.jobId) {
            Box(Modifier.padding(horizontal = 20.dp)) {
                if (contract) SiteLine(siteStatus) else SiteStatusLine(siteStatus, onCancel = { vm.cancelSiteJob() })
            }
            Spacer(Modifier.height(8.dp))
        }
        if (docs.loading) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Accent2, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("citesc folderul…", style = monoLabel(10, 0.12f).copy(color = Accent2))
            }
            Spacer(Modifier.height(8.dp))
        }
        val aiExtras = remember(report, docs.ai.suggestions) { vm.aiDocDeleteExtras(docs) }
        val selectedDocs = report?.items?.filter { it.key in docs.selected } ?: emptyList()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            if (report != null) {
                val says = if (docs.loading) "Fac inventarul." else if (vm.aiAvailable && aiOn) mascotLineFor(docs.ai, "fișiere", "fișier") else null
                if (says != null) {
                    item(key = "says") {
                        MascotSays(says, hat = MascotHat.Helmet, state = if (docs.loading || docs.ai.loading) MascotState.Talking else mascotState)
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (report.isEmpty && aiExtras.isEmpty() && !docs.loading) {
                    item(key = "empty") {
                        ForjaCard(Modifier.fillMaxWidth()) {
                            Text("Nimic de aruncat aici. Folder curat.", style = BodyStrong.copy(fontSize = 15.sp))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (docs.ai.loading) "Modelul citește încă fișierele. Dosarele propuse apar mai jos."
                                else "Dosarele propuse de model, dacă sunt, apar mai jos.",
                                style = BodySmall.copy(color = TextSecondary)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
                val dupRows = report.duplicates.flatMap { g -> listOf(DocRowModel(g.keeper, null, true)) + g.copies.map { DocRowModel(it, "duplicat identic", false) } }
                docSection(vm, docs, "Duplicate", "Duplicate", report.duplicates.flatMap { it.copies }, dupRows, docNames)
                docSection(vm, docs, "Mari", "Mari", report.large, report.large.map { DocRowModel(it, "mare", false) }, docNames)
                docSection(vm, docs, "Vechi", "Vechi", report.old, report.old.map { DocRowModel(it, "vechi (>3 luni)", false) }, docNames)
                docSection(vm, docs, "Suspecte", "Duplicate", report.suspects.map { it.first }, report.suspects.map { DocRowModel(it.first, it.second, false) }, docNames)
                docSection(vm, docs, "AI: de aruncat", "De verificat", aiExtras, aiExtras.map { DocRowModel(it, null, false) }, docNames, moveButton = false)
                if (vm.aiAvailable || (siteOn && docs.jobId != null)) {
                    item(key = "ai") {
                        AiPanelCard(
                            ai = docs.ai, aiOn = aiOn, aiAvailable = vm.aiAvailable,
                            subject = "PDF-urile (≤ 4 MB), fragmente scurte din fișierele text și numele celorlalte",
                            onToggle = { vm.setAiOn(it) },
                            onRetry = { vm.retryDocsAi() },
                            onApplyFolder = { vm.applyDocsAiFolder(it) },
                            siteOn = siteOn, contract = contract, siteJob = docs.jobId != null,
                            siteRoot = OrganizerJobs.DOC_DESTINATION, site = docs.site,
                            nameOf = { uri -> report.items.firstOrNull { it.key == uri }?.name ?: "fișier" },
                            onApplySiteFolder = { vm.applyDocsSiteFolder(it) }
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (report.warnings.isNotEmpty()) {
                    item(key = "warnings") {
                        Text(report.warnings.take(2).joinToString(" · "), style = BodyTiny.copy(color = TextDim))
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            if (docs.busy) Text("lucrez…", style = monoLabel(10, 0.12f).copy(color = Accent2), modifier = Modifier.padding(bottom = 8.dp))
            if (selectedDocs.isNotEmpty()) {
                PrimaryButton(
                    "Șterge ${selectedDocs.size} ${if (selectedDocs.size == 1) "fișier" else "fișiere"} · ${fmtBytes(selectedDocs.sumOf { it.sizeBytes })}",
                    onClick = { vm.deleteDocs(selectedDocs) }, modifier = Modifier.fillMaxWidth(), enabled = !docs.busy
                )
                Spacer(Modifier.height(8.dp))
            }
            if (docs.undoCount > 0) {
                SecondaryButton("Anulează ultima mutare", onClick = { vm.undoLastDocMove() }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun LazyListScope.docSection(
    vm: CleanupViewModel,
    docs: DocsUiState,
    title: String,
    category: String,
    items: List<DocItem>,
    rows: List<DocRowModel>,
    aiNames: Map<String, String>,
    moveButton: Boolean = true
) {
    if (items.isEmpty()) return
    val keys = items.map { it.key }
    val allSelected = keys.all { it in docs.selected }
    val targets = items.filter { it.key in docs.selected }.ifEmpty { items }
    item(key = "h-$title") {
        SectionLabel("$title · ${items.size} · ${fmtBytes(items.sumOf { it.sizeBytes })}", color = Accent2)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (allSelected) "Deselectează" else "Selectează tot", onClick = { vm.selectDocs(keys, !allSelected) }, padV = 8.dp)
            if (moveButton) SecondaryButton("Mută în ${DocumentOrganizer.ROOT_FOLDER}/$category", onClick = { vm.moveDocs(vm.docTargets(items), category) }, padV = 8.dp)
            SecondaryButton("Șterge ${targets.size}", onClick = { vm.deleteDocs(vm.docTargets(items)) }, padV = 8.dp, textColor = Error)
        }
        if (!moveButton) {
            Spacer(Modifier.height(4.dp))
            Text("Recomandarea modelului, cu motiv. Verifică și confirmă tu.", style = BodyTiny.copy(color = TextDim))
        }
        Spacer(Modifier.height(8.dp))
    }
    items(rows, key = { "d-$title-${it.item.key}" }) { r ->
        DocRow(
            item = r.item, selected = r.item.key in docs.selected, reason = r.reason, keeper = r.keeper,
            badge = docs.ai.suggestions[vm.docAiId(r.item)],
            siteHint = docs.site[r.item.key]?.takeIf { it.analysis != null || it.state == "moved" || it.state == "needs_review" },
            onToggle = { vm.toggleDoc(r.item.key) },
            aiNameOf = { aiNames[it] }
        )
    }
    item(key = "sp-$title") { Spacer(Modifier.height(10.dp)) }
}

@Composable
private fun DocRow(
    item: DocItem, selected: Boolean, reason: String?, keeper: Boolean, badge: OrganizeVerdictV2?,
    onToggle: () -> Unit, siteHint: SiteHint? = null, aiNameOf: (String) -> String? = { null }
) {
    ForjaCard(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).pressable(onToggle),
        fill = if (selected) Color(0x14FF4D3A) else Surface1,
        stroke = if (selected) Color(0x66FF4D3A) else if (keeper) Positive.copy(alpha = 0.45f) else StrokeCard,
        padding = 12.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = BodyStrong.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val folder = item.path.substringBeforeLast('/', "")
                Text(
                    buildString {
                        append(fmtBytes(item.sizeBytes)); append(" · "); append(Fmt.freshness(item.lastModified))
                        if (folder.isNotBlank()) { append(" · "); append(folder) }
                        if (keeper) append(" · păstrat") else if (reason != null) { append(" · "); append(reason) }
                    },
                    style = BodyTiny.copy(color = if (keeper) Positive else if (reason != null) Accent2 else TextDim),
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (badge != null) {
                    Spacer(Modifier.height(4.dp))
                    AiVerdictRow(badge, nameOf = aiNameOf)
                }
                if (siteHint != null) {
                    Spacer(Modifier.height(4.dp))
                    SiteHintLine(siteHint)
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(if (selected) Error else Surface2)
                    .border(1.dp, if (selected) Error else StrokeCardStrong, CircleShape),
                contentAlignment = Alignment.Center
            ) { if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
        }
    }
}
