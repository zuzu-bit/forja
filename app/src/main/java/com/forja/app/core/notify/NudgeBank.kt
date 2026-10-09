package com.forja.app.core.notify

import com.forja.app.core.notify.NudgeContext.Bedtime
import com.forja.app.core.notify.NudgeContext.Comeback
import com.forja.app.core.notify.NudgeContext.Evening
import com.forja.app.core.notify.NudgeContext.FocusDone
import com.forja.app.core.notify.NudgeContext.FriendNear
import com.forja.app.core.notify.NudgeContext.MealLog
import com.forja.app.core.notify.NudgeContext.Midday
import com.forja.app.core.notify.NudgeContext.Milestone
import com.forja.app.core.notify.NudgeContext.Morning
import com.forja.app.core.notify.NudgeContext.NewFriend
import com.forja.app.core.notify.NudgeContext.NewPlace
import com.forja.app.core.notify.NudgeContext.Permission
import com.forja.app.core.notify.NudgeContext.SleepReport
import com.forja.app.core.notify.NudgeContext.StreakRisk
import com.forja.app.core.notify.NudgeContext.SyncOngoing
import com.forja.app.core.notify.NudgeContext.SyncStopped
import com.forja.app.core.notify.NudgePose.Angry
import com.forja.app.core.notify.NudgePose.Happy
import com.forja.app.core.notify.NudgePose.Sorry
import com.forja.app.core.notify.NudgePose.Talking
import com.forja.app.core.notify.NudgePose.Thinking
import com.forja.app.core.notify.NudgePose.Wink

/*
 * Banca finală „Casca — camaradul care îți știe ziua” (notifications-design.md §D, curățată după „Verificare
 * adversarială”): coloana coach, deschiderile friend, continuitatea mascotei din duo.
 *
 * Reguli pe care testele le verifică (NudgeBankLintTest, portul lui check_bank.py):
 *  - zero „!”, zero emoji, ș/ț cu virgulă, fără „Bravo/Super/Wow/Felicitări/Ups/Hai să/Descoperă/călătoria”;
 *  - titlu ≤ 40 (notificarea permanentă ≤ 28), text ≤ 90 cu valori lungi; cel mult un „?”;
 *  - fără acord cu genul cititorului sau al prietenului; fără glume de supraveghere;
 *  - orice text despre somn spune „estimat”; mesele fără „prea/peste/ai sărit/ai depășit/compensezi”.
 * Pluralul se scrie în șablon: {serie_zile|zi|zile} → „1 zi”, „3 zile”, „20 de zile”. Ordinalul: {locuri@m} → „al 25-lea”.
 */
object NudgeBank {

    /** Fereastra notificării permanente: 0 noapte (21–08), 1 dimineață (08–12), 2 zi (12–17), 3 seară (17–21). */
    fun slot(hour: Int): Int = when (hour) {
        in 8..11 -> 1
        in 12..16 -> 2
        in 17..20 -> 3
        else -> 0
    }

    private fun s(d: NudgeData) = slot(d.hour)
    /** Mesajele despre mese: niciodată după 21:30 (§D.13). */
    private fun mealHours(d: NudgeData) = NudgeRules.mealTime(d.hour, d.minute)
    /** Seria în pericol despre care avem voie să vorbim acum (cea de mese tace după 21:30). */
    private fun riskNow(d: NudgeData): Streak? = d.risk?.takeIf { it.kind != StreakKind.Meals || mealHours(d) }
    private fun day(d: NudgeData) = s(d) in 1..3

