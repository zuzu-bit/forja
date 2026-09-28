package com.forja.app.core.designsystem.components

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.forja.app.core.designsystem.AccentGradient
import com.forja.app.core.designsystem.Body
import com.forja.app.core.designsystem.ButtonShape
import com.forja.app.core.designsystem.ButtonText
import com.forja.app.core.designsystem.LocalReducedMotion
import com.forja.app.core.designsystem.Surface1
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Ghidajul de la prima folosire (DESIGN-4.3 §8 + §11): explicațiile trăiesc aici, nu pe ecran.
 *
 *   CoachMarks(
 *       screen = "inventory.start",
 *       steps = listOf(
 *           CoachStep("kind", "Alegi: poze sau documente."),
 *           CoachStep("scope", "Tot telefonul sau doar o parte."),
 *           CoachStep("start", "Analiza merge în fundal. Nimic nu se șterge fără tine."),
 *       )
 *   ) {
 *       … Modifier.coachTarget("kind") … Modifier.coachTarget("scope") … Modifier.coachTarget("start") …
 *   }
 *
 * - Conținutul se desenează mereu, neschimbat. Suprapunerea apare o singură dată pe ecran (DataStore „forja_tutorial”),
 *   abia după ce DataStore a răspuns — cine a văzut ghidajul nu vede nici măcar un cadru din el.
 * - Țintele își raportează poziția (boundsInRoot, prin onGloballyPositioned). Pașii a căror țintă nu e pe ecran se sar;
 *   dacă în primele 3 s nu apare nicio țintă, ghidajul nu pornește (și nici nu se marchează „văzut”).
 * - Aspectul e cel din prototip (Ghidaj.dc.html, §11 — are prioritate față de §8): spot cu decupaj (6 dp în jurul
 *   țintei, rază 10), contur amber 1,5 dp + inel amber care pulsează (1,8 s), întunecare 84 %; cardul jos (mascota
 *   cu cască 64 dp în starea pasului + replica ≤ 60 de caractere, punctele pașilor, „Sari”, „Înainte” / „Am înțeles”).
 *   Cardul stă în cel mai de jos spațiu liber dintre ținte (nu acoperă nicio țintă, rămâne pe loc între pași când se
 *   poate); altfel imediat sub sau deasupra țintei curente. Spotul și cardul alunecă 450 ms (cubic-bezier .2,.8,.2,1).
 * - „Am înțeles”, „Sari” și „Înapoi” închid ghidajul și îl marchează „văzut”.
 * - Mișcare redusă: spotul și cardul sar direct, fără fade; inelul stă pe loc (fără puls).
 * - Suprapunerea acoperă doar conținutul înfășurat (bara de file din MainActivity rămâne deasupra) și ține
 *   atingerile pentru ea; cititorul de ecran vede doar ghidajul cât e deschis.
 * - Tutorial.reset (Profil → „Reia ghidajul”) re-armează toate ecranele, inclusiv pe cel deschis acum.
 */

/** Un pas: ținta (cheia din Modifier.coachTarget), replica mascotei (≤ 60 de caractere) și fața ei. */
data class CoachStep(val target: String, val text: String, val mascot: MascotState = MascotState.Talking)

// ───────────────────────────── Memoria: ce ecrane și-au văzut ghidajul ─────────────────────────────

private val Context.tutorialStore by preferencesDataStore(name = "forja_tutorial")

object Tutorial {
    private fun seenKey(screen: String) = booleanPreferencesKey("seen_$screen")

    /** Marcarea „văzut” nu ține de ecran: se termină chiar dacă utilizatorul pleacă imediat după „Am înțeles”. */
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Uită tot: fiecare ecran își arată din nou ghidajul (Profil → „Reia ghidajul”). */
    suspend fun reset(context: Context) {
        write(context) { it.clear() }
    }

    /** true după ce ghidajul ecranului a fost parcurs, sărit sau închis. Un fișier ilizibil = „nevăzut”. */
    fun seen(context: Context, screen: String): Flow<Boolean> =
        context.applicationContext.tutorialStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { it[seenKey(screen)] == true }
            .distinctUntilChanged()

