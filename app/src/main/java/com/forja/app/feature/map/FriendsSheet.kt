package com.forja.app.feature.map

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.forja.app.ForjaApp
import com.forja.app.core.data.Friend
import com.forja.app.core.location.BgLocation
import com.forja.app.core.social.ContactsSync
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.launch

/** Sheet „Prietenii tăi" — doar oameni reali, stări live, freshness onest, invitație prin cod, agendă, familie. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsSheet(
    friends: List<Friend>,
    onClose: () -> Unit,
    onPick: (Friend) -> Unit
) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var myCode by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { myCode = app.auth.loadProfile()?.inviteCode ?: "" } catch (_: Exception) {}
    }

    // Comutatorul „Familie” răspunde pe loc; Firestore confirmă imediat după.
    val familyOverride = remember { mutableStateMapOf<String, Boolean>() }
    val familyBusy = remember { mutableStateMapOf<String, Boolean>() }

    // Din agendă: potrivirile salvate local (numele din agenda MEA; nu pleacă nicăieri).
    val matchesRaw by app.prefs.contactMatches.collectAsState(initial = "")
    val matches = remember(matchesRaw) { ContactsSync.decode(matchesRaw) }
    val mutualUids = remember(matches) { matches.filter { it.mutual }.map { it.uid }.toSet() }
    val friendUids = remember(friends) { friends.map { it.uid }.toSet() }
    val fromAgenda = remember(matches, friendUids) { matches.filter { !it.mutual && it.uid !in friendUids } }

    // Familie mereu pornită: cere „Tot timpul” dacă lipsește (starea se reface la revenirea din setări).
    var permTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) permTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val bgOk = remember(permTick) { BgLocation.hasBackground(context) }
    val fineOk = remember(permTick) { BgLocation.hasFine(context) || BgLocation.hasCoarse(context) }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++; BgLocation.registerIfReady(context) }
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permTick++
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true && Build.VERSION.SDK_INT >= 29 && !BgLocation.hasBackground(context)) {
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    fun askAlways() {
        when {
            !fineOk -> fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            Build.VERSION.SDK_INT >= 29 -> bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    val familyAny = friends.any { familyOverride[it.uid] ?: it.family }

    val activeCount = friends.count { !it.ghost && System.currentTimeMillis() - it.locUpdatedAt < 15 * 60_000 }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .fillMaxHeight(0.85f)
                .verticalScroll(rememberScrollState())
        ) {
            ModuleHeader(
                stamp = "CAMARAZI",
                title = "Prietenii tăi",
                titleStyle = TitleModule.copy(fontSize = 20.sp)
            )
            Text(
                if (friends.isEmpty()) "Încă niciunul. Schimbați codurile și apăreți pe hartă."
                else "${friends.size} · $activeCount activi acum",
                style = BodySmall.copy(color = TextSecondary)
            )

            Spacer(Modifier.height(16.dp))

            // Codul meu — vizibil, ușor de dat mai departe.
            ForjaCard(Modifier.fillMaxWidth(), fill = Surface2) {
                SectionLabel("Codul tău de invitație")
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (myCode.isEmpty()) "…" else "FORJA-$myCode",
                        style = heroNumeral(22).copy(color = Accent2)
                    )
                    Spacer(Modifier.weight(1f))
                    SecondaryButton("Copiază", padV = 8.dp, onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("FORJA", "FORJA-$myCode"))
                        toast.show("Cod copiat. Trimite-l prietenului tău.")
                    })
                }
                Spacer(Modifier.height(4.dp))
                Text("Prietenul îl introduce mai jos, la el în aplicație — și gata.", style = BodyTiny.copy(color = TextDim))
            }

            Spacer(Modifier.height(12.dp))

            // Adaugă prin cod
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    singleLine = true,
                    placeholder = { Text("Codul prietenului (ex: FORJA-A2K9ZP)", style = BodySmall) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = BodyStrong.copy(fontSize = 14.sp),
                    modifier = Modifier
                        .weight(1f)
                        .clip(SecondaryShape)
                        .border(1.dp, StrokeCardStrong, SecondaryShape),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        cursorColor = Accent2,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    )
                )
                Spacer(Modifier.width(10.dp))
                if (busy) {
                    CircularProgressIndicator(color = Accent2, modifier = Modifier.size(24.dp))
                } else {
                    PrimaryButton("Adaugă", small = true, onClick = {
                        val uid = app.auth.currentUid ?: return@PrimaryButton
                        busy = true
                        scope.launch {
                            val res = try {
                                app.friends.addFriendByCode(uid, code)
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                            busy = false
                            res.fold(
                                onSuccess = {
                                    code = ""
                                    toast.show("$it e acum prietenul tău. Vă vedeți pe hartă.")
                                },
                                onFailure = {
                                    toast.show(it.message ?: "Nu s-a putut. Verifică internetul și codul.")
                                }
                            )
                        }
                    })
                }
            }

            // Familia te vede și când FORJA e închisă — doar cu locația „Tot timpul”.
            if (familyAny && !bgOk) {
                Spacer(Modifier.height(12.dp))
                ForjaCard(Modifier.fillMaxWidth(), fill = Surface2, stroke = EmberWarm.copy(alpha = 0.45f)) {
                    Text("Familia ta nu te vede încă tot timpul.", style = BodyStrong.copy(fontSize = 14.sp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Ca să te vadă și când FORJA e închisă, permite locația tot timpul. Android deschide pagina lui; alege „Se permite tot timpul”.",
                        style = BodyTiny.copy(color = TextSecondary)
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Permite tot timpul", onClick = ::askAlways, small = true)
                }
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("Pe hartă acum")
            Spacer(Modifier.height(8.dp))

            if (friends.isEmpty()) {
                Text(
                    "Harta se umple când primul prieten acceptă. Fără conturi false, fără roboți. Doar oamenii tăi.",
                    style = BodySmall.copy(color = TextDim)
                )
            }

            friends.sortedByDescending { it.locUpdatedAt }.forEach { f ->
                val fresh = System.currentTimeMillis() - f.locUpdatedAt < 15 * 60_000
                val familyOn = familyOverride[f.uid] ?: f.family
                ForjaCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    fill = Surface2, padding = 12.dp
                ) {
                    Row(
                        Modifier.pressable({ onPick(f) }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(name = f.name, size = 40.dp, ring = fresh, live = fresh && f.state in setOf("run", "walk", "ride"))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, style = BodyStrong.copy(fontSize = 14.sp))
                                if (f.fromContacts || f.uid in mutualUids) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("din agendă", style = monoLabel(8, 0.1f).copy(color = Accent2))
                                }
                            }
                            Text(
                                when {
                                    f.viaFamily -> "fantomă · te vede familia lui"
                                    f.ghost -> "mod fantomă"
                                    f.state == "run" -> "aleargă acum"
                                    f.state == "ride" -> "pe roți acum"
                                    f.state == "walk" -> "se plimbă"
                                    f.state == "sleep" -> "doarme · nu-l trezi"
                                    f.lat == null -> "locație oprită"
                                    else -> "văzut ${Fmt.freshness(f.locUpdatedAt)}"
                                },
                                style = BodyTiny.copy(
                                    color = when {
                                        f.ghost -> SleepRem
                                        f.state in setOf("run", "walk", "ride") -> Positive
                                        f.state == "sleep" -> SleepRem
                                        else -> TextDim
                                    }
                                )
                            )
                            if (f.lastActivityType != null && f.lastActivityKm > 0) {
                                Text(
                                    "ultima: ${Fmt.km(f.lastActivityKm * 1000)} km ${
                                        when (f.lastActivityType) {
                                            "walk" -> "mers"; "ride" -> "ciclism"; else -> "alergare"
                                        }
                                    } · ${Fmt.freshness(f.lastActivityAt)}",
                                    style = BodyTiny.copy(color = Accent2)
                                )
                            }
                            if (f.weekKm > 0) {
                                Text(
                                    "${Fmt.km(f.weekKm * 1000)} km săptămâna asta",
                                    style = BodyTiny.copy(color = TextSecondary)
                                )
                            }
                        }
                        Text(
                            if (f.lat != null && (!f.ghost || f.viaFamily)) "Pe hartă" else "Salută",
                            style = BodySmall.copy(color = Accent2)
                        )
                    }

                    // Familie: excepția de la fantomă, aleasă de tine, om cu om.
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Familie", style = BodyStrong.copy(fontSize = 13.sp))
                            Text(
                                "Te vede și în modul fantomă (mama, iubita)",
                                style = BodyTiny.copy(color = TextDim)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        if (familyBusy[f.uid] == true) {
                            CircularProgressIndicator(color = Accent2, modifier = Modifier.size(20.dp))
                        } else {
                            ForjaSwitch(checked = familyOn) { on ->
                                val uid = app.auth.currentUid ?: return@ForjaSwitch
                                familyOverride[f.uid] = on
                                familyBusy[f.uid] = true
                                scope.launch {
                                    try {
                                        app.friends.setFamily(uid, f.uid, on, app.prefs)
                                        // Familia schimbă cadența (120 s) și pornește urmărirea chiar fără „Locație în fundal”.
                                        BgLocation.registerIfReady(context)
                                        // Documentul meu s-a actualizat; Friend.family vine prin flow, nu mai avem nevoie de override.
                                        familyOverride.remove(f.uid)
                                        val first = f.name.split(' ').first()
                                        toast.show(
                                            if (on) "$first te vede și când ești fantomă."
                                            else "$first nu te mai vede în modul fantomă."
                                        )
                                    } catch (_: Exception) {
                                        familyOverride.remove(f.uid)
                                        toast.show("Nu s-a putut. Verifică internetul.")
                                    }
                                    familyBusy.remove(f.uid)
                                }
                            }
                        }
                    }
                }
            }

            // Din agendă: au FORJA, dar nu te au (încă) în agenda lor — nicio vizibilitate fără acordul lor.
            if (fromAgenda.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionLabel("Din agendă")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Au FORJA, dar nu te au în agenda lor. Trimite-le codul tău; te adaugă ei.",
                    style = BodyTiny.copy(color = TextDim)
                )
                Spacer(Modifier.height(8.dp))
                fromAgenda.sortedBy { it.name.lowercase() }.forEach { m ->
                    ForjaCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), fill = Surface2, padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name = m.name, size = 40.dp, ring = false, live = false)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = BodyStrong.copy(fontSize = 14.sp))
                                Text(
                                    buildString {
                                        append("are FORJA")
                                        if (m.forjaName.isNotBlank() && m.forjaName != m.name) append(" ca „${m.forjaName}”")
                                        append(if (m.verified) " · număr verificat" else " · număr declarat")
                                    },
                                    style = BodyTiny.copy(color = TextDim)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            MonoButton("Trimite-i codul tău", color = Accent2, onClick = {
                                if (myCode.isEmpty()) { toast.show("Codul tău se încarcă. Încearcă imediat."); return@MonoButton }
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Sunt pe FORJA. Adaugă-mă cu codul FORJA-$myCode.")
                                }
                                try {
                                    context.startActivity(Intent.createChooser(share, "Trimite codul"))
                                } catch (_: Exception) { toast.show("Nu am găsit o aplicație de mesaje. Copiază codul de mai sus.") }
                            })
                        }
                    }
                }
            }
        }
    }
}