    // ───────────── 1. Notificarea permanentă de sincronizare (titlu ≤ 28, fără „acum”, fără prieteni) ─────────────
    // Rândul onest (ce urcă, „Oprește”) NU e aici: îl pune SyncCopy în fiecare variantă, vizibil și restrâns.
    // Replica stă ore întregi pe ecran (se rescrie cel mult o dată la 3 h): orice număr al zilei de azi poartă ora la
    // care a fost numărat („la 14:05”), ca să rămână adevărat și după ce mai notezi ceva (corectura 4).
    val sync = listOf(
        Template("S-a", SyncOngoing, "Bună dimineața.", "Ai dormit {somn_h}, estimat. Primul pas de azi decide restul.", Happy) { s(it) == 1 },
        Template("S-b", SyncOngoing, "{serie_zile|zi|zile} la rând.", "Ieri ai ținut linia. Azi o ții cu un singur gest mic.", Happy) { s(it) == 1 },
        Template("S-m", SyncOngoing, "Ieri: {km_ieri} km.", "Corpul ține minte drumul. Azi mai e o stradă care te așteaptă.", Wink) { s(it) == 1 },
        Template("S-c", SyncOngoing, "{km} km azi, la {ora}.", "Mai ai {ramas} km din ținta săptămânii. Merge și pe jos, pe bucăți.", Happy) { s(it) == 2 },
        Template("S-c2", SyncOngoing, "{km} km azi, la {ora}.", "Fiecare bucată de drum rămâne pe hartă. Și asta contează.", Happy) { s(it) == 2 },
        Template("S-h", SyncOngoing, "{copaci|copac|copaci} azi, la {ora}.", "{focus_min} min de focus în pădurea ta. Atenția se ascute când o folosești.", Happy) { s(it) in 2..3 },
        Template("S-i", SyncOngoing, "{mese_azi|masă notată|mese notate} azi, la {ora}.", "Sinceritatea din farfurie e tot antrenament. Tu îl faci.", Happy) { s(it) == 2 && it.mealsToday >= 2 },
        Template("S-d", SyncOngoing, "Loc nou: {loc}.", "Ai stat acolo {ore_stat}. Harta ta a crescut cu un punct.", Wink) { s(it) in 2..3 && it.newPlaceToday != null },
        Template("S-d2", SyncOngoing, "Un loc nou azi.", "Ai stat acolo {ore_stat}. Dă-i un nume când ai un minut.", Happy) {
            s(it) in 2..3 && it.newPlaceToday?.name.isNullOrBlank() && it.newPlaceToday != null
        },
        Template("S-f", SyncOngoing, "Tura de seară, {nume}.", "Până la {ora}: {bilant}. Ziua se scrie.", Happy) { s(it) == 3 },
        Template("S-f2", SyncOngoing, "Tura de seară.", "Până la {ora}: {bilant}. Ziua se scrie.", Happy) { s(it) == 3 },
        Template("S-j", SyncOngoing, "{serie_zile|zi|zile} la rând.", "Seria se ține cu un gest mic înainte de culcare. Unul ajunge.", Talking) {
            s(it) == 3 && (it.longest?.current ?: 0) >= 3
        },
        Template("S-k", SyncOngoing, "{antrenament}: bifat.", "Corpul reține efortul, nu doar rezultatul. Seara e pentru odihnă.", Happy) { s(it) == 3 },
        // Noaptea: fără date, fără „veghează” (serviciul urcă locația și noaptea — nu sună a supraveghere).
        Template("S-n1", SyncOngoing, "Liniște pe front.", "Odihna face parte din antrenament. Corpul se repară în somn.", Thinking, reserve = true) { s(it) == 0 },
        Template("S-n2", SyncOngoing, "Postul e în repaus.", "Nicio misiune urgentă. Telefonul poate sta deoparte.", Talking, reserve = true) { s(it) == 0 },
        Template("F1", SyncOngoing, "Postul e ocupat.", "Un pas mic azi bate un plan mare mâine.", Talking, reserve = true) { day(it) },
        Template("F3", SyncOngoing, "Ziua încă se scrie.", "Un pas, o masă, o respirație. Oricare dintre ele contează.", Thinking, reserve = true) { day(it) },
        Template("F4", SyncOngoing, "Nu trebuie să fie mult.", "Trebuie doar să fie azi. Restul vine de la sine.", Happy, reserve = true) { day(it) }
    )

