package com.forja.app.feature.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.EmberWarm
import com.forja.app.core.designsystem.Error
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.OnAccent
import com.forja.app.core.designsystem.Surface0
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextDim
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.components.Mascot
import com.forja.app.core.designsystem.components.MascotHat
import com.forja.app.core.designsystem.components.MascotState
import com.forja.app.core.designsystem.components.pressable
import com.forja.app.core.inventory.InvKind
import com.forja.app.core.inventory.TreePaths

// ═════════════════════════════ S6 · Gata ═════════════════════════════

/**
 * `onGallery` = butonul principal („Galerie” / „Fișiere”), `onPlace` = eticheta cu calea (dosarul nou, în Fișiere),
 * `onSite` = „Pe site” (rularea pe /insights#inventar), `onClose` = „Închide”.
 */
data class DoneActions(
    val onGallery: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onPlace: () -> Unit = {},
    val onSite: () -> Unit = {}
)

/** O scânteie care urcă (Gata.dc.html): poziția (dp în eroul de 280), mărimea, culoarea, faza. */
private class Rise(val x: Float, val y: Float, val d: Float, val color: Color, val delay: Float)

private val Rises = listOf(
    Rise(40f, 210f, 6f, Amber, 0.2f), Rise(70f, 230f, 4f, AmberPale, 1.4f), Rise(104f, 240f, 5f, EmberWarm, 2.6f),
    Rise(138f, 236f, 7f, Amber, 0.8f), Rise(170f, 240f, 4f, AmberPale, 3.0f), Rise(204f, 228f, 6f, EmberWarm, 1.9f),
    Rise(236f, 214f, 5f, Amber, 0.5f), Rise(20f, 180f, 4f, AmberPale, 2.2f), Rise(256f, 176f, 4f, AmberPale, 1.1f),
    Rise(120f, 250f, 3f, Amber, 2.9f)
)
private const val RISE_S = 3.4f
private val InEase = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
/** CSS „ease-out”. */
private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * Finalul (Gata.dc.html): mascota fericită cu cască, pulsul inelului și scânteile care urcă, „Gata”,
 * „14 DOSARE | 1,2 GB ELIBERAȚI”, „Recuperezi 30 de zile” / „Nimic nu s-a șters”, calea noii locații (atingibilă),
 * egalizatorul turtit sus (muzica s-a oprit), [Galerie / Fișiere] și [Pe site] [Închide] jos.
 *
 * Așezarea (4.4, după testul pe S23 — 360 × 780 dp, cu barele ≈ 696 dp utili): sus și jos sunt fixe, doar mijlocul
 * derulează când nu încape (text mărit); eroul se scalează cu înălțimea (280 dp pe 851, ~230 pe 696), iar între mijloc
 * și butoane rămân mereu cel puțin 16 dp. Fără X sus-dreapta: dubla lui „Închide”, și stătea lipit de bara de stare.
 */
