package com.forja.app.feature.games

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.EmberHot
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.games.GameId
import com.forja.app.core.games.GameProgress
import com.forja.app.core.games.GameStore
import com.forja.app.feature.inventory.Amber
import com.forja.app.feature.inventory.MediaBg
import com.forja.app.feature.inventory.R4
import com.forja.app.feature.inventory.mono

/*
 * Cardurile ZID și ASALT din S2 (Rulare): o miniatură desenată a fiecărui joc + cipul „NIV. 4” în colț.
 * Mișcarea (piesa care cade, scânteia care sare) merge doar fără mișcare redusă; în capturi e statică.
 */

/** Nivelurile arătate pe carduri (null = încă necitite). */
data class WaitLevels(val zid: Int?, val asalt: Int?)

/** Nivelul curent al fiecărui joc, pentru cardurile „Cât aștepți”. */
@Composable
fun rememberWaitLevels(): WaitLevels {
    val context = LocalContext.current
    val zidFlow = remember(context) { GameStore.progress(context, GameId.Zid) }
    val asaltFlow = remember(context) { GameStore.progress(context, GameId.Asalt) }
    val z by zidFlow.collectAsState(initial = null)
    val a by asaltFlow.collectAsState(initial = null)
    return WaitLevels(z?.let { cardLevel(GameId.Zid, it) }, a?.let { cardLevel(GameId.Asalt, it) })
}

private fun cardLevel(game: GameId, p: GameProgress): Int = p.current(game)

/** Cipul „NIV. 4” din colțul miniaturii. */
@Composable
internal fun BoxScope.LevelChip(level: Int?) {
    if (level == null) return
    Box(
        Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .clip(R4)
            .background(Surface0.copy(alpha = 0.72f))
            .padding(horizontal = 5.dp, vertical = 3.dp)
    ) {
        Text("NIV. $level", style = mono(9, 0.1f, color = Amber, bold = true), maxLines = 1)
    }
}

/** Ceasul miniaturilor (ms), doar cu mișcare. */
@Composable
private fun rememberArtClock(moving: Boolean): androidx.compose.runtime.State<Long> {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val t0 = withFrameMillis { it } - clock.longValue
        while (true) withFrameMillis { clock.longValue = it - t0 }
    }
    return clock
}

private val ZidArt = arrayOf(
    "......",
    "......",
    "......",
    "......",
    "2....4",
    "22..44",
    "771.64",
    "7716.6"
)

private fun DrawScope.miniBrick(l: Float, t: Float, c: Float, color: Color) {
    val g = c * 0.07f
    drawRoundRect(color, topLeft = Offset(l + g, t + g), size = Size(c - 2 * g, c - 2 * g), cornerRadius = CornerRadius(c * 0.1f))
    drawRect(Color.White.copy(alpha = 0.18f), topLeft = Offset(l + g, t + g), size = Size(c - 2 * g, c * 0.08f))
    drawRect(Color.Black.copy(alpha = 0.22f), topLeft = Offset(l + g, t + c - g - c * 0.08f), size = Size(c - 2 * g, c * 0.08f))
}

/** Miniatura ZID: un zid început, un T care coboară spre locul lui (conturul punctat). */
@Composable
internal fun ZidCardArt(level: Int?, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val clock = rememberArtClock(!reduced)
    Box(modifier.fillMaxSize().background(MediaBg)) {
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val cols = 6
                val rows = ZidArt.size
                val c = minOf(size.width * 0.8f / cols, size.height * 0.84f / rows)
                val ox = (size.width - c * cols) / 2f
                val oy = (size.height - c * rows) / 2f + c * 0.2f
                val dash = PathEffect.dashPathEffect(floatArrayOf(c * 0.2f, c * 0.14f))
                val sw = maxOf(1f, c * 0.07f)
                onDrawBehind {
                    for (r in 0 until rows) for (col in 0 until cols) {
                        val ch = ZidArt[r][col]
                        if (ch == '.') continue
                        miniBrick(ox + col * c, oy + r * c, c, ZidKindColors[ch - '0'])
                    }
                    // un T cu vârful în jos coboară pe rânduri spre locul lui (rândurile 4–5, conturul punctat)
                    val land = 4f
                    val t = clock.value
                    val top = if (reduced) 1f else ((t % 2_400L) / 480L).toFloat().coerceAtMost(land)
                    val ghostC = Accent2.copy(alpha = 0.5f)
                    for ((gx, gy) in arrayOf(2 to 0, 3 to 0, 4 to 0, 3 to 1)) {
                        val l = ox + gx * c
                        val tt = oy + (land + gy) * c
                        drawRect(ghostC, topLeft = Offset(l + c * 0.08f, tt + c * 0.08f), size = Size(c * 0.84f, c * 0.84f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw, pathEffect = dash))
                    }
                    for ((gx, gy) in arrayOf(2 to 0, 3 to 0, 4 to 0, 3 to 1)) {
                        miniBrick(ox + gx * c, oy + (top + gy) * c, c, ZidKindColors[3])
                    }
                }
            }
        )
        LevelChip(level)
    }
}

