package com.forja.app.core.designsystem.components

/*
 * STUB TEMPORAR — mascota reală (spirit de jar, vectorială, animată) e scrisă în paralel de pachetul „mascot”
 * și înlocuiește acest fișier la îmbinare. Aici doar semnăturile exacte + un desen simplu (cerc olive cu doi ochi),
 * ca pachetul „nutrition-3” să compileze și să fie testabil.
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.Body
import com.forja.app.core.designsystem.EmberHot
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.StrokeCardStrong
import com.forja.app.core.designsystem.TextPrimary

enum class MascotState { Idle, Thinking, Happy, Sorry, Talking, Reading, Wink }
enum class MascotHat { None, Chef, Helmet }

@Composable
fun Mascot(
    state: MascotState = MascotState.Idle,
    hat: MascotHat = MascotHat.None,
    size: Dp = 120.dp,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null
) {
    val m = if (onTap != null) modifier.pressable(onTap) else modifier
    Canvas(m.size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(this.size.width / 2f, this.size.height / 2f + r * 0.08f)
        drawCircle(Accent2, r * 0.82f, c)
        drawCircle(Color(0xFF141008), r * 0.82f, c, style = Stroke(r * 0.09f))
        val eyeY = c.y - r * 0.15f
        val eyeR = if (state == MascotState.Happy) r * 0.14f else r * 0.17f
        drawCircle(Color.White, eyeR, Offset(c.x - r * 0.3f, eyeY))
        if (state != MascotState.Wink) drawCircle(Color.White, eyeR, Offset(c.x + r * 0.3f, eyeY))
        drawCircle(Color(0xFF141008), eyeR * 0.5f, Offset(c.x - r * 0.3f, eyeY))
        if (state != MascotState.Wink) drawCircle(Color(0xFF141008), eyeR * 0.5f, Offset(c.x + r * 0.3f, eyeY))
        val mouthW = r * 0.5f
        val mouthH = if (state == MascotState.Sorry) r * 0.08f else r * 0.26f
        drawOval(Color(0xFF141008), Offset(c.x - mouthW / 2f, c.y + r * 0.2f), Size(mouthW, mouthH))
        if (hat == MascotHat.Chef) {
            drawOval(Color.White, Offset(c.x - r * 0.55f, c.y - r * 1.15f), Size(r * 1.1f, r * 0.6f))
            drawCircle(Color(0xFF141008), r * 0.0f, c)
        }
        if (hat == MascotHat.Helmet) {
            drawOval(EmberHot, Offset(c.x - r * 0.7f, c.y - r * 1.05f), Size(r * 1.4f, r * 0.55f))
        }
    }
}

@Composable
fun MascotSays(
    text: String,
    modifier: Modifier = Modifier,
    state: MascotState = MascotState.Talking,
    hat: MascotHat = MascotHat.None,
    size: Dp = 56.dp
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Mascot(state = state, hat = hat, size = size)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(Surface1)
                .border(1.dp, StrokeCardStrong, RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(text, style = Body.copy(color = TextPrimary, fontSize = 15.sp, lineHeight = 20.sp))
        }
    }
}

@Composable
fun MascotAvatar(hat: MascotHat = MascotHat.None, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x336F855A)),
        contentAlignment = Alignment.Center
    ) {
        Mascot(state = MascotState.Idle, hat = hat, size = size * 0.8f)
    }
}