    // ───────────── 2. Dimineața (08:00–10:30) ─────────────
    val morning = listOf(
        Template("2.1", Morning, "Prima veste de azi.", "Ai dormit {somn_h}, estimat. Ziua asta pornește cu combustibil.", Happy) {
            (it.lastNight?.minutes ?: 0) >= 360
        },
        Template("2.2", Morning, "Noapte scurtă, zi blândă.", "Doar {somn_h}, estimat. Azi ținta e mersul, nu recordul. Apă întâi.", Thinking) {
            (it.lastNight?.minutes ?: Int.MAX_VALUE) < 360
        },
        Template("2.3", Morning, "{serie_zile|zi|zile} la rând. Linia ține.", "Ieri ai bifat. Azi repeți un singur gest și seria merge mai departe.", Happy),
        Template("2.5", Morning, "Ieri ai dus {km_ieri} km.", "Azi nu trebuie mai mult. Doar încă un drum al tău.", Happy) { it.kmYesterday >= 5.0 },
        Template("2.6", Morning, "{prieten} e deja pe drum.", "Vezi pe hartă unde a ajuns și ieși și tu când poți.", Wink) { it.friendMoving != null },
        Template("2.8", Morning, "Săptămâna e o pagină goală.", "Primul rând îl scrii azi. Un singur pas ajunge pentru început.", Happy) { it.dayOfWeek == 1 },
        Template("2.9", Morning, "Weekend. Teren liber.", "Fără program. Alege o stradă pe care n-ai mers și pune-o pe hartă.", Wink) { it.isWeekend },
        Template("2.w", Morning, "Dimineață de iarnă.", "Zece minute de mers și tot orașul pare al tău. Ia fularul.", Wink) { it.isWinter },
        Template("2.v", Morning, "Dimineață de vară.", "Aerul e cel mai bun înainte de căldură. Zece minute afară îl prind.", Wink) { it.isSummer },
        Template("5.8", Morning, "Seria s-a oprit la {serie_veche|zi|zile}.", "Nimic din ce ai făcut nu s-a pierdut. Ziua 1 începe azi.", Sorry) { it.broke != null },
        Template("N2", Morning, "O tură scurtă ajunge.", "Corpul ține minte fiecare pas.", Happy, reserve = true),
        Template("N5", Morning, "Scuza de azi e mai mică decât tine.", "Un pas, acum. Restul vine după el.", Wink, reserve = true),
        Template("N10", Morning, "Un minut azi.", "Pentru somn, masă sau mișcare. Tot contează.", Talking, reserve = true),
        Template("2.r1", Morning, "Bună dimineața, {nume}.", "Apă, lumină, zece pași afară. Restul zilei se aliniază după ei.", Happy, reserve = true),
        Template("2.r0", Morning, "Bună dimineața.", "Apă, lumină, zece pași afară. Restul zilei se aliniază după ei.", Happy, reserve = true)
    )

