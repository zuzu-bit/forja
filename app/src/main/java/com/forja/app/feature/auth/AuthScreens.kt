package com.forja.app.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.PrimaryButton
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.designsystem.components.topoBackground
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import kotlinx.coroutines.launch

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
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            textStyle = BodyStrong.copy(fontSize = 15.sp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(SecondaryShape)
                .border(1.dp, StrokeCardStrong, SecondaryShape),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface2,
                unfocusedContainerColor = Surface1,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent2,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}

/** Login + Register — poarta către contul FORJA (Firebase). */
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

    Box(
        Modifier
            .fillMaxSize()
            .topoBackground(decor = true)
            .background(
                Brush.radialGradient(
                    listOf(Color(0x266F855A), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(180f, 300f),
                    radius = 800f
                )
            )
    ) {
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
                Icon(Icons.Filled.LocalFireDepartment, contentDescription = null, tint = Accent2, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Text("FORJA", style = TitleModule.copy(fontSize = 34.sp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (isLogin) "CONTUL TĂU · LIVE IT" else "CONT NOU · LIVE IT",
                style = monoLabel(10, 0.16f).copy(color = Accent2)
            )
            Spacer(Modifier.height(36.dp))
            Text(
                if (isLogin) "Bine ai revenit." else "Hai să te cunoaștem.",
                style = TitleOnboarding.copy(fontSize = 32.sp, lineHeight = 35.sp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isLogin) "Intră în cont — progresul și prietenii tăi te așteaptă."
                else "Contul tău ține progresul, prietenii și harta voastră comună.",
                style = Body.copy(fontSize = 15.sp, lineHeight = 20.sp)
            )
            Spacer(Modifier.height(28.dp))

            if (!isLogin) {
                ForjaField(name, { name = it }, "Numele tău")
                Spacer(Modifier.height(14.dp))
            }
            ForjaField(email, { email = it }, "Email", keyboard = KeyboardType.Email)
            Spacer(Modifier.height(14.dp))
            ForjaField(password, { password = it }, "Parolă (minim 6 caractere)", keyboard = KeyboardType.Password, password = true)

            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = Body.copy(color = Error, fontSize = 13.sp, lineHeight = 17.sp))
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
