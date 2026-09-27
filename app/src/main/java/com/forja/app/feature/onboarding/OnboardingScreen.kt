package com.forja.app.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.ForjaApp
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import kotlinx.coroutines.launch
import kotlin.math.abs

/** O pagină a prezentării: un „ordin de zi” — comandă scurtă + un citat cald. */
internal data class OrdinPage(
    val nr: String,          // "ORDIN DE ZI NR. 1"
    val kicker: String,      // "ZORI · MIȘCARE"
    val title: String,
    val order: String,       // ordinul — imperativ, fără semne de exclamare
    val quote: String,       // citatul cald
    val quoteBy: String,     // atribuirea
    val video: String,
    val poster: String
)

/** Tot textul prezentării, într-un singur loc — ușor de revizuit. */
internal object IntroCopy {
    const val NEXT = "Înainte"
    const val START = "Începe"
    const val SKIP = "Sari"

    val pages: List<OrdinPage> = listOf(
        OrdinPage(
            nr = "ORDIN DE ZI NR. 1",
            kicker = "ZORI · MIȘCARE",
            title = "Te ridici. Te miști.",
            order = "Fiecare zi începe cu un pas. FORJA îți măsoară antrenamentul și alergarea, pe hartă, lângă prietenii tăi. Fără scuze, fără filtre.",
            quote = "„Disciplina e libertate câștigată dimineață.”",
            quoteBy = "— jurnal de front, FORJA",
            video = "https://v.ftcdn.net/10/70/20/79/700_F_1070207993_vIfgD2rf5RWK9Sz68WonFo6D78QWfBWy_ST.mp4",
            poster = "https://t4.ftcdn.net/jpg/04/30/39/81/500_F_430398119_8X2LMR6p3pWYrpsvH3DYgYUz32PfnxXl.jpg"
        ),
        OrdinPage(
            nr = "ORDIN DE ZI NR. 2",
            kicker = "RAȚIE · NUTRIȚIE",
            title = "Mănânci real. Vezi clar.",
            order = "Scanezi codul sau fotografiezi farfuria — FORJA îți spune exact ce ai în ea. Tu confirmi, tu decizi. Nimic nu se ascunde.",
            quote = "„Corpul e casa în care locuiești toată viața. Ai grijă de ea cu blândețe.”",
            quoteBy = "— gândul zilei",
            video = "https://v.ftcdn.net/01/94/92/63/700_F_194926378_Lo30ngI1RhPKxx8MKl8clLpbq4P304fB_ST.mp4",
            poster = "https://t4.ftcdn.net/jpg/05/03/88/17/500_F_503881704_hyhi1pOJrBNqQ0dJqK1Qceno2pa8KWiJ.jpg"
        ),
        OrdinPage(
            nr = "ORDIN DE ZI NR. 3",
            kicker = "STINGEREA · SOMN",
            title = "Te odihnești ca un soldat.",
            order = "Somnul se măsoară discret: mișcarea pe telefon, sunetul ascultat de model pe server. Dimineața primești raportul și te trezești în fereastra potrivită.",
            quote = "„Odihna nu e slăbiciune. E muniția de mâine.”",
            quoteBy = "— regula nr. 3",
            video = "https://v.ftcdn.net/05/12/88/79/700_F_512887976_190EN7woFkvAws5F4qzRxGMIOuIjvyPY_ST.mp4",
            poster = "https://t3.ftcdn.net/jpg/04/70/98/78/500_F_470987805_jsREzUZZZNUDZ56fG4J9Cpz4UquN6zJg.jpg"
        ),
        OrdinPage(
            nr = "ORDIN DE ZI NR. 4",
            kicker = "APEL · CAMARAZI",
            title = "Nu ești singur pe drum.",
            order = "Prietenii tăi te văd pe hartă, îți trimit energie, te trag după ei. Focusul îți apără timpul. Tu ții rândul.",
            quote = "„Un om singur ajunge departe. Împreună ajungeți acasă.”",
            quoteBy = "— FORJA · LIVE IT",
            video = "https://v.ftcdn.net/04/99/13/67/700_F_499136769_X4Pfv9UFpmLtXcXu0JLdSo80FTPH2BGx_ST.mp4",
            poster = "https://t3.ftcdn.net/jpg/10/16/02/48/500_F_1016024842_sVPfKb4a4gZkZ7XjEjnGtdkeYz1eF2Gz.jpg"
        )
    )
}

