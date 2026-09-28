package com.forja.app.feature.workout

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color as GfxColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.forja.app.core.data.db.ExerciseEntity
import com.forja.app.core.data.db.PlanEntity
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.music.Music
import com.forja.app.core.music.MusicSource
import com.forja.app.core.music.MusicStarter
import kotlinx.coroutines.delay

/** Spațiul neîntrerupt: „62,5 KG”, „4 SERII” nu se despart la capăt de rând. */
private const val NBSP = '\u00A0'

/** Ghidajul primei vizite: rândul „Muzică”, spus o singură dată. */
private val ANTRENAMENT_STEPS = listOf(
    CoachStep("antrenament.muzica", "Muzica ta pornește odată cu sesiunea.", MascotState.Happy)
)

/** Hub Antrenament: 3 planuri selectabile + lista de azi + „Începe sesiunea". */
@Composable
fun WorkoutScreen(onStartLive: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val vm: WorkoutViewModel = viewModel(viewModelStoreOwner = activity)
    val plans by vm.plans.collectAsState()
    val planIdx by vm.planIdx.collectAsState()
    val exercises by vm.planExercises.collectAsState()
    var editing by remember { mutableStateOf<ExerciseEntity?>(null) }

    // Muzica: comutatorul, lista, discul (ce cântă acum). Accesul se reverifică la întoarcerea din Setări.
    val context = LocalContext.current
    val music by vm.music.collectAsState()
    val track by Music.nowPlaying.collectAsState()
    val start by MusicStarter.state.collectAsState()
    val origin by MusicStarter.origin.collectAsState()
    val queue by MusicStarter.queue.collectAsState()
    var sheetOpen by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refreshMusic() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshMusic() }
    LaunchedEffect(Unit) { Music.ensureStarted(context) }
    val art = track?.art
    val artBitmap = remember(art) { art?.asImageBitmap() }
    val audible by Music.audible.collectAsState()
    val disc = discUi(start, origin == MusicSource.WORKOUT, track, artBitmap, track?.positionMs ?: 0L, queue != null, audible = !music.access && audible)
    fun onMusicSwitch(on: Boolean) {
        vm.setMusicOn(on)
        // Fără acces, „pornit” cere accesul (altfel pornește doar Melodii apreciate).
        if (on && !music.access) {
            try { accessLauncher.launch(Music.accessIntent(context)) } catch (_: Exception) {
                try { accessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
            }
        }
    }

    // Ghidajul primei vizite arată rândul „Muzică”. Rândul stă deasupra pliului (sub citat, și pe S23); doar dacă un font
    // mărit îl împinge în jumătatea de jos, hubul derulează o dată până la el, ca ținta să apară în primele 3 s.
    val scroll = rememberScrollState()
    val guideSeen by remember { Tutorial.seen(context, "antrenament") }.collectAsState(initial = true)
    var musicRowCenter by remember { mutableFloatStateOf(-1f) }
    var viewport by remember { mutableIntStateOf(0) }
    LaunchedEffect(guideSeen, exercises.size, musicRowCenter > 0f && viewport > 0) {
        if (guideSeen || musicRowCenter <= 0f || viewport <= 0 || musicRowCenter < viewport * 0.6f) return@LaunchedEffect
        delay(250)
        val target = (musicRowCenter - viewport * 0.45f).toInt().coerceIn(0, scroll.maxValue)
        if (target > scroll.value) scroll.animateScrollTo(target)
    }

    CoachMarks(screen = "antrenament", steps = ANTRENAMENT_STEPS) {
        WorkoutHubContent(
            plans = plans,
            planIdx = planIdx,
            exercises = exercises,
            music = music,
            disc = disc,
            actions = HubActions(
                onSelectPlan = { vm.selectPlan(it) },
                onEdit = { editing = it },
                onStartFrom = { pos -> vm.startSession(fromExercise = pos); onStartLive() },
                onStart = { vm.startSession(0); onStartLive() },
                onMusicSwitch = ::onMusicSwitch,
                onOpenSheet = { sheetOpen = true }
            ),
            scroll = scroll,
            onMusicRow = { musicRowCenter = it },
            onViewport = { viewport = it }
        )
    }

    if (sheetOpen) {
        WorkoutMusicSheet(
            state = music,
            actions = MusicSheetActions(
                onMix = { vm.setMix(it) },
                onStopAtEnd = { vm.setMusicStopAtEnd(it) },
                onDone = { sheetOpen = false }
            ),
            onDismiss = { sheetOpen = false }
        )
    }

    editing?.let { ex ->
        ExerciseEditSheet(
            exercise = ex,
            onSave = { sets, reps, load -> vm.updateExercise(ex.id, sets, reps, load); editing = null },
            onClose = { editing = null }
        )
    }
}