    // ───────────── 3. Prânzul (12:00–14:30) + notarea mesei, fără rușine ─────────────
    val midday = listOf(
        Template("3.1", Midday, "Pauza de prânz.", "Ți-au rămas {kcal_ramase} kcal. Alegi tu, eu doar țin socoteala.", Thinking),
        Template("3.2", Midday, "O poză cât mănânci.", "Nu contează cât, contează că ții firul zilei. O secundă.", Talking) { !it.lunchLogged },
        Template("3.3", Midday, "Nicio tură notată azi.", "Zece minute de mers după masă limpezesc capul.", Wink) { it.kmToday < 0.1 },
        Template("3.4", Midday, "{km} km până la prânz.", "Ritm bun. O tură scurtă diseară și ziua e rotundă.", Happy) { it.kmToday >= 1.0 },
        Template("3.5", Midday, "{serie_mese|zi|zile} de mese notate.", "Prânzul de azi ține seria întreagă. O poză și gata.", Happy) { it.mealsToday == 0 },
        Template("3.6", Midday, "E vară afară.", "Masa afară, pe o bancă, e altă masă. Ieși cinci minute.", Wink) { it.isSummer },
        Template("13.5", Midday, "Mâncarea e doar mâncare.", "O notezi ca să vezi tiparul, nu ca să te judeci. Atât.", Thinking) { !it.lunchLogged },
        Template("N1", Midday, "Cinci minute de repaus.", "Deschide FORJA și respiră rar. Restul așteaptă.", Thinking, reserve = true),
        Template("N3", Midday, "Notează masa de azi.", "Sinceritatea începe în farfurie.", Talking, reserve = true) { !it.lunchLogged },
        Template("N7", Midday, "O apă, o respirație.", "Un gând limpede. Le ai pe toate în FORJA.", Thinking, reserve = true),
        Template("N9", Midday, "Mândria de diseară.", "Se clădește din alegerea de acum.", Wink, reserve = true),
        Template("3.r1", Midday, "Jumătatea zilei.", "O apă, un pas, o respirație. Reîncepi de aici, fără grabă.", Thinking, reserve = true),
        Template("3.r2", Midday, "Pauză de amiază.", "Ridică-te, umerii jos, trei respirații lungi. Apoi înapoi la post.", Talking, reserve = true)
    )

    // ───────────── 4. Seara, bilanțul (19:00–21:45) ─────────────
    private val eveningVoices = mapOf(
        Voice.Sergent to "Azi: {bilant}. Raport încheiat.",
        Voice.Antrenor to "Azi: {bilant}. Mâine urcăm puțin.",
        Voice.Ghid to "Azi: {bilant}. Detaliile sunt în Panou."
    )
    val evening = listOf(
        Template("4.1", Evening, "Raport de seară, {nume}.", "Azi: {bilant}. Ziua s-a scris.", Happy, voices = eveningVoices),
        Template("4.1b", Evening, "Raport de seară.", "Azi: {bilant}. Ziua s-a scris.", Happy, voices = eveningVoices),
        Template("4.2", Evening, "Peste media ta.", "{km} km azi, media ta e {km_medie} km. Picioarele țin minte asta.", Happy) {
            it.kmAvg7 >= 0.5 && it.kmToday > it.kmAvg7 * 1.1
        },
        Template("4.3", Evening, "Ziua nu s-a terminat.", "Mai e timp. Cinci minute de mers cu GO sau o masă notată o închid.", Thinking) { it.emptyDay && mealHours(it) },
        Template("4.4", Evening, "Azi: un loc nou pe hartă.", "{loc} e {locuri@m} loc de pe harta ta. Fiecare are o zi în spate.", Wink) { it.newPlaceToday != null },
        Template("4.6", Evening, "Seria: {serie_zile|zi|zile}.", "Încă o zi pusă în raft. Mâine se adaugă una, nu toate deodată.", Happy) {
            it.longest?.let { l -> l.current >= 3 && l.doneToday } == true
        },
        Template("4.7", Evening, "Ai închis {antrenament}.", "{seturi|set dus|seturi duse}. Corpul reține efortul, nu doar rezultatul.", Happy),
        Template("13.3", Evening, "Rația de seară.", "Notează cina cum a fost. Aici nu există mese bune sau rele, doar notate.", Talking) {
            !it.dinnerLogged && it.hour < 21
        },
        Template("13.4", Evening, "{serie_mese|zi|zile} de mese.", "O poză diseară și seria rămâne întreagă. Doar una.", Wink) {
            it.mealsToday == 0 && it.streak(StreakKind.Meals)?.current == 2 && mealHours(it)
        },
        Template("N4", Evening, "Somnul bun începe de cu seară.", "Pregătește stingerea în FORJA. Dimineața o simți.", Talking, reserve = true),
        Template("N8", Evening, "Progresul e suma zilelor mici.", "Azi e una dintre ele.", Happy, reserve = true),
        Template("4.r1", Evening, "Ziua se închide.", "Ce ai făcut azi rămâne făcut. Pregătește stingerea, mâine continui.", Talking, reserve = true),
        Template("4.r2", Evening, "Seara e a ta.", "Pune telefonul jos după asta. Ziua s-a scris deja.", Talking, reserve = true)
    )

