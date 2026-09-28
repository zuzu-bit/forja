package com.forja.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.BoxScopeBottomScrim
import com.forja.app.core.designsystem.components.EmberField
import com.forja.app.core.designsystem.components.PopIn
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.Reveal
import com.forja.app.core.designsystem.components.StampLabel
import com.forja.app.core.designsystem.components.TopScrim
import com.forja.app.core.designsystem.components.VideoSurface
import com.forja.app.core.designsystem.components.pressable
import kotlinx.coroutines.launch

// Fundalul: balcon la asfințit — calm, mișcare puțină, se citește bine sub text.
private const val AUTH_VIDEO = "https://v.ftcdn.net/04/99/13/67/700_F_499136769_X4Pfv9UFpmLtXcXu0JLdSo80FTPH2BGx_ST.mp4"
private const val AUTH_POSTER = "https://t3.ftcdn.net/jpg/10/16/02/48/500_F_1016024842_sVPfKb4a4gZkZ7XjEjnGtdkeYz1eF2Gz.jpg"

@Composable
private fun ForjaField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = monoLabel(9, 0.14f))
        Spacer(Modifier.height(6.dp))
        TextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            textStyle = BodyStrong.copy(fontSize = 15.sp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(SecondaryShape)
                .border(1.dp, StrokeCardStrong, SecondaryShape),
            colors = TextFieldDefaults.colors(
                // Translucide: videoul se ghicește sub câmpuri, textul rămâne lizibil.
                focusedContainerColor = Color(0xCC1A1A1E),
                unfocusedContainerColor = Color(0xB3121214),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}

/** Login + Register — poarta către contul FORJA (Firebase), peste un video calm. */
@Composable
fun AuthScreens(startInLogin: Boolean, onAuthed: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()

    var isLogin by remember { mutableStateOf(startInLogin) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    var notice by remember { mutableStateOf<String?>(null) }

    fun forgot() {
        if (loading) return
        error = null; notice = null
        if (email.isBlank()) { error = "Scrie emailul contului, apoi apasă din nou „Am uitat parola”."; return }
        loading = true
        scope.launch {
            try {
                app.auth.sendPasswordReset(email)
                notice = "Ți-am trimis un email cu linkul de resetare. Verifică și dosarul Spam."
            } catch (e: Exception) {
                error = app.auth.humanError(e)
            } finally {
                loading = false
            }
        }
    }

    fun submit() {
        if (loading) return
        error = null
        if (email.isBlank() || password.isBlank() || (!isLogin && name.isBlank())) {
            error = "Completează toate câmpurile — apoi mergem mai departe."
            return
        }
        loading = true
        scope.launch {
            try {
                if (isLogin) {
                    app.auth.login(email, password)
                } else {
                    app.auth.register(name, email, password)
                }
                app.auth.loadProfile()?.let { app.prefs.setCachedName(it.name) }
                onAuthed()
            } catch (e: Exception) {
                error = app.auth.humanError(e)
            } finally {
                loading = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        VideoSurface(url = AUTH_VIDEO, posterUrl = AUTH_POSTER, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0x990A0A0B)))
        BoxScopeBottomScrim()
        TopScrim()
        EmberField(Modifier.fillMaxSize(), count = 18, alpha = 0.5f)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Spacer(Modifier.height(64.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PopIn {
                    Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = Accent2, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(8.dp))
                Reveal(index = 0) {
                    Text("FORJA", style = TitleModule.copy(fontSize = 34.sp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Reveal(index = 1, key = isLogin) {
                StampLabel(if (isLogin) "RAPORT LA DATORIE" else "ÎNROLARE")
            }
            Spacer(Modifier.height(30.dp))
            Reveal(index = 2, key = isLogin) {
                Column {
                    Text(
                        if (isLogin) "Bine ai revenit." else "Înrolarea durează un minut.",
                        style = TitleOnboarding.copy(fontSize = 32.sp, lineHeight = 35.sp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isLogin) "Intră în cont — progresul și prietenii tăi te așteaptă."
                        else "Contul tău ține progresul, prietenii și harta voastră comună.",
                        style = Body.copy(fontSize = 15.sp, lineHeight = 20.sp)
                    )
                }
            }
            Spacer(Modifier.height(28.dp))

            if (!isLogin) {
                ForjaField(name, { name = it }, "Numele tău")
                Spacer(Modifier.height(14.dp))
            }
            ForjaField(email, { email = it }, "Email", keyboard = KeyboardType.Email)
            Spacer(Modifier.height(14.dp))
            ForjaField(password, { password = it }, "Parolă (minim 6 caractere)", keyboard = KeyboardType.Password, password = true)

            error?.let { msg ->
                Spacer(Modifier.height(14.dp))
                Reveal(key = msg, offsetY = 8.dp) {
                    Text(msg, style = Body.copy(color = Error, fontSize = 13.sp, lineHeight = 17.sp))
                }
            }
            notice?.let { msg ->
                Spacer(Modifier.height(14.dp))
                Reveal(key = msg, offsetY = 8.dp) {
                    Text(msg, style = Body.copy(color = Accent2, fontSize = 13.sp, lineHeight = 17.sp))
                }
            }
            if (isLogin) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Am uitat parola",
                    style = BodySmall.copy(color = TextSecondary),
                    modifier = Modifier
                        .align(Alignment.End)
                        .pressable(::forgot)
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (loading) {
                    CircularProgressIndicator(color = Accent2, modifier = Modifier.size(28.dp))
                } else {
                    PrimaryButton(
                        text = if (isLogin) "Intră în cont" else "Creează contul",
                        onClick = ::submit,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (isLogin) "Nu ai cont? Creează unul" else "Ai deja cont? Intră",
                style = BodyStrong.copy(color = Accent2, fontSize = 14.sp),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .pressable({ isLogin = !isLogin; error = null })
                    .padding(8.dp)
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}