/** Prezentarea de început: patru ordine de zi, cu glisare, paralaxă și dezvăluire în trepte. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val pages = IntroCopy.pages
    val last = pages.size - 1
    val pager = rememberPagerState(pageCount = { pages.size })
    var finishing by remember { mutableStateOf(false) }

    fun finish() {
        if (finishing) return
        finishing = true
        scope.launch {
            app.prefs.setIntroSeen()
            onFinished()
        }
    }

    fun goTo(page: Int) {
        scope.launch { pager.animateScrollToPage(page.coerceIn(0, last)) }
    }

    BackHandler(enabled = pager.currentPage > 0) { goTo(pager.currentPage - 1) }

    Box(Modifier.fillMaxSize().background(Surface0)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 0,
            key = { it }
        ) { page ->
            val p = pages[page]
            val isCurrent = pager.settledPage == page

            Box(Modifier.fillMaxSize()) {
                VideoSurface(
                    url = p.video, posterUrl = p.poster,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Distanța (în pagini, cu semn) față de poziția curentă — citită aici, în faza de desen,
                            // ca glisarea să nu recompună pagina la fiecare cadru.
                            val offset = pager.getOffsetDistanceInPages(page)
                            // Fundalul se mișcă mai încet decât pagina; mărim puțin ca să nu se vadă marginile.
                            translationX = offset * size.width * 0.35f
                            val sc = 1f + 0.08f * abs(offset)
                            scaleX = sc; scaleY = sc
                        }
                )
                BoxScopeBottomScrim()
                TopScrim()

                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 132.dp)
                        .graphicsLayer {
                            val offset = pager.getOffsetDistanceInPages(page)
                            alpha = 1f - abs(offset).coerceIn(0f, 1f)
                            translationX = -offset * size.width * 0.12f
                        }
                ) {
                    Reveal(visible = isCurrent, index = 0, key = page) { StampLabel(p.nr) }
                    Spacer(Modifier.height(14.dp))
                    Reveal(visible = isCurrent, index = 1, key = page) {
                        Text(p.kicker, style = monoLabel(10, 0.16f).copy(color = Accent2))
                    }
                    Spacer(Modifier.height(8.dp))
                    Reveal(visible = isCurrent, index = 2, key = page) {
                        Text(p.title, style = TitleOnboarding)
                    }
                    Spacer(Modifier.height(10.dp))
                    Reveal(visible = isCurrent, index = 3, key = page) {
                        Text(
                            p.order,
                            style = Body.copy(fontSize = 15.sp, lineHeight = 20.sp, color = TextPrimary.copy(alpha = 0.9f))
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Reveal(visible = isCurrent, index = 4, key = page) {
                        Column {
                            Text(
                                p.quote,
                                style = TitleModule.copy(fontSize = 18.sp, lineHeight = 23.sp, fontStyle = FontStyle.Italic)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(p.quoteBy, style = BodyTiny.copy(color = TextDim))
                        }
                    }
                }
            }
        }

        // „Sari” — sus-dreapta, doar până la ultima pagină
        AnimatedVisibility(
            visible = pager.currentPage < last,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = fadeIn(), exit = fadeOut()
        ) {
            Text(
                IntroCopy.SKIP,
                style = BodyStrong.copy(color = TextSecondary),
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(20.dp)
                    .pressable({ finish() })
            )
        }

        // Blocul fix de jos — nu glisează cu paginile
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
        ) {
            PageDots(count = pages.size, current = pager.currentPage)
            Spacer(Modifier.height(18.dp))
            PrimaryButton(
                text = if (pager.currentPage == last) IntroCopy.START else IntroCopy.NEXT,
                onClick = { if (pager.currentPage < last) goTo(pager.currentPage + 1) else finish() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