    // ───────────── 5. Seria în pericol (≥ 19:00, serie ≥ 3, azi nimic; o dată pe zi; seria cea mai lungă) ─────────────
    // „Angry” o singură dată în toată banca: seria de instrucție, vocea Sergent, în glumă. Niciodată la mâncare sau corp.
    private fun risk(kind: StreakKind): (NudgeData) -> Boolean = { it.risk?.kind == kind }
    val streakRisk = listOf(
        Template("R3", StreakRisk, "15 minute. Atât cer.", "Nu tot planul. Doar prima parte din {plan}. Seria se mulțumește cu puțin.", Thinking, cond = risk(StreakKind.Workout)),
        Template("5.2", StreakRisk, "Casca e puțin încruntată.", "{serie_antrenament|zi|zile} de instrucție și azi nimic încă. Zece genuflexiuni o salvează.", Angry) {
            it.risk?.kind == StreakKind.Workout && it.voice == Voice.Sergent
        },
        Template("5.1", StreakRisk, "Seria de instrucție: {serie_antrenament|zi|zile}.", "Nu trebuie o sesiune mare. Zece minute țin firul aprins.", Talking, cond = risk(StreakKind.Workout)),
        Template(
            "R4", StreakRisk, "Rația: seria de {serie_mese|zi|zile}.", "O singură masă notată o ține. Orice masă. Nu judec farfuria.", Talking,
            voices = mapOf(
                Voice.Sergent to "O masă. O poză. Seria rămâne.",
                Voice.Antrenor to "Poză, notat, serie salvată. Mergem.",
                Voice.Ghid to "Notezi orice masă de azi și seria continuă. Estimarea o corectezi tu."
            ),
            cond = { risk(StreakKind.Meals)(it) && mealHours(it) }
        ),
        Template("R5", StreakRisk, "O poză ține seria.", "O farfurie fotografiată și seria de {serie_mese|zi|zile} merge mai departe.", Wink) {
            risk(StreakKind.Meals)(it) && mealHours(it)
        },
        // Seria de mers se face doar din turele înregistrate cu GO (ActivityEntity): textul nu promite mai mult.
        Template("R8", StreakRisk, "Seria de mers cere puțin.", "{serie_mers|zi|zile} la rând. Un tur de bloc cu GO pornit o ține. Nu cere kilometri.", Thinking, cond = risk(StreakKind.Walk)),
        Template("5.5", StreakRisk, "Drumul de azi e încă liber.", "Seria de mers: {serie_mers|zi|zile}. O tură scurtă cu GO, până la stingere, o ține.", Talking, cond = risk(StreakKind.Walk)),
        // „Cinci minute” nu e despre mese; seria de mese are R4/R5 și tace după 21:30.
        Template("5.6", StreakRisk, "Seara ține seria.", "{serie_risc|zi|zile} nu se pierd pentru o seară. Cinci minute. Atât.", Thinking) {
            it.risk?.let { r -> r.kind != StreakKind.Meals && r.current >= 7 } == true && it.hour >= 21
        },
        Template("5.7", StreakRisk, "Seria ta atârnă de un gest.", "Nu trebuie o zi mare. Trebuie o zi care contează. Una mică ajunge.", Thinking, reserve = true) { riskNow(it) != null }
    )