/** Ce face hubul (planul, creionul, „Începe de aici”, „Începe sesiunea”, rândul „Muzică”). */
data class HubActions(
    val onSelectPlan: (Int) -> Unit = {},
    val onEdit: (ExerciseEntity) -> Unit = {},
    val onStartFrom: (Int) -> Unit = {},
    val onStart: () -> Unit = {},
    val onMusicSwitch: (Boolean) -> Unit = {},
    val onOpenSheet: () -> Unit = {}
)

/**
 * Hubul Antrenament, fără ViewModel (și pentru capturi): antetul, planurile, citatul, rândul „Muzică” (deasupra
 * pliului și pe S23: muzica pornește odată cu sesiunea, deci se vede de la intrare), exercițiile de azi și „Începe
 * sesiunea”. [onMusicRow] = mijlocul rândului „Muzică” în conținut (pentru ghidaj).
 */
@Composable
fun WorkoutHubContent(
    plans: List<PlanEntity>,
    planIdx: Int,
    exercises: List<ExerciseEntity>,
    music: WorkoutMusicState,
    disc: DiscUi,
    actions: HubActions,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    onMusicRow: (Float) -> Unit = {},
    onViewport: (Int) -> Unit = {}
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Surface0)
            .onSizeChanged { onViewport(it.height) }
            .verticalScroll(scroll)
            .statusBarsPadding()
            .padding(bottom = 120.dp)
    ) {
        // Antetul modulului: ștampila postului, numele filei, ordinul scurt.
        ModuleHeader(
            stamp = "INSTRUCȚIE",
            title = "Antrenament",
            order = "Alege planul. Ajustează seriile. Începe sesiunea.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        )
        SectionLabel("Planul tău", Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))

        // Carduri plan 150×188, stroke amber pe selecție, badge ACTIV
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp)) {
            itemsIndexed(plans, key = { _, p -> p.id }) { i, p ->
                val selected = i == planIdx
                val stroke by animateColorAsState(
                    if (selected) Color(0xA66F855A) else Color(0x12FFFFFF),
                    Springs.natural(), label = "stroke"
                )
                val shape = RoundedCornerShape(Radii.card)
                Box(
                    Modifier
                        .padding(end = 12.dp)
                        .size(width = 150.dp, height = 188.dp)
                        .clip(shape)
                        .border(1.5.dp, stroke, shape)
                        .pressable({ actions.onSelectPlan(i) })
                ) {
                    AsyncImage(
                        model = p.cover, contentDescription = p.name,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color(0x1A0A0A0B), 0.5f to Color(0x8C0A0A0B), 1f to Color(0xF20A0A0B)
                                )
                            )
                    )
                    if (selected) {
                        Box(
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(ChipShape)
                                .background(AccentGradient)
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text("ACTIV", style = monoLabel(8, 0.12f).copy(color = OnAccent))
                        }
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        Text(p.name, style = BodyStrong.copy(fontSize = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text(p.meta, style = monoLabel(8, 0.10f).copy(color = TextSecondary))
                    }
                }
            }
        }

        // Un singur citat cald pe ecran — același toată ziua, altul mâine.
        Spacer(Modifier.height(18.dp))
        WarmQuote(Tone.ofDay(Tone.workout), Modifier.padding(horizontal = 20.dp))

        // Muzica sesiunii: sub citat, ca să nu cadă sub pliu (sub cele 4 exerciții nu se vedea pe S23).
        Spacer(Modifier.height(18.dp))
        WorkoutMusicRow(
            state = music,
            disc = disc,
            onToggle = actions.onMusicSwitch,
            onOpenSheet = actions.onOpenSheet,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .onGloballyPositioned { onMusicRow(it.positionInParent().y + it.size.height / 2f) }
        )

        Spacer(Modifier.height(22.dp))
        val plan = plans.getOrNull(planIdx)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel("Azi · ${plan?.name ?: ""}")
            Text(
                "${exercises.size} EXERCIȚII",
                style = monoLabel(9, 0.12f)
            )
        }
        Spacer(Modifier.height(10.dp))

        // Rânduri exercițiu: thumb 54×66, serii×rep×kg, „VIDEO DEMO · LOOP", play
        Column(Modifier.padding(horizontal = 20.dp)) {
            exercises.forEachIndexed { pos, e ->
                ForjaCard(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    padding = 10.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = e.thumb, contentDescription = e.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 54.dp, height = 66.dp)
                                .clip(ThumbShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = BodyStrong.copy(fontSize = 14.sp))
                            Spacer(Modifier.height(3.dp))
                            // Spații neîntrerupte în fiecare grup („2×14 KG” nu se mai rupe): dacă nu încape, rândul se
                            // frânge doar la „·”. Spațierea 0,06 em îl ține pe un rând la 158 dp (S23, 26 de semne).
                            Text(
                                "${e.sets}$NBSP${if (e.sets == 1) "SERIE" else "SERII"}$NBSP×$NBSP${e.reps}${NBSP}REP · " +
                                    e.load.replace(' ', NBSP) + (if (e.loadLabel == "KG") "${NBSP}KG" else ""),
                                style = monoLabel(9, 0.06f).copy(color = TextSecondary),
                                maxLines = 2
                            )
                            Spacer(Modifier.height(3.dp))
                            Text("AJUSTEAZĂ CU CREIONUL", style = monoLabel(8, 0.12f).copy(color = Accent2))
                        }
                        Icon(
                            Icons.Filled.Edit, contentDescription = "Editează",
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Surface2)
                                .border(1.dp, StrokeCardStrong, RoundedCornerShape(10.dp))
                                .pressable({ actions.onEdit(e) })
                                .padding(7.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.PlayArrow, contentDescription = "Începe de aici",
                            tint = OnAccent,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentGradient)
                                .pressable({ actions.onStartFrom(pos) })
                                .padding(6.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = "Începe sesiunea",
            onClick = actions.onStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        )
    }
}