    suspend fun markSeen(context: Context, screen: String) {
        write(context) { it[seenKey(screen)] = true }
    }

    internal fun markSeenInBackground(context: Context, screen: String) {
        val app = context.applicationContext
        writes.launch { markSeen(app, screen) }
    }

    /** Scrierile nu aruncă niciodată: un disc plin nu strică ecranul (cel mult ghidajul mai apare o dată). */
    private suspend fun write(context: Context, block: (MutablePreferences) -> Unit) {
        try {
            context.applicationContext.tutorialStore.edit { block(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

// ───────────────────────────── Registrul țintelor ─────────────────────────────

/** Pozițiile țintelor (în coordonatele rădăcinii) și ale gazdei; stare Compose, deci suprapunerea le urmărește. */
@Stable
internal class CoachRegistry {
    private class Entry(val owner: Any, val rect: Rect)

    private val targets = mutableStateMapOf<String, Entry>()

    /** Gazda (conținutul înfășurat de CoachMarks), în coordonatele rădăcinii; înălțimea rădăcinii (bara de navigare). */
    var host by mutableStateOf(Rect.Zero)
    var rootHeight by mutableFloatStateOf(0f)

    fun report(key: String, owner: Any, rect: Rect) {
        val old = targets[key]
        if (old == null || old.owner !== owner || old.rect != rect) targets[key] = Entry(owner, rect)
    }

    fun forget(key: String, owner: Any) {
        if (targets[key]?.owner === owner) targets.remove(key)
    }

    /** Ținta în coordonatele gazdei, sau null dacă nu e pe ecran (necompusă, fără mărime, în afara gazdei). */
    fun rectOf(key: String): Rect? {
        val r = targets[key]?.rect ?: return null
        val h = host
        if (r.width < 1f || r.height < 1f || h.width < 1f || h.height < 1f || !r.overlaps(h)) return null
        return r.translate(-h.left, -h.top)
    }

    /** Toate țintele de pe ecran (coordonatele gazdei) — cardul nu le acoperă când are loc. */
    fun visibleRects(): List<Rect> = targets.keys.mapNotNull { rectOf(it) }

    /** Primul pas cu ținta pe ecran, începând cu `from`; -1 dacă nu mai e niciunul. */
    fun nextVisible(steps: List<CoachStep>, from: Int): Int {
        for (i in max(from, 0) until steps.size) if (rectOf(steps[i].target) != null) return i
        return -1
    }
}

internal val LocalCoachRegistry = staticCompositionLocalOf<CoachRegistry?> { null }

/**
 * Marchează elementul ca țintă de ghidaj (cheia din CoachStep.target). În afara unui CoachMarks nu face nimic.
 * Pus primul în lanțul de modificatori, spotul cuprinde tot elementul (inclusiv padding-ul de după el).
 */
fun Modifier.coachTarget(key: String): Modifier = this then CoachTargetElement(key)

private class CoachTargetElement(private val key: String) : ModifierNodeElement<CoachTargetNode>() {
    override fun create() = CoachTargetNode(key)

    override fun update(node: CoachTargetNode) = node.retarget(key)

    override fun InspectorInfo.inspectableProperties() {
        name = "coachTarget"
        properties["key"] = key
    }

    override fun equals(other: Any?) = other is CoachTargetElement && other.key == key

    override fun hashCode() = key.hashCode()
}

private class CoachTargetNode(private var key: String) :
    Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    private var registry: CoachRegistry? = null

    fun retarget(newKey: String) {
        if (newKey == key) return
        registry?.forget(key, this)
        key = newKey
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val reg = currentValueOf(LocalCoachRegistry) ?: return
        registry = reg
        if (coordinates.isAttached) reg.report(key, this, coordinates.boundsInRoot())
    }

    override fun onDetach() {
        registry?.forget(key, this)
        registry = null
    }
}

// ───────────────────────────── Gazda ─────────────────────────────

/** Conținutul ecranului + ghidajul lui, doar prima dată. */
@Composable
fun CoachMarks(screen: String, steps: List<CoachStep>, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext
    // null = DataStore încă n-a răspuns: până atunci nu se arată nimic (fără „clipit” pentru cine l-a văzut).
    val seen by produceState<Boolean?>(initialValue = null, app, screen) {
        Tutorial.seen(app, screen).collect { value = it }
    }
    var closed by remember(screen) { mutableStateOf(false) }
    // După „văzut” starea locală nu mai contează; o resetare ulterioară (Profil → „Reia ghidajul”) re-armează ecranul.
    LaunchedEffect(seen) { if (seen == true) closed = false }
    CoachMarksHost(
        steps = steps,
        active = seen == false && !closed,
        onFinish = { shown ->
            closed = true
            if (shown) Tutorial.markSeenInBackground(app, screen)
        },
        content = content
    )
}

// Valorile din prototip (Ghidaj.dc.html; px din prototip = dp).
/** Înălțimea estimată a cardului (mascota 64 + butoanele 40 + spații), doar pentru decizia „încape între ținte?”. */
private val CardEstimate = 164.dp
private const val START_WINDOW_MS = 3_000L
private const val MOVE_MS = 450
private const val PULSE_MS = 1_800
private val MoveEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val SpotPadding = 6.dp            // spotul depășește ținta cu 6 dp
private val SpotRadius = 10.dp
private val SpotBorder = 1.5.dp
private val PulseOutset = 6.dp            // inelul pulsant: încă 6 dp în afara spotului, rază 14, 2 dp
private val PulseRadius = 14.dp
private val PulseStroke = 2.dp
private val CardMargin = 16.dp            // cardul: 16 dp de margini, 16 dp între el și ținte
private val Scrim = Color(0xD6060607)     // rgba(6, 6, 7, .84)
private val Amber = Color(0xFFF3B952)
private val PulseAmber = Color(0x99F3B952)
private val DotOff = Color(0xFF3A3D44)
private val CardStroke = Color(0x1AFFFFFF)
private val CardShape = RoundedCornerShape(8.dp)
private val CardText = Body.copy(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
private val NextText = ButtonText.copy(letterSpacing = 0.03.em)
private val SkipText = ButtonText.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.em, color = TextSecondary)

/** Ce s-a arătat ultima dată — folosit cât suprapunerea se stinge (fade-out). */
private class Shown {
    var index = -1
    var rect = Rect.Zero
    var obstacles: List<Rect> = emptyList()
    var position = 0
    var count = 1
    var isLast = true
}

/**
 * Gazda fără DataStore: `active` spune dacă ghidajul e de arătat; `onFinish(true)` = parcurs / sărit / închis,
 * `onFinish(false)` = nicio țintă n-a apărut la timp. `startAt` (primul pas încercat) e pentru capturile de test.
 */
@Composable
internal fun CoachMarksHost(
    steps: List<CoachStep>,
    active: Boolean,
    onFinish: (shown: Boolean) -> Unit,
    startAt: Int = 0,
    content: @Composable () -> Unit
) {
    val registry = remember { CoachRegistry() }
    val reduced = LocalReducedMotion.current
    val finish by rememberUpdatedState(onFinish)
    var index by remember(steps, startAt) { mutableIntStateOf(-1) }      // -1 = încă pe primul pas vizibil
    var expired by remember(steps, startAt) { mutableStateOf(false) }

    val current = when {
        !active || steps.isEmpty() -> -1
        index >= 0 -> index
        expired -> -1
        else -> registry.nextVisible(steps, startAt)
    }
    val showing = current >= 0

    // Ținte care apar abia după câteva secunde (după o acțiune) nu mai pornesc ghidajul peste utilizator.
    LaunchedEffect(active, steps, startAt) {
        if (!active || steps.isEmpty()) return@LaunchedEffect
        delay(START_WINDOW_MS)
        if (index < 0 && registry.nextVisible(steps, startAt) < 0) {
            expired = true
            finish(false)
        }
    }

    fun close() {
        index = -1
        finish(true)
    }

    fun next() {
        val n = registry.nextVisible(steps, current + 1)
        if (n < 0) close() else index = n
    }

    val fade by animateFloatAsState(
        targetValue = if (showing) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(if (showing) 220 else 160),
        label = "coachFade"
    )

    val last = remember { Shown() }
    if (showing) {
        val order = steps.indices.filter { registry.rectOf(steps[it].target) != null }
        last.index = current
        last.rect = registry.rectOf(steps[current].target) ?: last.rect
        last.obstacles = registry.visibleRects()
        last.position = order.indexOf(current).coerceAtLeast(0)
        last.count = max(order.size, 1)
        last.isLast = registry.nextVisible(steps, current + 1) < 0
    }

    Box(
        Modifier.onGloballyPositioned {
            registry.host = it.boundsInRoot()
            registry.rootHeight = it.findRootCoordinates().size.height.toFloat()
        },
        propagateMinConstraints = true
    ) {
        Box(if (showing) Modifier.clearAndSetSemantics { } else Modifier, propagateMinConstraints = true) {
            CompositionLocalProvider(LocalCoachRegistry provides registry) { content() }
        }
        // După conținut: callback-ul se înregistrează ultimul, deci „Înapoi” închide întâi ghidajul, nu ecranul.
        BackHandler(enabled = showing) { close() }
        if ((showing || fade > 0.01f) && last.index in steps.indices) {
            CoachOverlay(
                step = steps[last.index],
                target = last.rect,
                obstacles = last.obstacles,
                host = registry.host,
                rootHeight = registry.rootHeight,
                position = last.position,
                count = last.count,
                isLast = last.isLast,
                reduced = reduced,
                alpha = fade,
                onNext = { if (showing) next() },
                onSkip = { if (showing) close() },
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

// ───────────────────────────── Suprapunerea ─────────────────────────────

@Composable
private fun CoachOverlay(
    step: CoachStep,
    target: Rect,
    obstacles: List<Rect>,
    host: Rect,
    rootHeight: Float,
    position: Int,
    count: Int,
    isLast: Boolean,
    reduced: Boolean,
    alpha: Float,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val moveRect: AnimationSpec<Rect> = if (reduced) snap() else tween(MOVE_MS, easing = MoveEasing)
    val spot: State<Rect> = animateRectAsState(
        targetValue = target.inflate(with(density) { SpotPadding.toPx() }),
        animationSpec = moveRect,
        label = "coachSpot"
    )
    // Pulsul inelului: 1 → 1,08 și .85 → 0 în 1,8 s. Sub mișcare redusă nu există deloc (inel static).
    val pulse: State<Float>? = if (reduced) null else rememberInfiniteTransition(label = "coachPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = EaseOut)),
        label = "coachPulseT"
    )

    // Cardul: marginea lui de JOS se calculează din ținte (cu o înălțime estimată pentru „încape?”) și se animă;
    // cardul se așază deasupra ei chiar în trecerea de layout care îl măsoară — fără stare intermediară, deci apare
    // în același cadru cu spotul (înainte aștepta un cadru în plus și în capturi nu apărea deloc).
    val statusTop = WindowInsets.statusBars.getTop(density).toFloat()
    val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
    // Barele sistemului, cât intră din ele peste gazdă (ecranele sunt edge-to-edge).
    val topSafe = max(0f, statusTop - host.top)
    val bottomSafe = max(0f, navBottom - max(0f, rootHeight - host.bottom))
    val estimate = with(density) { CardEstimate.toPx() }
    val bottomTarget: Float = if (host.height < 1f) 0f else density.cardTop(
        hole = target,
        obstacles = obstacles,
        cardHeight = estimate,
        height = host.height,
        topSafe = topSafe,
        bottomSafe = bottomSafe
    ) + estimate
    val cardBottom: State<Float> = animateFloatAsState(
        targetValue = bottomTarget,
        animationSpec = if (reduced) snap() else tween(MOVE_MS, easing = MoveEasing),
        label = "coachCardBottom"
    )

    Box(modifier.graphicsLayer { this.alpha = alpha }) {
        // Întunecarea cu decupaj: strat separat, spotul „șters” cu BlendMode.Clear, apoi conturul și inelul amber.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            val s = spot.value
            val corner = CornerRadius(SpotRadius.toPx())
            drawRect(Scrim)
            drawRoundRect(Color.Black, topLeft = s.topLeft, size = s.size, cornerRadius = corner, blendMode = BlendMode.Clear)
            drawRoundRect(Amber, topLeft = s.topLeft, size = s.size, cornerRadius = corner, style = Stroke(SpotBorder.toPx()))
            val t = pulse?.value
            val ringAlpha = if (t == null) 1f else 0.85f * (1f - t)
            if (ringAlpha > 0.01f) {
                val ring = s.inflate(PulseOutset.toPx())
                scale(if (t == null) 1f else 1f + 0.08f * t, pivot = ring.center) {
                    drawRoundRect(
                        PulseAmber, topLeft = ring.topLeft, size = ring.size, cornerRadius = CornerRadius(PulseRadius.toPx()),
                        style = Stroke(PulseStroke.toPx()), alpha = ringAlpha
                    )
                }
            }
        }

        CoachCard(
            step = step,
            position = position,
            count = count,
            isLast = isLast,
            reduced = reduced,
            onNext = onNext,
            onSkip = onSkip,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = CardMargin)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val card = measurable.measure(constraints)
                    layout(card.width, card.height) {
                        card.place(0, (cardBottom.value - card.height).roundToInt().coerceAtLeast(0))
                    }
                }
        )
    }
}

/**
 * Unde stă cardul (marginea de sus, px în gazdă): „cardul jos” = cel mai de jos spațiu liber dintre ținte (cu inelul lor),
 * lipit de partea de jos a spațiului — așa rămâne pe loc de la un pas la altul și nu acoperă nicio țintă.
 * Fără spațiu liber: imediat sub ținta curentă, altfel deasupra ei, altfel jos de tot.
 */
private fun Density.cardTop(
    hole: Rect,
    obstacles: List<Rect>,
    cardHeight: Float,
    height: Float,
    topSafe: Float,
    bottomSafe: Float
): Float {
    val margin = CardMargin.toPx()
    val reach = (SpotPadding + PulseOutset).toPx() + margin
    val minY = topSafe + margin
    val maxY = height - bottomSafe - margin - cardHeight
    if (maxY <= minY) return max(minY, 0f)
    val blocks = (obstacles + hole)
        .map { (it.top - reach) to (it.bottom + reach) }
        .sortedBy { it.first }
    var lowestFit: Float? = null
    var cursor = minY
    for ((top, bottom) in blocks) {
        val fitTop = min(top - cardHeight, maxY)        // lipit de marginea de jos a spațiului liber
        if (fitTop >= cursor) lowestFit = fitTop
        cursor = max(cursor, bottom)
    }
    if (maxY >= cursor) lowestFit = maxY
    if (lowestFit != null) return lowestFit
    val below = hole.bottom + reach
    if (below <= maxY) return below
    val above = hole.top - reach - cardHeight
    if (above >= minY) return above
    return maxY
}

@Composable
private fun CoachCard(
    step: CoachStep,
    position: Int,
    count: Int,
    isLast: Boolean,
    reduced: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .shadow(elevation = 18.dp, shape = CardShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CardShape)
            .background(Surface1)
            .border(1.dp, CardStroke, CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mascot(state = step.mascot, hat = MascotHat.Helmet, size = 64.dp)
            Spacer(Modifier.width(12.dp))
            Text(step.text, style = CardText, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepDots(count = count, current = position, reduced = reduced)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .height(40.dp)
                    .clip(ButtonShape)
                    .pressable(onSkip)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) { Text("Sari", style = SkipText) }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .height(40.dp)
                    .clip(ButtonShape)
                    .background(AccentGradient)
                    .pressable(onNext)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) { Text(if (isLast) "Am înțeles" else "Înainte", style = NextText) }
        }
    }
}

/** Punctele pașilor: activul e o liniuță amber de 20 dp, celelalte puncte de 6 dp. */
@Composable
private fun StepDots(count: Int, current: Int, reduced: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val on = i == current
            val w by animateDpAsState(
                targetValue = if (on) 20.dp else 6.dp,
                animationSpec = if (reduced) snap() else tween(300, easing = Ease),
                label = "coachDot"
            )
            Box(
                Modifier
                    .size(width = w, height = 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (on) Amber else DotOff)
            )
        }
    }
}
