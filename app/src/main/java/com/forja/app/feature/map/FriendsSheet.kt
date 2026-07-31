package com.forja.app.feature.map

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
import com.forja.app.ForjaApp
import com.forja.app.core.data.Friend
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.launch

/** Sheet „Prietenii tăi" — doar oameni reali, stări live, freshness onest, invitație prin cod. */
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
            Text("Prietenii tăi", style = TitleModule.copy(fontSize = 20.sp))
            Text(
                if (friends.isEmpty()) "încă niciunul — schimbați codurile și apăreți pe hartă"
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

            Spacer(Modifier.height(18.dp))
            SectionLabel("Pe hartă acum")
            Spacer(Modifier.height(8.dp))

            if (friends.isEmpty()) {
                Text(
                    "Harta prinde viață când primul prieten acceptă. Fără conturi false, fără roboți — doar oamenii tăi.",
                    style = BodySmall.copy(color = TextDim)
                )
            }

            friends.sortedByDescending { it.locUpdatedAt }.forEach { f ->
                val fresh = System.currentTimeMillis() - f.locUpdatedAt < 15 * 60_000
                ForjaCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .pressable({ onPick(f) }),
                    fill = Surface2, padding = 12.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name = f.name, size = 40.dp, ring = fresh, live = fresh && f.state in setOf("run", "walk", "ride"))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = BodyStrong.copy(fontSize = 14.sp))
                            Text(
                                when {
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
                            if (f.weekKm > 0) {
                                Text(
                                    "${Fmt.km(f.weekKm * 1000)} km săptămâna asta",
                                    style = BodyTiny.copy(color = TextSecondary)
                                )
                            }
                        }
                        Text(
                            if (f.lat != null && !f.ghost) "Pe hartă" else "Salută",
                            style = BodySmall.copy(color = Accent2)
                        )
                    }
                }
            }
        }
    }
}