/** Miniatura ASALT: trei rânduri de zid, nicovala și scânteia în drum spre ele. */
@Composable
internal fun AsaltCardArt(level: Int?, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val clock = rememberArtClock(!reduced)
    Box(modifier.fillMaxSize().background(MediaBg)) {
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val cols = 5
                val bw = size.width * 0.84f / cols
                val bh = bw * 0.46f
                val ox = (size.width - bw * cols) / 2f
                val oy = size.height * 0.2f
                val g = bw * 0.05f
                val kinds = arrayOf(
                    intArrayOf(4, 3, 4, 3, 4),
                    intArrayOf(2, 2, 5, 2, 2),
                    intArrayOf(1, 1, 0, 1, 1)
                )
                val anvilW = size.width * 0.36f
                val anvilY = size.height * 0.84f
                val glow = Brush.radialGradient(listOf(EmberHot.copy(alpha = 0.55f), Color.Transparent), center = Offset.Zero, radius = bw * 0.55f)
                val r = bw * 0.13f
                val dash = PathEffect.dashPathEffect(floatArrayOf(r * 0.9f, r * 0.9f))
                onDrawBehind {
                    for (row in 0 until 3) for (col in 0 until cols) {
                        val k = kinds[row][col]
                        if (k == 0) continue
                        val l = ox + col * bw + g
                        val t = oy + row * bh + g
                        drawRoundRect(AsaltBrickColors[k], topLeft = Offset(l, t), size = Size(bw - 2 * g, bh - 2 * g), cornerRadius = CornerRadius(g * 1.4f))
                        if (k == 5) {
                            drawLine(Color.Black.copy(alpha = 0.45f), Offset(l + bw * 0.3f, t + bh - 2 * g), Offset(l + bw * 0.45f, t), g * 1.6f)
                            drawLine(Color.Black.copy(alpha = 0.45f), Offset(l + bw * 0.55f, t + bh - 2 * g), Offset(l + bw * 0.7f, t), g * 1.6f)
                        }
                        if (k == 4) drawCircle(Rivet, g, Offset(l + g * 3, t + g * 3))
                    }
                    // nicovala
                    val ax = size.width / 2f
                    drawRoundRect(AnvilFace, topLeft = Offset(ax - anvilW / 2, anvilY), size = Size(anvilW, bh * 0.4f), cornerRadius = CornerRadius(g))
                    drawRoundRect(AnvilBody, topLeft = Offset(ax - anvilW * 0.3f, anvilY + bh * 0.4f), size = Size(anvilW * 0.6f, bh * 0.45f), cornerRadius = CornerRadius(g))
                    // scânteia: urcă în V de pe nicovală spre golul din zid
                    val t = clock.value
                    val p = if (reduced) 0.55f else ((t % 1_800L) / 1_800f)
                    val startX = ax
                    val startY = anvilY - r
                    val endX = ox + 2.5f * bw
                    val endY = oy + 3f * bh + r
                    val bx = startX + (endX - startX) * p + (if (p < 0.5f) p else 1f - p) * bw * 1.4f
                    val by = startY + (endY - startY) * p
                    drawLine(EmberWarm.copy(alpha = 0.45f), Offset(startX, startY), Offset(bx, by), r * 0.6f, cap = StrokeCap.Round, pathEffect = dash)
                    drawCircle(glow, bw * 0.55f, Offset(bx, by))
                    drawCircle(EmberHot, r, Offset(bx, by))
                }
            }
        )
        LevelChip(level)
    }
}