    // ───────────── 6. Praguri de serie (3/7/14/30/50/100/365; gradul doar în text) ─────────────
    private fun at(n: Int): (NudgeData) -> Boolean = { it.milestone?.current == n }
    val milestones = listOf(
        Template("6.1", Milestone, "Trei zile la rând.", "Primele trei sunt cele mai grele. Tu le ai deja. Grad: Recrut.", Happy, cond = at(3)),
        Template("6.2", Milestone, "O săptămână întreagă.", "Șapte zile {tip_serie}. Nu mai e noroc, e un obicei. Grad: Soldat.", Happy, cond = at(7)),
        Template("6.3", Milestone, "Paisprezece zile. Linia ține.", "Corpul ține minte fiecare zi în care nu ai renunțat. Grad: Fruntaș.", Happy, cond = at(14)),
        Template("6.4", Milestone, "30 de zile. Misiune îndeplinită.", "O lună {tip_serie}. E felul tău de a trăi. Grad: Caporal.", Wink, cond = at(30)),
        Template("6.5", Milestone, "50 de zile {tip_serie}.", "Jumătate de drum spre o sută. Nimeni nu te grăbește.", Happy, cond = at(50)),
        Template("6.6", Milestone, "100 de zile. Misiune îndeplinită.", "O sută de zile {tip_serie}. Puțini ajung aici. Tu ai ajuns. Grad: Sergent.", Happy, cond = at(100)),
        Template("6.8", Milestone, "Un an întreg.", "Trei sute șaizeci și cinci de zile. Disciplina e libertate câștigată dimineață.", Happy, cond = at(365)),
        Template("6.7", Milestone, "Record personal: {serie_prag|zi|zile}.", "Ai trecut de vechea serie de {record_vechi|zi|zile}. De aici e teren nou.", Wink) {
            it.milestone != null && it.recordOld >= 3 && it.milestone.current > it.recordOld
        }
    )

    // ───────────── 7. Revenirea (ziua 2 / 7 / 14, apoi tăcere; zero vină) ─────────────
    private fun step(n: Int): (NudgeData) -> Boolean = { it.comebackStep == n }
    val comeback = listOf(
        Template("7.2", Comeback, "Casca ți-a ținut locul.", "Totul e unde l-ai lăsat. Reîncepi de unde vrei, nu de la zero.", Wink, cond = step(2)),
        Template("7.1", Comeback, "Totul e la locul lui.", "Harta și jurnalele te așteaptă. Un pas azi și ești din nou în formație.", Happy, cond = step(2)),
        Template("7.4", Comeback, "Ultima serie: {serie_veche|zi|zile}.", "Ai făcut-o o dată. Știi drumul. Primul pas e la fel de scurt.", Thinking) {
            it.comebackStep in setOf(2, 7) && it.oldStreak >= 5
        },
        Template("7.3", Comeback, "A trecut o săptămână.", "Fără explicații, fără reproș. Deschide și facem primul pas iar.", Talking, cond = step(7)),
        Template("7.6", Comeback, "Harta ta te așteaptă.", "{locuri|loc|locuri} pe ea. Tot ce ai construit e încă acolo.", Thinking) {
            it.comebackStep == 7 && it.placesTotal >= 3
        },
        Template("C7", Comeback, "Ultimul mesaj, promit.", "Nu mai insist. Când te întorci, FORJA e aici, cu totul pe loc.", Thinking, cond = step(14))
    )

    // ───────────── 13.1 Mese găsite în poze (GalleryScan) ─────────────
    val mealLog = listOf(
        Template("13.1", MealLog, "{mese_gasite|masă găsită|mese găsite} în poze.", "Confirmi tu în FORJA. Nimic nu intră în jurnal fără tine.", Thinking)
    )

    // ───────────── 14. Focus încheiat ─────────────
    val focusDone = listOf(
        Template("14.1", FocusDone, "Postul de pază s-a încheiat.", "{focus_sesiune} min fără distrageri. {copaci_noi|copac nou|copaci noi} în pădurea ta.", Happy),
        Template("14.2", FocusDone, "Pădurea de azi: {copaci|copac|copaci}.", "Atenția se ascute când o folosești. Se vede în pădurea ta.", Happy) {
            it.treesToday >= 2 && it.focusSessionTrees == 0
        },
        Template("14.6", FocusDone, "Gata, poți respira.", "Ai dus sesiunea la capăt. Restul zilei e mai limpede acum.", Thinking, reserve = true)
    )

