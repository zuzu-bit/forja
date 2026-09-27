package com.forja.app.core.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forja.app.core.designsystem.Accent2
import com.forja.app.core.designsystem.Body
import com.forja.app.core.designsystem.TextPrimary
import com.forja.app.core.designsystem.TextSecondary
import com.forja.app.core.designsystem.TitleModule
import com.forja.app.core.designsystem.monoLabel
import java.util.Calendar

/**
 * Vocea FORJA — „armată serioasă + citate calde”.
 *
 * Reguli (aceleași ca în ordinele de zi de la început):
 *  - ștampila (StampLabel) numește postul: INSTRUCȚIE, RAȚIE, STINGEREA, POST DE PAZĂ, JURNAL DE MARȘ…
 *  - titlul are două bătăi scurte: „Te ridici. Te miști.”
 *  - ordinul e la imperativ, scurt, fără semne de exclamare, fără „Hai să…”, fără emoji
 *  - un singur citat cald, cu atribuire de front: „— jurnal de front, FORJA”, „— gândul zilei”, „— regula nr. N”
 *
 * Citatul e același toată ziua (nu sare la fiecare deschidere) și altul mâine — vezi [Tone.ofDay].
 */
object Tone {
    data class Quote(val text: String, val by: String)

    private const val FRONT = "— jurnal de front, FORJA"
    private const val MARCH = "— jurnal de marș, FORJA"
    private const val DAY = "— gândul zilei"
    private const val LIVE = "— FORJA · LIVE IT"

    val workout = listOf(
        Quote("Disciplina e libertate câștigată dimineață.", FRONT),
        Quote("Nu numeri repetările. Le faci până contează.", "— regula nr. 1"),
        Quote("Corpul ține minte fiecare zi în care nu ai renunțat.", DAY),
        Quote("Greutatea de azi e ușurința de mâine.", FRONT),
        Quote("Nu trebuie să fii extraordinar ca să începi. Trebuie să începi ca să fii extraordinar.", LIVE),
        Quote("Antrenamentul bun se termină cu tine mai calm, nu mai gol.", DAY)
    )

    val nutrition = listOf(
        Quote("Corpul e casa în care locuiești toată viața. Ai grijă de ea cu blândețe.", DAY),
        Quote("Rația bună nu e pedeapsă. E respect.", "— regula nr. 2"),
        Quote("Mănânci ca să duci ziua, nu ca să o uiți.", FRONT),
        Quote("Ce pui în farfurie pui și în picioare.", DAY),
        Quote("Sinceritatea începe la cântar și se termină la masă.", FRONT)
    )

    val sleep = listOf(
        Quote("Odihna nu e slăbiciune. E muniția de mâine.", "— regula nr. 3"),
        Quote("Stingerea e un ordin, nu o sugestie.", FRONT),
        Quote("Somnul repară ce ziua a cerut.", DAY),
        Quote("Nimeni nu câștigă un război cu ochii roșii.", FRONT)
    )

    val focus = listOf(
        Quote("Timpul tău e teren. Îl aperi sau îl pierzi.", "— regula nr. 4"),
        Quote("Atenția e singura armă care se ascuțește când o folosești.", DAY),
        Quote("O oră fără zgomot valorează cât o zi cu el.", FRONT),
        Quote("Telefonul așteaptă. Viața nu.", DAY)
    )

    val breath = listOf(
        Quote("Respiri rar, gândești clar.", "— regula nr. 5"),
        Quote("Înainte de orice luptă, un om liniștit.", FRONT),
        Quote("Aerul e gratuit. Liniștea se câștigă.", DAY)
    )

    val activities = listOf(
        Quote("Fiecare tură e o pagină. Le citești mai târziu, cu mândrie.", MARCH),
        Quote("Nu drumul e greu. Greu e primul pas de pe canapea.", DAY),
        Quote("Kilometrii nu mint și nu uită.", MARCH)
    )

    val map = listOf(
        Quote("Un om singur ajunge departe. Împreună ajungeți acasă.", LIVE),
        Quote("Orașul nu se vede din casă.", MARCH),
        Quote("Terenul se cunoaște cu pasul, nu cu degetul.", DAY)
    )

    val cleanup = listOf(
        Quote("Ordinea în lucruri face loc ordinii în cap.", "— regula nr. 6"),
        Quote("Păstrezi ce merită. Restul pleacă fără regrete.", FRONT),
        Quote("Un rucsac ușor duce departe.", MARCH)
    )

    val general = listOf(
        Quote("Disciplina cântărește kilograme; regretul cântărește tone.", DAY),
        Quote("Un pas mic azi bate un plan mare mâine.", FRONT),
        Quote("Corpul realizează ce mintea crede.", DAY),
        Quote("Nu trebuie să fii extraordinar ca să începi. Trebuie să începi ca să fii extraordinar.", LIVE),
        Quote("Un om singur ajunge departe. Împreună ajungeți acasă.", LIVE)
    )

    /** Citatul zilei dintr-o listă: stabil pe parcursul zilei, altul mâine. `salt` separă modulele care împart ziua. */
    fun ofDay(pool: List<Quote>, salt: Int = 0, now: Long = System.currentTimeMillis()): Quote {
        if (pool.isEmpty()) return Quote("", "")
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val day = cal.get(Calendar.YEAR) * 366 + cal.get(Calendar.DAY_OF_YEAR)
        return pool[Math.floorMod(day + salt, pool.size)]
    }

    /** Salut sobru, după oră: „Raport de dimineață, Ana.” / „Raport de seară, Ana.” */
    fun report(firstName: String, hour: Int): String {
        val moment = when (hour) {
            in 5..11 -> "dimineață"
            in 12..17 -> "amiază"
            else -> "seară"
        }
        val name = firstName.trim()
        return if (name.isEmpty()) "Raport de $moment." else "Raport de $moment, $name."
    }
}

/**
 * Antetul unui modul: ștampilă + titlu + (opțional) ordinul zilei, cu apariție în trepte.
 * `reveal = false` îl desenează direct (liste lungi, antete care revin des în ecran).
 */
@Composable
fun ModuleHeader(
    stamp: String,
    title: String,
    modifier: Modifier = Modifier,
    order: String? = null,
    color: Color = Accent2,
    titleStyle: TextStyle = TitleModule,
    orderColor: Color = TextSecondary,
    reveal: Boolean = true
) {
    Column(modifier) {
        Staged(reveal, 0) { StampLabel(stamp, color = color, rotationDeg = -4f, appear = reveal) }
        Spacer(Modifier.height(10.dp))
        Staged(reveal, 1) { Text(title, style = titleStyle) }
        if (!order.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Staged(reveal, 2) { Text(order, style = Body.copy(color = orderColor)) }
        }
    }
}

/** Citat cald: ghilimele românești, atribuirea în mono, sub el. */
@Composable
fun WarmQuote(
    quote: Tone.Quote,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    byColor: Color = TextSecondary,
    fontSize: Int = 16
) {
    if (quote.text.isBlank()) return
    Column(modifier) {
        Text(
            "„${quote.text}”",
            style = TitleModule.copy(fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp, color = color)
        )
        if (quote.by.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(quote.by, style = monoLabel(9, 0.12f).copy(color = byColor))
        }
    }
}

@Composable
private fun Staged(reveal: Boolean, index: Int, content: @Composable () -> Unit) {
    if (reveal) Reveal(index = index) { content() } else content()
}