@Composable
fun InventoryDoneContent(state: DoneUiState, actions: DoneActions, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val t = if (!reduced) {
        rememberInfiniteTransition(label = "gata").animateFloat(0f, 1f, infiniteRepeatable(tween((RISE_S * 1000).toInt(), easing = LinearEasing)), label = "gataT")
    } else null
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) enter.animateTo(1f, tween(720, easing = LinearEasing)) }
    fun inAt(delayMs: Int): () -> Float = { InEase.transform(((enter.value * 720f - delayMs) / 500f).coerceIn(0f, 1f)) }
    val photos = state.kind == InvKind.Photos

    BoxWithConstraints(modifier.fillMaxSize().background(Surface0)) {
        val hero = (maxHeight * 0.33f).coerceIn(168.dp, 280.dp)
        val title = if (maxHeight < 720.dp) 68 else 84
        Canvas(Modifier.fillMaxSize()) {
            // radial-gradient(90% 55% at 50% 34%, amber .16 → transparent 70 %): elipsă = cerc scalat pe verticală
            val c = Offset(size.width / 2f, size.height * 0.34f)
            val rx = size.width * 0.9f
            val ry = size.height * 0.55f
            withTransform({ scale(1f, ry / rx, pivot = c) }) {
                drawCircle(
                    Brush.radialGradient(0f to Amber.copy(alpha = 0.16f), 0.7f to Color.Transparent, center = c, radius = rx),
                    radius = rx, center = c
                )
            }
        }
        Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 20.dp)) {
            // sus, fix: doar egalizatorul turtit (muzica s-a oprit)
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
                if (state.musicStopped) EqualizerFlat()
            }
            // mijlocul: centrat când e loc, derulat când nu (butoanele nu se mișcă)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = maxHeight
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.size(hero), contentAlignment = Alignment.Center) {
                        if (t != null) {
                            Canvas(Modifier.fillMaxSize()) {
                                val u = size.width / 280f
                                val tv = t.value
                                // pulsul inelului: .45 → 1.5 în primele 45 % ale buclei
                                if (tv < 0.45f) {
                                    val e = EaseOut.transform(tv / 0.45f)
                                    val scale = 0.45f + (1.5f - 0.45f) * e
                                    drawCircle(Amber.copy(alpha = 0.6f * 0.7f * (1f - e)), 100f * u * scale, center, style = Stroke(2f * u * scale))
                                }
                                for (r in Rises) {
                                    val p = ((tv * RISE_S + r.delay) / RISE_S) % 1f
                                    val e = EaseOut.transform(p)
                                    val a = if (p < 0.12f) EaseOut.transform(p / 0.12f) else 1f - EaseOut.transform((p - 0.12f) / 0.88f)
                                    val s = 1f - 0.65f * e
                                    val cx = (r.x + r.d / 2f) * u
                                    val cy = (r.y + r.d / 2f - 230f * e) * u
                                    drawCircle(r.color.copy(alpha = a.coerceIn(0f, 1f)), r.d / 2f * u * s, Offset(cx, cy))
                                }
                            }
                        }
                        Mascot(state = MascotState.Happy, hat = MascotHat.Helmet, size = hero * 0.75f)
                    }
                    Text(
                        "Gata",
                        style = cond(title, title - 4, tracking = 0.01f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).riseIn(inAt(0))
                    )
                    StatsRow(state, inAt(120), Modifier.fillMaxWidth().padding(top = 14.dp).height(62.dp).riseIn(inAt(120)))
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp).riseIn(inAt(220)),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (photos) InvIcons.Restore else InvIcons.Check, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (photos) "Recuperezi 30 de zile" else "Nimic nu s-a șters", style = body(14))
                    }
                    val place = state.place?.target
                    if (place != null) {
                        PlaceChip(place.label, actions.onPlace, Modifier.padding(top = 2.dp).riseIn(inAt(300)))
                    }
                    if (state.failed > 0) {
                        Text(
                            "${fmtCount(state.failed)} NEMUTATE",
                            style = mono(10, 0.16f, color = Error),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = if (place != null) 2.dp else 10.dp)
                        )
                    }
                }
            }
            // jos, fix, la cel puțin 16 dp de mijloc
            Spacer(Modifier.height(16.dp))
            InvPrimaryButton(if (photos) "Galerie" else "Fișiere", actions.onGallery)
            Spacer(Modifier.height(10.dp))
            if (state.showSite) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SiteButton(actions.onSite, Modifier.weight(1f))
                    InvOutlineButton("Închide", actions.onClose, Modifier.weight(1f))
                }
            } else {
                InvOutlineButton("Închide", actions.onClose)
            }
        }
    }
}

/**
 * Calea noii locații („DOCUMENTS/ORGANIZATE”), ca etichetă mono atingibilă: deschide dosarul în Fișiere. Se vede de
 * 34 dp, dar zona de atingere are 48 dp (e drumul principal spre dosar la poze); spațiul în plus stă în jurul ei.
 */
@Composable
private fun PlaceChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val path = TreePaths.tail(label).uppercase()
    Box(
        modifier
            .heightIn(min = 48.dp)
            .pressable(onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "Deschide locația $label" },
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .height(34.dp)
                .clip(R6)
                .background(Surface1)
                .border(1.dp, W09, R6)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(InvIcons.Folder, null, tint = Accent2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(path, style = mono(11, 0.08f, color = TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Icon(InvIcons.ChevronRight, null, tint = TextDim, modifier = Modifier.size(14.dp))
        }
    }
}

/** „Pe site”: butonul conturat cu globul olive, lângă „Închide”. */
@Composable
private fun SiteButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .pressable(onClick)
            .fillMaxWidth()
            .height(52.dp)
            .clip(R8)
            .border(1.dp, W12, R8)
            .semantics(mergeDescendants = true) { role = Role.Button },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(InvIcons.Globe, null, tint = Accent2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Pe site", style = cond(19, color = TextSecondary), maxLines = 1)
    }
}