/** Editează seriile, repetările și greutatea unui exercițiu, înainte de sesiune. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseEditSheet(
    exercise: com.forja.app.core.data.db.ExerciseEntity,
    onSave: (sets: Int, reps: Int, load: String) -> Unit,
    onClose: () -> Unit
) {
    var sets by remember { mutableStateOf(exercise.sets) }
    var reps by remember { mutableStateOf(exercise.reps) }
    var load by remember { mutableStateOf(exercise.load) }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface1, shape = SheetShape
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(exercise.name, style = TitleModule.copy(fontSize = 20.sp))
            Spacer(Modifier.height(4.dp))
            Text("Pune-ți valorile. Se salvează în plan.", style = BodySmall)
            Spacer(Modifier.height(18.dp))

            @Composable
            fun stepper(label: String, value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SectionLabel(label)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SecondaryButton("−", onClick = onMinus, padV = 8.dp)
                        Text("$value", style = heroNumeral(30), modifier = Modifier.padding(horizontal = 16.dp))
                        SecondaryButton("+", onClick = onPlus, padV = 8.dp)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                stepper("Serii", sets, { sets = (sets - 1).coerceAtLeast(1) }, { sets = (sets + 1).coerceAtMost(20) })
                stepper("Repetări", reps, { reps = (reps - 1).coerceAtLeast(1) }, { reps = (reps + 1).coerceAtMost(100) })
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Greutate / sarcină")
            Spacer(Modifier.height(8.dp))
            TextField(
                value = load,
                onValueChange = { load = it },
                singleLine = true,
                placeholder = { Text("ex: 62,5 · corp · 2×14", style = BodySmall) },
                textStyle = BodyStrong.copy(fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth().clip(SecondaryShape),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = Accent2,
                    focusedIndicatorColor = GfxColor.Transparent, unfocusedIndicatorColor = GfxColor.Transparent
                )
            )
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Salvează", onClick = { onSave(sets, reps, load.trim()) }, modifier = Modifier.fillMaxWidth())
        }
    }
}