    // ───────────── 9. Raportul nopții (mereu „estimat”) ─────────────
    val sleepReport = listOf(
        Template("9.1", SleepReport, "Raportul tău e gata.", "Ai dormit {somn_h}, estimat. Cea mai bună noapte a săptămânii.", Happy) {
            it.sleep?.let { s -> s.minutes >= 420 && s.bestOfWeek } == true
        },
        Template("9.2", SleepReport, "Noapte plină: {somn_h}.", "Estimat din mișcare. Ai muniție pentru toată ziua.", Happy) { (it.sleep?.minutes ?: 0) >= 420 },
        Template("9.3", SleepReport, "Ai prins somn adânc.", "{profund} de somn profund, estimat. Vezi tot pe cronologia nopții.", Thinking) { (it.sleep?.deepMin ?: 0) >= 60 },
        Template("9.4", SleepReport, "Raportul e gata, parțial.", "Am analizat {acoperire}. E o estimare, nu un cântar.", Thinking) {
            it.sleep?.let { s -> s.totalMin > 0 && s.coverageMin * 10 < s.totalMin * 8 } == true
        },
        Template("9.5", SleepReport, "Noapte liniștită.", "Totul arată bine. Ai muniție pentru toată ziua.", Wink),
        Template("9.6", SleepReport, "Noaptea a fost scurtă.", "{somn_h}, estimat. Fără reproș; diseară recuperezi o parte.", Talking) { (it.sleep?.minutes ?: Int.MAX_VALUE) < 360 },
        Template("9.7", SleepReport, "Raportul nopții e gata.", "Somn estimat: {somn_h}. Un minut de citit, apoi ziua.", Talking, reserve = true),
        Template("9.0", SleepReport, "Raportul nopții e gata.", "Cronologia nopții te așteaptă la Somn. Totul e estimat din sunet și mișcare.", Talking, reserve = true)
    )

    // ───────────── 11. Loc nou / revizitat (un loc = ai STAT acolo) ─────────────
    private val PLACE_MARKS = setOf(10, 25, 50, 100, 250, 500)
    val newPlace = listOf(
        Template("11.0", NewPlace, "Primul loc pe harta ta.", "Ai stat aici {ore_stat}. De aici începe jurnalul de marș.", Happy) {
            it.place?.let { p -> !p.revisit && p.total == 1 } == true
        },
        Template("11.3", NewPlace, "{locuri@m} loc pe hartă.", "Ai stat aici {ore_stat}. Orașul nu se vede din casă. Tu îl vezi, loc cu loc.", Wink) {
            it.place?.let { p -> !p.revisit && p.total in PLACE_MARKS } == true
        },
        Template("11.2", NewPlace, "Un loc nou pe hartă.", "Ai stat aici {ore_stat}. Dă-i un nume și devine al tău.", Happy) {
            it.place?.let { p -> !p.revisit && p.total != 1 && p.total !in PLACE_MARKS } == true
        },
        Template("11.4", NewPlace, "Iar la {loc}.", "E {vizite@f} oară aici. A devenit un loc al tău, se vede.", Happy) { it.place?.revisit == true }
    )