/** „14 DOSARE | 1,2 GB ELIBERAȚI”; cifrele cresc odată cu apariția (P5). Progresul se citește doar aici. */
@Composable
private fun StatsRow(state: DoneUiState, progress: () -> Float, modifier: Modifier) {
    val k = progress()
    val photos = state.kind == InvKind.Photos
    Row(modifier, horizontalArrangement = Arrangement.Center) {
        Stat(fmtCount((state.folders * k).toInt()), if (state.folders == 1) "DOSAR" else "DOSARE", TextPrimary)
        Spacer(Modifier.width(22.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(W10))
        Spacer(Modifier.width(22.dp))
        if (photos && state.freedBytes > 0) Stat(fmtSize((state.freedBytes * k).toLong()), "ELIBERAȚI", Amber)
        else Stat(fmtCount((state.items * k).toInt()), "MUTATE", Amber)
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = hero(38, 38, color = color))
        Text(label, style = mono(10, 0.16f, color = TextDim))
    }
}

private fun Modifier.riseIn(p: () -> Float): Modifier = graphicsLayer {
    val v = p()
    alpha = v
    translationY = (1f - v) * 12.dp.toPx()
}

// ═════════════════════════════ Previzualizarea ═════════════════════════════

/**
 * Previzualizarea pe tot ecranul: pager peste elementele dosarului (filtrul curent), zoom cu două degete și dublă
 * atingere; jos: data · mărimea, motivul (în „De aruncat”) și o singură acțiune — „Păstrează” sau „La gunoi”.
 * Video / documente: miniatura mare + „Deschide” (aplicația telefonului).
 */
@Composable
internal fun InvPreview(
    cells: List<CellUi>,
    startId: String,
    special: Boolean,
    onClose: () -> Unit,
    onAction: (CellUi) -> Unit,
    onOpenExternal: (CellUi) -> Unit
) {
    BackHandler(onBack = onClose)
    if (cells.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val start = cells.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { cells.size }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { cells[it].id }) { page ->
            val cell = cells[page]
            PreviewPage(cell, onOpenExternal)
        }
        val current = cells.getOrNull(pager.currentPage.coerceIn(0, cells.size - 1)) ?: return@Box
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            InvIconButton(InvIcons.Close, "Închide previzualizarea", onClose, iconSize = 18.dp)
            Text("${fmtCount(pager.currentPage + 1)} / ${fmtCount(cells.size)}", style = mono(12, color = TextPrimary, bold = true))
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .navigationBarsPadding()
                .padding(start = 20.dp, top = 40.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val r = current.reason
                if (special && r != null) {
                    Icon(r.icon(), null, tint = TextPrimary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(r.tip(), style = cond(17))
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    listOf(fmtDay(current.takenAt).uppercase(), if (current.bytes > 0) fmtSize(current.bytes) else "").filter { it.isNotBlank() }.joinToString(" · "),
                    style = mono(10, 0.12f)
                )
            }
            if (special) InvPrimaryButton("Păstrează", { onAction(current) })
            else {
                Box(
                    Modifier
                        .pressable({ onAction(current) })
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(R8)
                        .background(Raised)
                        .border(1.5.dp, Error.copy(alpha = 0.7f), R8)
                        .semantics { role = Role.Button },
                    contentAlignment = Alignment.Center
                ) {
                    Text("La gunoi", style = cond(21, tracking = 0.03f, color = Error))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewPage(cell: CellUi, onOpenExternal: (CellUi) -> Unit) {
    val visual = cell.mime.startsWith("image/") || cell.mime.startsWith("video/") || isPdf(cell.mime, cell.uri)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!visual) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                DocSheet(extOf(cell.name, cell.mime), Modifier.size(width = 240.dp, height = 208.dp))
                Text(cell.name, style = cond(20), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
                OpenButton { onOpenExternal(cell) }
            }
            return@Box
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transform = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            offset = if (scale > 1f) offset + pan else Offset.Zero
        }
        AsyncImage(
            model = rememberPreviewModel(cell.uri, cell.mime),
            contentDescription = cell.name.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(cell.id) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    })
                }
                .transformable(transform, canPan = { scale > 1f })
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
        )
        if (cell.mime.startsWith("video/") || isPdf(cell.mime, cell.uri)) {
            Box(
                Modifier
                    .pressable({ onOpenExternal(cell) })
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Surface0.copy(alpha = 0.72f))
                    .semantics { contentDescription = "Deschide"; role = Role.Button },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (cell.mime.startsWith("video/")) InvIcons.Play else InvIcons.ChevronRight, null, tint = TextPrimary, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun OpenButton(onClick: () -> Unit) {
    Box(
        Modifier
            .pressable(onClick)
            .height(48.dp)
            .clip(R8)
            .background(CtaBrush)
            .semantics { role = Role.Button }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Deschide", style = cond(19, color = OnAccent))
    }
}
