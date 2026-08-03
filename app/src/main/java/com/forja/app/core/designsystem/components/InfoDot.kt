package com.forja.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.forja.app.core.designsystem.*

/**
 * Un „!" mic și discret. La atingere deschide un panou cald cu explicația —
 * ca termenii/condițiile/notele să nu-ți stea zilnic în față, dar să fie mereu la o apăsare.
 */
@Composable
fun InfoDot(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    size: Int = 22,
    tint: Color = Accent2
) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.30f), CircleShape)
            .pressable({ open = true }),
        contentAlignment = Alignment.Center
    ) { Text("!", style = BodyStrong.copy(color = tint, fontSize = (size * 0.6f).sp)) }

    if (open) {
        Dialog(onDismissRequest = { open = false }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(Surface1)
                    .border(1.dp, StrokeCardStrong, CardShape)
                    .padding(22.dp)
            ) {
                androidx.compose.foundation.layout.Column {
                    if (title != null) {
                        Text(title, style = TitleModule.copy(fontSize = 18.sp))
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(text, style = Body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary))
                    Spacer(Modifier.height(18.dp))
                    PrimaryButton("Am înțeles", onClick = { open = false }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