    // ───────────── 12. Prieteni (niciodată fantomă, familie, somn; ≤ 1/prieten/zi, ≤ 2/zi) ─────────────
    val friendNear = listOf(
        Template("12.1", FriendNear, "{prieten} e la {distanta}.", "Chiar aproape acum. Un semn scurt și poate vă vedeți.", Happy) {
            it.friend?.let { f -> Nudge.stateText(f.state) == null } == true
        },
        Template("12.3", FriendNear, "{prieten} e în mișcare.", "{stare_prieten} chiar acum, la {distanta}. Vezi pe hartă pe unde e.", Talking)
    )
    val newFriend = listOf(
        Template("12.5", NewFriend, "Camarad nou: {prieten}.", "E în agenda ta și pe FORJA. Sunteți prieteni și vă vedeți pe hartă.", Happy),
        Template("12.5b", NewFriend, "Camarad nou.", "Cineva din agenda ta e pe FORJA. Sunteți prieteni și vă vedeți pe hartă.", Happy, reserve = true)
    )

    // ───────────── 8. Culcarea (Prefs.sleepReminder; trece prin orele de liniște) ─────────────
    val bedtime = listOf(
        Template("8.1", Bedtime, "Stingerea în {min_stingere|minut|minute}.", "Ecranul jos, lumina mică. Trezirea e la {ora_trezire}.", Talking),
        Template("8.2", Bedtime, "Odihna e muniție.", "Mâine ai trezire la {ora_trezire}. Fiecare minut de acum e somn câștigat.", Thinking) { it.hour in 12..23 },
        Template("8.3", Bedtime, "Azi recuperezi.", "Aseară: {somn_h}, estimat. Culcarea cu 20 de minute mai devreme repară mult.", Thinking) {
            (it.lastNight?.minutes ?: Int.MAX_VALUE) < 360
        },
        Template("8.4", Bedtime, "Stingerea e un ordin.", "Nu o sugestie. Pornește Stingerea și lasă FORJA să vegheze.", Wink) { !it.sleepTracking },
        Template("8.r1", Bedtime, "Noapte bună, {nume}.", "Apă pe noptieră, telefonul departe. Raportul te așteaptă dimineața.", Happy, reserve = true),
        Template("8.r0", Bedtime, "Noapte bună, recrut.", "Apă pe noptieră, telefonul departe. Raportul te așteaptă dimineața.", Happy, reserve = true)
    )

    // ───────────── 15. Contract / permisiuni (sec; ≤ 1/săpt./subiect, oprit după 2 refuzuri, zero după Revocă) ─────────────
    val permission = listOf(
        Template("15.1", Permission, "Contractul așteaptă semnătura.", "Fără el, datele rămân pe telefon. Îl citești în două minute, la Profil.", Thinking) { it.permission == "contract" },
        Template("15.2", Permission, "Harta vede doar cu FORJA deschisă.", "Pentru locurile tale, Android cere locația „tot timpul”. Alegerea e a ta.", Talking) { it.permission == "bg_location" },
        Template("15.7", Permission, "Camarazii nu te găsesc încă.", "Cu agenda, prietenii cu FORJA apar singuri. Numele rămân pe telefon.", Talking) { it.permission == "contacts" }
    )

    // ───────────── 16. Sincronizarea oprită de Android (bugetul dataSync de 6 h) ─────────────
    val syncStopped = listOf(
        Template("A1", SyncStopped, "Sincronizarea a luat pauză.", "Android o oprește după 6 h în fundal. Deschizi FORJA și reluăm de unde am rămas.", Thinking)
    )

    val all: List<Template> by lazy {
        sync + morning + midday + evening + streakRisk + milestones + comeback + mealLog + focusDone +
            sleepReport + newPlace + friendNear + newFriend + bedtime + permission + syncStopped
    }

    fun of(context: NudgeContext): List<Template> = when (context) {
        SyncOngoing -> sync
        Morning -> morning
        Midday -> midday
        Evening -> evening
        StreakRisk -> streakRisk
        Milestone -> milestones
        Comeback -> comeback
        MealLog -> mealLog
        FocusDone -> focusDone
        SleepReport -> sleepReport
        NewPlace -> newPlace
        FriendNear -> friendNear
        NewFriend -> newFriend
        Bedtime -> bedtime
        Permission -> permission
        SyncStopped -> syncStopped
    }
}
