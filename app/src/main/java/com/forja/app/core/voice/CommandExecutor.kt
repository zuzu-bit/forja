package com.forja.app.core.voice

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.forja.app.ForjaApp
import com.forja.app.R
import com.forja.app.core.data.Friend
import com.forja.app.core.data.listening
import com.forja.app.core.focus.FocusMonitorService
import com.forja.app.core.location.GoTrackService
import com.forja.app.core.music.Mix
import com.forja.app.core.music.Music
import com.forja.app.core.music.MusicSource
import com.forja.app.core.music.MusicStarter
import com.forja.app.core.music.MusicStats
import com.forja.app.core.sleep.SleepTrackService
import com.forja.app.core.sleep.SystemAlarm
import com.forja.app.core.util.Fmt
import com.forja.app.feature.breath.BreathLinks
import com.forja.app.feature.map.MapLinks
import com.forja.app.feature.nutrition.NutritionPrefs
import com.forja.app.feature.nutrition.Targets
import com.forja.app.feature.workout.LiveState
import com.forja.app.feature.workout.WorkoutLink
import com.forja.app.navigation.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Ce mai așteaptă asistentul de la utilizator după o întrebare. */
sealed class Pending {
    data class Recipient(val draft: VoiceCommand.SendMessage) : Pending()
    data class Body(val draft: VoiceCommand.SendMessage, val contact: Contact, val exact: Boolean = true) : Pending()
    data class Confirm(val draft: VoiceCommand.SendMessage, val contact: Contact) : Pending()
    data class MusicQuery(val service: MusicService) : Pending()
    object CallWho : Pending()
    /** „Deschid setările ca să pornești controlul ecranului?” */
    object EnableScreen : Pending()
}

/** Rezultatul unei comenzi: ce se spune și ce se întâmplă. */
sealed class Outcome {
    /** [readScreenAfterMs] > 0: după răspuns, asistentul citește ecranul aplicației din față (dacă are acces). */
    data class Done(val spoken: String, val navigate: String? = null, val readScreenAfterMs: Long = 0) : Outcome()
    data class Ask(val question: String, val pending: Pending) : Outcome()
    /** Lipsesc permisiuni; după ce utilizatorul răspunde, [retry] se execută din nou. */
    data class NeedPermission(val permissions: List<String>, val spoken: String, val retry: VoiceCommand) : Outcome()
}

/** Comenzile, duse la capăt cu intenții Android. Rulează pe un fir de fundal. */
class CommandExecutor(private val app: ForjaApp) {

    /** Permisiunile refuzate chiar acum, doar pentru comanda reluată — pentru ele folosim căile fără permisiune. */
    private var denied: Set<String> = emptySet()

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    }

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    suspend fun execute(cmd: VoiceCommand, confirmSend: Boolean, lastSpoken: String, deniedNow: Set<String> = emptySet()): Outcome = try {
        denied = deniedNow
        when (cmd) {
            is VoiceCommand.SendMessage -> sendMessage(cmd, confirmSend)
            is VoiceCommand.Call -> call(cmd)
            is VoiceCommand.PlayMusic -> playMusic(cmd)
            is VoiceCommand.OpenApp -> openApp(cmd.name)
            is VoiceCommand.OpenAppThen -> openAppThen(cmd, confirmSend, lastSpoken)
            is VoiceCommand.WebSearch -> webSearch(cmd.query)
            is VoiceCommand.OpenPlace -> openPlace(cmd)
            is VoiceCommand.Screen -> screen(cmd.action)
            VoiceCommand.EnableScreenControl -> enableScreen()
            is VoiceCommand.Navigate -> Outcome.Done("Deschid ${cmd.target.spoken}.", cmd.target.route)
            // ── în FORJA (4.9) ──
            is VoiceCommand.StartWorkout -> startWorkout(cmd)
            is VoiceCommand.WorkoutControl -> workout(cmd.action)
            is VoiceCommand.MusicControl -> musicControl(cmd.action)
            is VoiceCommand.PlayPlaylist -> playPlaylist(cmd)
            VoiceCommand.Pause -> pauseAny()
            VoiceCommand.Continue -> continueAny()
            VoiceCommand.Next -> nextAny()
            is VoiceCommand.StartFocus -> startFocus(cmd)
            VoiceCommand.StopFocus -> stopFocus()
            VoiceCommand.FocusStatus -> Outcome.Done(focusStatus())
            VoiceCommand.StartBreath -> startBreath()
            VoiceCommand.NutritionSummary -> Outcome.Done(nutritionSummary())
            VoiceCommand.SleepSummary -> Outcome.Done(sleepSummary())
            VoiceCommand.SoldierStatus -> soldierStatus()
            is VoiceCommand.FriendWhere -> friendWhere(cmd)
            is VoiceCommand.StartGo -> startGo(cmd)
            VoiceCommand.StopGo -> stopGo()
            is VoiceCommand.SetAlarm -> setAlarm(cmd)
            VoiceCommand.StartSleep -> startSleep()
            VoiceCommand.StopSleep -> {
                SleepTrackService.stop(app)
                Outcome.Done("Bună dimineața. Sesiunea de somn s-a încheiat.", Route.SLEEP)
            }
            VoiceCommand.TellTime -> Outcome.Done(spokenTime())
            VoiceCommand.TellDate -> Outcome.Done(spokenDate())
            VoiceCommand.Progress -> Outcome.Done(progress())
            VoiceCommand.Summary -> summaryAny()
            VoiceCommand.Friends -> Outcome.Done(friends())
            VoiceCommand.Help -> Outcome.Done(CommandParser.HELP_TEXT)
            VoiceCommand.Repeat -> Outcome.Done(lastSpoken.ifBlank { "Nu am spus nimic încă." })
            VoiceCommand.Stop -> Outcome.Done("Bine.")
            VoiceCommand.StopListening -> Outcome.Done("Am oprit ascultarea. Apasă microfonul când mai ai nevoie de mine.")
            is VoiceCommand.Unknown ->
                if (cmd.text.isBlank()) Outcome.Done("Nu am auzit nimic. Apasă microfonul și spune, de exemplu, „trimite mesaj lui Ion”.")
                else Outcome.Done("Nu am înțeles „${cmd.text}”. Spune „ajutor” ca să afli ce pot face.")
        }
    } catch (e: Exception) {
        Outcome.Done("Nu am reușit: ${e.message ?: "eroare necunoscută"}.")
    } finally {
        denied = emptySet()
    }

    /** Răspunsul la o întrebare pusă anterior. */
    suspend fun answer(pending: Pending, raw: String, confirmSend: Boolean): Outcome {
        // „anulează" oprește orice dialog.
        if (CommandParser.parse(raw) is VoiceCommand.Stop) return Outcome.Done("Am anulat.")
        return when (pending) {
            is Pending.Recipient -> {
                val who = CommandParser.recipientFromAnswer(raw)
                if (who.isBlank()) Outcome.Ask("Nu am prins numele. Cui să trimit mesajul?", pending)
                else sendMessage(pending.draft.copy(recipient = who, splitByContact = false), confirmSend)
            }
            is Pending.Body -> {
                val body = CommandParser.bodyFromAnswer(raw)
                if (body.isBlank()) Outcome.Ask("Nu am prins textul. Ce mesaj să-i trimit lui ${pending.contact.name}?", pending)
                else {
                    val draft = pending.draft.copy(recipient = pending.contact.number, body = body, splitByContact = false)
                    // Textul tocmai a fost dictat: îl citim înapoi când utilizatorul vrea confirmări sau când numele a fost ghicit.
                    if (confirmSend || !pending.exact) Outcome.Ask(confirmQuestion(pending.contact, body, draft.channel), Pending.Confirm(draft, pending.contact))
                    else deliver(draft, pending.contact)
                }
            }
            is Pending.Confirm -> when (CommandParser.yesNo(raw)) {
                true -> deliver(pending.draft, pending.contact)
                false -> Outcome.Done("Am anulat. Nu am trimis nimic.")
                null -> Outcome.Ask("Spune „da” ca să trimit sau „nu” ca să anulez.", pending)
            }
            is Pending.MusicQuery -> {
                val q = VoiceText.normalize(raw).replace(Regex("^(?:pune|play|melodia|piesa|cantecul|song|the song)\\s+"), "")
                if (q.isBlank()) Outcome.Ask("Nu am prins numele. Ce să pun?", pending)
                else playMusic(VoiceCommand.PlayMusic(q, pending.service))
            }
            Pending.CallWho -> {
                val who = CommandParser.recipientFromAnswer(raw)
                if (who.isBlank()) Outcome.Ask("Pe cine să sun?", pending) else call(VoiceCommand.Call(who))
            }
            Pending.EnableScreen -> when (CommandParser.yesNo(raw)) {
                true -> enableScreen()
                false -> Outcome.Done("Bine. Pot deschide aplicațiile, dar nu pot lucra în ele până nu pornești controlul ecranului.")
                null -> Outcome.Ask("Spune „da” ca să deschid setările sau „nu”.", pending)
            }
        }
    }

    // ── Pe ecranul altor aplicații (serviciul de accesibilitate FORJA, același ca pentru Detox) ──────

    private fun screenUnavailable(prefix: String = ""): Outcome =
        if (!ScreenAgent.isEnabled(app)) Outcome.Ask(
            "${prefix}Ca să lucrez pe ecranul altor aplicații am nevoie de serviciul FORJA din Accesibilitate, același ca pentru Focus. Deschid setările ca să-l pornești?",
            Pending.EnableScreen
        ) else Outcome.Done("${prefix}Serviciul FORJA e pornit în Accesibilitate, dar nu e conectat. Oprește-l și pornește-l din nou din Setări, Accesibilitate.")

    private fun enableScreen(): Outcome {
        if (ScreenAgent.isConnected()) return Outcome.Done("Accesibilitatea FORJA e deja pornită.")
        val i = com.forja.app.core.detox.AccessibilityLink.best(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(i, "Am deschis Accesibilitatea. Pornește „FORJA” și confirmă. Dacă Android spune „setare restricționată”, intră în Setări, Aplicații, FORJA, meniul cu trei puncte, „Permite setările restricționate”. Apoi spune din nou comanda.", "Pornește accesibilitatea FORJA")
    }

    private suspend fun screen(action: ScreenAction): Outcome {
        if (!ScreenAgent.isConnected()) return screenUnavailable()
        return when (action) {
            ScreenAction.Read -> {
                val txt = ScreenAgent.read(900)
                val label = ScreenAgent.foregroundAppLabel(app)
                if (txt.isBlank()) Outcome.Done("Nu văd text pe ecran.")
                else Outcome.Done((if (label.isNotBlank()) "În $label: " else "Pe ecran: ") + txt)
            }
            is ScreenAction.Tap -> {
                val item = ScreenAgent.find(action.target)
                    ?: return Outcome.Done("Nu am găsit „${action.target}” pe ecran. Văd: ${ScreenAgent.glance()}")
                if (ScreenAgent.tap(item)) Outcome.Done("Am apăsat pe „${item.text.take(60)}”.", readScreenAfterMs = 1500)
                else Outcome.Done("Nu am putut apăsa pe „${item.text.take(60)}”.")
            }
            is ScreenAction.Type ->
                if (ScreenAgent.type(action.text)) Outcome.Done("Am scris „${action.text}”.")
                else Outcome.Done("Nu văd un câmp de text pe ecran. Spune întâi „apasă pe …” câmpul, apoi „scrie …”. Dacă vrei să trimiți un mesaj, spune „trimite mesaj lui …”.")
            ScreenAction.Enter -> if (ScreenAgent.enter()) Outcome.Done("Gata.", readScreenAfterMs = 2000) else Outcome.Done("Nu am găsit unde să apăs Enter.")
            is ScreenAction.Scroll ->
                if (ScreenAgent.scroll(action.down)) Outcome.Done(if (action.down) "Am derulat în jos." else "Am derulat în sus.", readScreenAfterMs = 900)
                else Outcome.Done("Nu am ce derula aici.")
            ScreenAction.Back -> if (ScreenAgent.back()) Outcome.Done("Înapoi.", readScreenAfterMs = 1200) else Outcome.Done("Nu pot merge înapoi.")
            ScreenAction.Home -> if (ScreenAgent.home()) Outcome.Done("Ecranul principal.") else Outcome.Done("Nu pot ajunge la ecranul principal.")
            is ScreenAction.Search -> when (ScreenAgent.search(action.query)) {
                ScreenAgent.SearchResult.SUBMITTED -> Outcome.Done("Caut „${action.query}”.", readScreenAfterMs = 2500)
                ScreenAgent.SearchResult.TYPED_ONLY -> Outcome.Done("Am scris „${action.query}” în căutare, dar nu am găsit butonul de căutare. Apasă tasta de căutare de pe tastatură.", readScreenAfterMs = 2500)
                ScreenAgent.SearchResult.NO_SEARCH -> Outcome.Done("Nu am găsit căutarea în aplicația asta. Spune „apasă pe căutare”, apoi „scrie …”, apoi „enter”.")
            }
        }
    }

    /** „deschide X și …”: aplicația, apoi acțiunea — pe ecranul ei sau prin intenție. */
    private suspend fun openAppThen(cmd: VoiceCommand.OpenAppThen, confirmSend: Boolean, lastSpoken: String): Outcome {
        // Fără serviciul de accesibilitate nu are rost să deschidem aplicația peste FORJA: întrebăm întâi (setările se pot deschide doar din prim-plan).
        if (cmd.then is VoiceCommand.Screen && !ScreenAgent.isConnected()) return screenUnavailable("Pot deschide ${cmd.app}, dar nu și să lucrez în ea. ")
        val (opened, pkg, started) = openAppPkg(cmd.app)
        if (opened !is Outcome.Done || !started) return opened
        return when (val then = cmd.then) {
            is VoiceCommand.Screen -> {
                if (!ScreenAgent.isConnected()) return screenUnavailable("Am deschis ${cmd.app}. ")
                val arrived = pkg == null || ScreenAgent.waitForPackage(pkg, 6000)
                if (!arrived) return Outcome.Done("Am deschis ${cmd.app}, dar nu a ajuns în față. Repetă comanda din aplicație.")
                ScreenAgent.waitForContent(5000)   // o aplicație pornită la rece are nevoie de o clipă să se deseneze
                delay(400)
                val r = screen(then.action)
                if (r is Outcome.Done) r.copy(spoken = "Am deschis ${cmd.app}. " + r.spoken) else r
            }
            is VoiceCommand.Unknown -> Outcome.Done("Am deschis ${cmd.app}. Nu am înțeles ce să fac înăuntru: „${then.text}”. Poți spune „caută …”, „apasă pe …”, „scrie …” sau „citește ecranul”.")
            else -> { delay(800); execute(then, confirmSend, lastSpoken) }
        }
    }

    private fun webSearch(query: String): Outcome {
        val q = query.trim()
        if (q.isBlank()) return Outcome.Done("Ce să caut?")
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(app.packageManager) != null) return launch(i, "Caut „$q” pe Google.", "Google: $q", readAfterMs = 3500)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (web.resolveActivity(app.packageManager) == null) return Outcome.Done("Nu am găsit un browser pe telefon.")
        return launch(web, "Caut „$q” pe internet.", "Căutare: $q", readAfterMs = 4000)
    }

    private fun openPlace(cmd: VoiceCommand.OpenPlace): Outcome {
        val place = cmd.place.trim()
        if (place.isBlank()) return Outcome.Done("Unde să te duc?")
        val enc = Uri.encode(place)
        val waze = "com.waze"; val maps = "com.google.android.apps.maps"
        val candidates = mutableListOf<Intent>()
        if (cmd.app == "waze" && isInstalled(waze)) candidates += Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=$enc&navigate=${if (cmd.navigate) "yes" else "no"}")).setPackage(waze)
        if (cmd.navigate && isInstalled(maps)) candidates += Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$enc")).setPackage(maps)
        if (isInstalled(maps)) candidates += Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc")).setPackage(maps)
        candidates += Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$enc"))
        val i = candidates.firstOrNull { it.resolveActivity(app.packageManager) != null }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return Outcome.Done("Nu am găsit o aplicație de hărți pe telefon.")
        return launch(i, if (cmd.navigate) "Pornesc navigarea spre $place." else "Îți arăt $place pe hartă.", if (cmd.navigate) "Navigare: $place" else "Hartă: $place")
    }

    // ── Mesaje ──────────────────────────────────────────────────────────────────────

    private fun confirmQuestion(c: Contact, body: String, channel: MessageChannel) =
        "Trimit lui ${c.name}${if (channel == MessageChannel.WHATSAPP) " pe WhatsApp" else ""}: „$body”. Confirmi?"

    private suspend fun sendMessage(cmd: VoiceCommand.SendMessage, confirmSend: Boolean): Outcome {
        if (cmd.recipient.isBlank()) {
            return Outcome.Ask(
                if (cmd.body.isBlank()) "Cui vrei să trimit mesajul?" else "Cui să trimit „${cmd.body}”?",
                Pending.Recipient(cmd)
            )
        }
        val directNumber = Contacts.asNumber(cmd.recipient)
        val contact: Contact
        var body = cmd.body
        var score = 100   // sub 90 = nume ghicit (prefix, „conține”, greșeală de o literă) → citim înapoi înainte să trimitem
        if (directNumber != null) {
            contact = Contact(directNumber, directNumber)
        } else {
            if (!Contacts.hasPermission(app)) {
                if (Manifest.permission.READ_CONTACTS in denied) return Outcome.Done("Fără acces la contacte nu pot găsi numărul. Spune numărul de telefon sau pornește permisiunea din setări.")
                return Outcome.NeedPermission(
                    listOf(Manifest.permission.READ_CONTACTS),
                    "Am nevoie de acces la contacte ca să găsesc numărul. Permite accesul și reiau.", cmd
                )
            }
            val contacts = Contacts.all(app)
            if (cmd.splitByContact) {
                val r = Contacts.resolvePrefixScored(contacts, cmd.recipient)
                if (r == null) {
                    val first = cmd.recipient.split(" ").first()
                    return Outcome.Ask("Nu am găsit pe „$first” în agendă. Cui să trimit?", Pending.Recipient(cmd.copy(recipient = "", splitByContact = false)))
                }
                contact = r.first
                body = r.second
                score = r.third
            } else {
                val found = Contacts.findScored(contacts, cmd.recipient)
                    ?: return Outcome.Ask("Nu am găsit pe „${cmd.recipient}” în agendă. Cui să trimit?", Pending.Recipient(cmd.copy(recipient = "")))
                contact = found.first
                score = found.second
            }
        }
        val exact = score >= 90
        if (body.isBlank()) {
            return Outcome.Ask("Ce mesaj să-i trimit lui ${contact.name}?", Pending.Body(cmd.copy(recipient = contact.number, splitByContact = false), contact, exact))
        }
        val draft = cmd.copy(recipient = contact.number, body = body, splitByContact = false)
        if ((confirmSend || !exact) && !cmd.confirmed) return Outcome.Ask(confirmQuestion(contact, body, cmd.channel), Pending.Confirm(draft, contact))
        return deliver(draft, contact)
    }

    /** Trimite efectiv: SMS direct (dacă avem voie) sau aplicația de mesaje / WhatsApp deschisă cu textul gata scris. */
    private fun deliver(draft: VoiceCommand.SendMessage, contact: Contact): Outcome {
        val number = contact.number
        val body = draft.body
        if (draft.channel == MessageChannel.WHATSAPP) {
            // wa.me vrea numărul internațional, fără zerouri în față: „0722 123 456” → „40722123456”.
            val e164 = toE164(number)
                ?: return Outcome.Done("Nu știu prefixul de țară pentru numărul lui ${contact.name}; nu pot deschide WhatsApp. Spune „trimite SMS” în loc.")
            val url = "https://wa.me/${e164.removePrefix("+")}?text=" + URLEncoder.encode(body, "UTF-8")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isInstalled("com.whatsapp")) i.setPackage("com.whatsapp")
            else if (isInstalled("com.whatsapp.w4b")) i.setPackage("com.whatsapp.w4b")
            else return Outcome.Done("Nu am găsit WhatsApp pe telefon.")
            return launch(i, "Am deschis WhatsApp cu mesajul pentru ${contact.name}. Apasă „Trimite”.", "WhatsApp: mesaj pentru ${contact.name}")
        }
        if (granted(Manifest.permission.SEND_SMS)) {
            return try {
                val sms = (if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault())
                    ?: throw IllegalStateException("SmsManager indisponibil")
                val sent = sentIntent(contact.name)
                val parts = sms.divideMessage(body)
                if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, ArrayList(List(parts.size) { sent }), null)
                else sms.sendTextMessage(number, null, body, sent, null)
                // Rezultatul real („a plecat” / „fără semnal”) vine prin sentIntent și e spus după.
                Outcome.Done("Trimit mesajul lui ${contact.name}: „$body”.")
            } catch (e: Exception) {
                openSmsApp(number, body, contact)
            }
        }
        if (Manifest.permission.SEND_SMS !in denied) {
            return Outcome.NeedPermission(
                listOf(Manifest.permission.SEND_SMS),
                "Ca să trimit mesajul fără să atingi ecranul, am nevoie de permisiunea pentru SMS.",
                draft.copy(confirmed = true)
            )
        }
        return openSmsApp(number, body, contact)
    }

    private fun openSmsApp(number: String, body: String, contact: Contact): Outcome {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { Telephony.Sms.getDefaultSmsPackage(app)?.let { i.setPackage(it) } } catch (_: Exception) { }
        if (i.resolveActivity(app.packageManager) == null) return Outcome.Done("Nu am găsit o aplicație de mesaje.")
        return launch(i, "Am deschis mesajul către ${contact.name}, cu textul scris. Apasă „Trimite”.", "Mesaj pentru ${contact.name}")
    }

    /** Număr în format internațional (+40…), după țara SIM-ului / rețelei / limbii telefonului. */
    private fun toE164(number: String): String? {
        val tm = try { app.getSystemService(TelephonyManager::class.java) } catch (_: Exception) { null }
        val isos = listOfNotNull(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .map { it.trim().uppercase(Locale.ROOT) }.filter { it.length == 2 }
        for (iso in isos.ifEmpty { listOf("RO") }) {
            try { PhoneNumberUtils.formatNumberToE164(number, iso)?.let { return it } } catch (_: Exception) { }
        }
        val digits = number.replace(Regex("[^\\d+]"), "")
        return if (digits.startsWith("+") || digits.startsWith("00")) "+" + digits.removePrefix("+").removePrefix("00") else null
    }

    /** sentIntent pentru SMS: receptorul (înregistrat o singură dată) spune cu voce dacă mesajul a plecat sau nu. */
    private fun sentIntent(name: String): PendingIntent {
        registerSmsReceiver()
        return PendingIntent.getBroadcast(
            app, (System.currentTimeMillis() and 0x7fffffff).toInt(),
            Intent(SMS_SENT_ACTION).setPackage(app.packageName).putExtra("name", name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    @Volatile private var smsReceiverRegistered = false
    private fun registerSmsReceiver() {
        if (smsReceiverRegistered) return
        smsReceiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val name = intent.getStringExtra("name") ?: ""
                val msg = when (resultCode) {
                    Activity.RESULT_OK -> "Mesajul pentru $name a plecat."
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "Mesajul pentru $name nu a plecat: telefonul e în modul avion."
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "Mesajul pentru $name nu a plecat: nu e semnal."
                    else -> "Mesajul pentru $name nu a plecat. Încearcă din aplicația de mesaje."
                }
                try { app.voice.speakAfter(msg) } catch (_: Exception) { }
            }
        }
        try {
            val filter = IntentFilter(SMS_SENT_ACTION)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
        } catch (_: Exception) { smsReceiverRegistered = false }
    }

    // ── Apeluri ─────────────────────────────────────────────────────────────────────

    private fun call(cmd: VoiceCommand.Call): Outcome {
        if (cmd.who.isBlank()) return Outcome.Ask("Pe cine să sun?", Pending.CallWho)
        val number: String
        val label: String
        val direct = Contacts.asNumber(cmd.who)
        if (direct != null) { number = direct; label = direct.map { it.toString() }.joinToString(" ") }
        else {
            if (!Contacts.hasPermission(app)) {
                if (Manifest.permission.READ_CONTACTS in denied) return Outcome.Done("Fără acces la contacte nu pot găsi numărul lui ${cmd.who}.")
                return Outcome.NeedPermission(listOf(Manifest.permission.READ_CONTACTS), "Am nevoie de acces la contacte ca să-l sun pe ${cmd.who}.", cmd)
            }
            val c = Contacts.find(app, cmd.who) ?: return Outcome.Ask("Nu am găsit pe „${cmd.who}” în agendă. Pe cine să sun?", Pending.CallWho)
            number = c.number; label = c.name
        }
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val emergency = number.length <= 4
        if (!emergency && granted(Manifest.permission.CALL_PHONE)) {
            // TelecomManager pornește apelul și cu ecranul stins (nu are nevoie de o activitate vizibilă).
            try {
                app.getSystemService(TelecomManager::class.java)?.placeCall(uri, null)
                return Outcome.Done("Sun pe $label.")
            } catch (_: Exception) { }
            if (start(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return Outcome.Done("Sun pe $label.")
        } else if (!emergency && Manifest.permission.CALL_PHONE !in denied) {
            return Outcome.NeedPermission(listOf(Manifest.permission.CALL_PHONE), "Ca să sun direct, am nevoie de permisiunea pentru apeluri.", cmd)
        }
        val dial = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (dial.resolveActivity(app.packageManager) == null) return Outcome.Done("Nu am găsit aplicația de telefon.")
        return launch(dial, "Am format numărul lui $label. Apasă butonul de apel.", "Apel: $label")
    }

    // ── Muzică / YouTube ────────────────────────────────────────────────────────────

    private suspend fun playMusic(cmd: VoiceCommand.PlayMusic): Outcome {
        val q = cmd.query.trim()
        if (q.isBlank() && cmd.service != MusicService.ANY && cmd.service != MusicService.SPOTIFY) {
            return Outcome.Ask("Ce să pun pe ${cmd.service.spoken}?", Pending.MusicQuery(cmd.service))
        }
        return when (cmd.service) {
            MusicService.YOUTUBE -> youtube(q)
            MusicService.YOUTUBE_MUSIC -> playFromSearch(q, "com.google.android.apps.youtube.music", "YouTube Music") ?: youtube(q)
            MusicService.SPOTIFY -> playFromSearch(q, "com.spotify.music", "Spotify")
                ?: if (q.isBlank()) Outcome.Ask("Nu am găsit Spotify. Ce să pun pe YouTube?", Pending.MusicQuery(MusicService.YOUTUBE)) else youtube(q)
            MusicService.ANY -> playFromSearch(q, null, "player")
                ?: if (q.isBlank()) Outcome.Ask("Ce melodie să pun?", Pending.MusicQuery(MusicService.YOUTUBE)) else youtube(q)
        }
    }

    /** Intenția standard „redă din căutare" — Spotify, YouTube Music și alți playeri pornesc singuri melodia. */
    private fun playFromSearch(q: String, pkg: String?, label: String): Outcome? {
        if (pkg != null && !isInstalled(pkg)) return null
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, q)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        if (i.resolveActivity(app.packageManager) == null) return null
        return launch(i, if (q.isBlank()) "Pun muzică pe $label." else "Pun „$q” pe $label.", "Muzică: ${q.ifBlank { label }}")
    }

    private suspend fun youtube(q: String): Outcome {
        val yt = "com.google.android.youtube"
        val id = withTimeoutOrNull(6500) { firstYoutubeVideoId(q) }
        if (id != null) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isInstalled(yt)) i.setPackage(yt)
            if (i.resolveActivity(app.packageManager) != null) return launch(i, "Pun „$q” pe YouTube.", "YouTube: $q")
        }
        val search = Intent(Intent.ACTION_SEARCH).setPackage(yt).putExtra("query", q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isInstalled(yt) && search.resolveActivity(app.packageManager) != null) return launch(search, "Caut „$q” pe YouTube. Alege primul rezultat.", "YouTube: $q")
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (web.resolveActivity(app.packageManager) == null) return Outcome.Done("Nu am găsit YouTube pe telefon.")
        return launch(web, "Caut „$q” pe YouTube în browser.", "YouTube: $q")
    }

    /** Primul rezultat al căutării YouTube — ca melodia să pornească singură, fără să atingi ecranul. */
    private fun firstYoutubeVideoId(q: String): String? = try {
        val req = Request.Builder()
            .url("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "ro,en;q=0.8")
            .header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            .build()
        http.newCall(req).execute().use { resp ->
            val html = resp.body?.string() ?: return null
            Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
        }
    } catch (_: Exception) { null }

    // ── Aplicații ───────────────────────────────────────────────────────────────────

    private val knownApps = mapOf(
        "youtube" to "com.google.android.youtube", "iutub" to "com.google.android.youtube", "yutub" to "com.google.android.youtube",
        "youtube music" to "com.google.android.apps.youtube.music", "whatsapp" to "com.whatsapp", "uatsap" to "com.whatsapp",
        "spotify" to "com.spotify.music", "facebook" to "com.facebook.katana", "instagram" to "com.instagram.android",
        "messenger" to "com.facebook.orca", "telegram" to "org.telegram.messenger", "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm", "google maps" to "com.google.android.apps.maps", "maps" to "com.google.android.apps.maps",
        "waze" to "com.waze", "tiktok" to "com.zhiliaoapp.musically", "netflix" to "com.netflix.mediaclient",
        "google" to "com.google.android.googlequicksearchbox", "play store" to "com.android.vending", "magazin play" to "com.android.vending"
    )

    private fun openApp(nameRaw: String): Outcome = openAppPkg(nameRaw).first

    /** (rezultat, pachetul aplicației, chiar a pornit pe ecran?) */
    private fun openAppPkg(nameRaw: String): Triple<Outcome, String?, Boolean> {
        val name = VoiceText.normalize(nameRaw)
        fun go(i: Intent, spoken: String, title: String): Triple<Outcome, String?, Boolean> {
            val pkg = i.`package` ?: try { i.resolveActivity(app.packageManager)?.packageName } catch (_: Exception) { null }
            val (o, started) = launchResult(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), spoken, title, 0)
            return Triple(o, pkg, started)
        }
        fun open(i: Intent, spoken: String, title: String, missing: String): Triple<Outcome, String?, Boolean> =
            if (i.resolveActivity(app.packageManager) == null) Triple(Outcome.Done(missing), null, false) else go(i, spoken, title)
        when (name) {
            "camera", "aparatul foto", "aparat foto" ->
                return open(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Deschid camera.", "Camera", "Nu am găsit camera.")
            "setari", "setarile", "settings", "setarile telefonului" ->
                return open(Intent(Settings.ACTION_SETTINGS), "Deschid setările telefonului.", "Setări", "Nu pot deschide setările.")
            "telefon", "telefonul", "phone", "dialer", "apeluri" ->
                return open(Intent(Intent.ACTION_DIAL), "Deschid telefonul.", "Telefon", "Nu am găsit aplicația de telefon.")
            "mesaje", "mesajele", "messages", "sms" -> {
                val pkg = try { Telephony.Sms.getDefaultSmsPackage(app) } catch (_: Exception) { null }
                val i = pkg?.let { app.packageManager.getLaunchIntentForPackage(it) } ?: return Triple(Outcome.Done("Nu am găsit aplicația de mesaje."), null, false)
                return go(i, "Deschid mesajele.", "Mesaje")
            }
        }
        knownApps[name]?.let { pkg ->
            app.packageManager.getLaunchIntentForPackage(pkg)?.let { i -> return go(i, "Deschid $nameRaw.", nameRaw) }
        }
        // Orice aplicație instalată, după numele de pe ecran.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try { app.packageManager.queryIntentActivities(launcher, 0) } catch (_: Exception) { emptyList() }
        var best: Pair<String, String>? = null   // (label, package)
        var bestScore = 0
        for (ri in apps) {
            val label = VoiceText.normalize(ri.loadLabel(app.packageManager)?.toString() ?: continue)
            val pkg = ri.activityInfo?.packageName ?: continue
            val s = when {
                label == name -> 100
                label.startsWith(name) || name.startsWith(label) -> 80
                name.length >= 4 && label.contains(name) -> 60
                label.split(" ").any { it == name } -> 70
                else -> 0
            }
            if (s > bestScore) { bestScore = s; best = label to pkg }
        }
        val found = best
        if (found == null || bestScore < 60) return Triple(Outcome.Done("Nu am găsit aplicația „$nameRaw” pe telefon."), null, false)
        val i = app.packageManager.getLaunchIntentForPackage(found.second) ?: return Triple(Outcome.Done("Nu pot porni „$nameRaw”."), null, false)
        return go(i, "Deschid ${found.first}.", found.first)
    }

    // ── Diverse ─────────────────────────────────────────────────────────────────────

    private fun setAlarm(cmd: VoiceCommand.SetAlarm): Outcome {
        val spokenTime = "ora ${cmd.hour}" + if (cmd.minute == 0) " fix" else " și ${cmd.minute}"
        if (!isForeground()) {
            // Din fundal, aplicația de Ceas nu poate fi pornită: lăsăm o notificare care o deschide cu alarma gata completată.
            val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                .putExtra(android.provider.AlarmClock.EXTRA_HOUR, cmd.hour)
                .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, cmd.minute)
                .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "FORJA")
                .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(app.packageManager) == null) return Outcome.Done("Nu am găsit o aplicație de ceas pentru alarmă.")
            return launch(i, "Alarma la $spokenTime.", "Alarmă la $spokenTime")
        }
        val ok = SystemAlarm.set(app, cmd.hour, cmd.minute, "FORJA")
        return if (ok) Outcome.Done("Alarma e pusă la $spokenTime.") else Outcome.Done("Nu am găsit o aplicație de ceas pentru alarmă.")
    }

    /** „7 ore și 5 minute” — nu „7 h 05 min”, pe care vocea îl citește prost. */
    private fun spokenDuration(min: Int): String {
        val h = min / 60; val m = min % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h ${if (h == 1) "oră" else "ore"}"
        if (m > 0) parts += "$m ${if (m == 1) "minut" else "minute"}"
        return if (parts.isEmpty()) "sub un minut" else parts.joinToString(" și ")
    }

    private fun startSleep(): Outcome {
        if (!granted(Manifest.permission.RECORD_AUDIO)) return Outcome.Done("Pentru somn am nevoie de microfon. Pornește-l din Permisiuni.", Route.SLEEP)
        return try {
            SleepTrackService.start(app)
            Outcome.Done("Somn ușor. Monitorizarea somnului a pornit; spune „m-am trezit” dimineața.", Route.SLEEP)
        } catch (_: Exception) {
            Outcome.Done("Nu am putut porni somnul din fundal. Deschide ecranul Somn și apasă „Încep să dorm”.", Route.SLEEP)
        }
    }

    private fun spokenTime(): String {
        val now = LocalTime.now()
        return when (now.minute) {
            0 -> "Este ora ${now.hour} fix."
            else -> "Este ora ${now.hour} și ${now.minute}."
        }
    }

    private fun spokenDate(): String {
        val d = LocalDate.now()
        val f = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale("ro", "RO"))
        return "Astăzi este ${d.format(f)}."
    }

    private suspend fun progress(): String {
        val todayStart = Fmt.startOfDayMillis(0)
        val acts = app.db.activityDao().since(todayStart).first()
        val km = acts.sumOf { it.distanceM } / 1000.0
        val meals = app.db.mealDao().mealsForDay(Fmt.epochDay()).first()
        val kcal = app.db.mealDao().kcalForDay(Fmt.epochDay()).first()
        val workouts = app.db.workoutDao().sessionCountSince(Fmt.startOfWeekMillis()).first()
        val sleep = app.db.sleepDao().lastFinished().first()
        val forest = app.prefs.focusForest.first()
        val focusMin = forest.first * 15 + forest.third / 60
        val sb = StringBuilder()
        sb.append(if (km > 0) "Azi ai parcurs ${Fmt.km(km * 1000)} kilometri. " else "Azi nu ai nicio tură înregistrată. ")
        sb.append(if (meals.isNotEmpty()) "Ai ${meals.size} ${if (meals.size == 1) "masă" else "mese"} în jurnal, $kcal de calorii. " else "Nicio masă în jurnal încă. ")
        sb.append("Săptămâna asta ai $workouts ${if (workouts == 1) "antrenament" else "antrenamente"}. ")
        if (sleep != null) {
            val min = (((sleep.endAt ?: sleep.startAt) - sleep.startAt) / 60000).toInt()
            sb.append("Aseară ai dormit ${spokenDuration(min)}. ")
        }
        if (focusMin > 0) sb.append("Focus azi: $focusMin minute. ")
        return sb.toString().trim()
    }

    private suspend fun friends(): String {
        if (app.auth.currentUid == null) return "Nu ești conectat. Intră în cont ca să-ți vezi prietenii."
        val list = friendsSnapshot(3000)
        if (list.isEmpty()) return "Încă nu ai prieteni în FORJA. Dă-le codul tău de invitație din Profil."
        val now = System.currentTimeMillis()
        val parts = list.map { f ->
            val first = f.name.split(' ').first()
            val music = f.listening(now)?.let { ", ascultă $it" } ?: ""
            "$first ${friendState(f)}$music"
        }
        return "Ai ${list.size} ${if (list.size == 1) "prieten" else "prieteni"}: " + parts.joinToString("; ") + "."
    }

    private fun friendState(f: Friend): String = when {
        f.ghost -> "e fantomă"
        f.state == "run" -> "aleargă acum"
        f.state == "ride" -> "e pe roți"
        f.state == "walk" -> "se plimbă"
        f.state == "gym" -> "e la sală"
        f.state == "sleep" -> "doarme"
        f.state == "off" -> "e deconectat"
        else -> "e liniștit"
    }

    /** Lista s-a așezat (nimic nou o jumătate de secundă). */
    private class Settled : RuntimeException() { override fun fillInStackTrace(): Throwable = this }

    /**
     * Lista de prieteni, întreagă: fluxul emite pe rând (câte un prieten sosit), deci luăm valoarea după care nu mai
     * vine nimic o jumătate de secundă — fără să așteptăm tot [ms] când lista e gata din prima.
     */
    private suspend fun friendsSnapshot(ms: Long): List<Friend> {
        val uid = app.auth.currentUid ?: return emptyList()
        var last: List<Friend> = emptyList()
        try {
            withTimeoutOrNull(ms) {
                app.friends.friendsFlow(uid).collectLatest { list ->
                    last = list
                    delay(500)
                    throw Settled()
                }
            }
        } catch (_: Settled) { } catch (_: Exception) { }
        return last
    }

    /** „rezumat”, fără obiect: al antrenamentului pornit, altfel al zilei. */
    private suspend fun summaryAny(): Outcome {
        val s = WorkoutLink.live.value
        return Outcome.Done(if (s != null && !s.finished) workoutSummary(s) else progress())
    }

    // ── În FORJA (4.9): antrenamentul ─────────────────────────────────────────────────

    private fun planMatches(planName: String, q: String): Boolean {
        val name = VoiceText.normalize(planName).replace(Regex("[&+/]"), " ").replace(Regex("\\s+"), " ").trim()
        val tokens = name.split(" ").filter { it.isNotBlank() }
        val qt = q.replace(Regex("[&+/]"), " ").split(" ").filter { it.isNotBlank() && it !in setOf("si", "and", "de", "la", "cu") }
        if (qt.isEmpty()) return false
        if (name.contains(q)) return true
        if (qt.all { w -> tokens.any { it == w || it.startsWith(w) } }) return true
        // „acasă” / „fără echipament” → planul de acasă; „sală” → primul plan de sală
        if (qt.any { it in setOf("acasa", "home", "casa") } && name.contains("acasa")) return true
        return false
    }

    private suspend fun startWorkout(cmd: VoiceCommand.StartWorkout): Outcome {
        val live = WorkoutLink.live.value
        if (live != null && !live.finished) {
            return Outcome.Done("Antrenamentul e deja pornit: ${workoutWhere(live)}. Spune „pauză”, „am terminat seria” sau „următorul exercițiu”.", Route.WORKOUT_LIVE)
        }
        val plans = try { withTimeoutOrNull(3000) { app.db.workoutDao().plans().first() } } catch (_: Exception) { null } ?: emptyList()
        var idx: Int? = null
        val q = cmd.plan?.let { VoiceText.normalize(it) }?.trim().orEmpty()
        if (q.isNotBlank() && plans.isNotEmpty()) {
            val found = plans.indexOfFirst { planMatches(it.name, q) }
            if (found < 0) {
                return Outcome.Done("Nu am un plan „${cmd.plan}”. Planurile sunt: ${plans.joinToString(", ") { it.name }}. Spune, de exemplu, „începe antrenamentul de ${plans.first().name}”.")
            }
            idx = found
        }
        WorkoutLink.request(WorkoutLink.Request.Start(idx))
        val name = idx?.let { plans.getOrNull(it)?.name }
        return Outcome.Done(
            (if (name != null) "Pornesc antrenamentul „$name”. " else "Pornesc antrenamentul. ") +
                "Spune „am terminat seria” după fiecare serie, „pauză” când ai nevoie și „rezumat” ca să afli unde ai ajuns.",
            Route.WORKOUT_LIVE
        )
    }

    /** „exercițiul 2 din 5, Genuflexiuni, seria 1 din 4” */
    private fun workoutWhere(s: LiveState): String {
        val ex = s.current ?: return "sesiune fără exerciții"
        val where = "exercițiul ${s.exPos + 1} din ${s.exercises.size}, ${ex.name}, seria ${s.setNo} din ${ex.sets}"
        return when {
            s.paused -> "$where, pe pauză"
            s.resting -> "$where, în pauza dintre serii (${s.restLeft} secunde)"
            else -> where
        }
    }

    private suspend fun workout(action: WorkoutAction): Outcome {
        val s = WorkoutLink.live.value
        if (action == WorkoutAction.SUMMARY) return Outcome.Done(workoutSummary(s))
        if (s == null || s.finished) return Outcome.Done("Nu e niciun antrenament pornit. Spune „începe antrenamentul”.")
        val ex = s.current
        return when (action) {
            WorkoutAction.PAUSE ->
                if (s.paused) Outcome.Done("Antrenamentul e deja în pauză. Spune „continuă” când ești gata.")
                else { WorkoutLink.request(WorkoutLink.Request.Pause); Outcome.Done("Pauză. Cronometrul stă. Spune „continuă” când ești gata.") }
            WorkoutAction.RESUME ->
                if (!s.paused) Outcome.Done("Antrenamentul merge deja: ${workoutWhere(s)}.")
                else { WorkoutLink.request(WorkoutLink.Request.Resume); Outcome.Done("Continuăm: ${workoutWhere(s.copy(paused = false))}.") }
            WorkoutAction.END -> {
                WorkoutLink.request(WorkoutLink.Request.End)
                val min = (s.elapsedSec() / 60).toInt()
                Outcome.Done("Am încheiat antrenamentul „${s.planName}” după ${spokenDuration(min)}, cu ${s.totalSetsDone} ${if (s.totalSetsDone == 1) "serie" else "serii"}.", Route.WORKOUT)
            }
            WorkoutAction.FINISH_SET -> when {
                s.paused -> Outcome.Done("Antrenamentul e în pauză. Spune „continuă” mai întâi.")
                s.resting -> Outcome.Done("Ești în pauza dintre serii: mai sunt ${s.restLeft} secunde. Spune „sari pauza” ca să continui.")
                ex == null -> Outcome.Done("Nu văd exercițiul curent.")
                else -> {
                    WorkoutLink.request(WorkoutLink.Request.FinishSet)
                    delay(300)
                    val n = WorkoutLink.live.value
                    when {
                        s.setNo < ex.sets -> Outcome.Done("Serie salvată: ${s.setNo} din ${ex.sets} la ${ex.name}. Pauză 90 de secunde; spune „sari pauza” dacă vrei mai repede.")
                        n != null && !n.finished && n.exPos > s.exPos -> Outcome.Done("${ex.name} terminat. Urmează: ${n.current?.name}, ${n.current?.sets} serii de ${n.current?.reps} repetări.")
                        else -> Outcome.Done("Ultima serie. Antrenament încheiat — misiune îndeplinită!")
                    }
                }
            }
            WorkoutAction.SKIP_REST ->
                if (!s.resting) Outcome.Done("Nu ești în pauza dintre serii acum: ${workoutWhere(s)}.")
                else {
                    WorkoutLink.request(WorkoutLink.Request.SkipRest)
                    Outcome.Done("Fără pauză. Seria ${s.setNo + 1} din ${ex?.sets}: ${ex?.name}, ${ex?.reps} repetări.")
                }
            WorkoutAction.ADD_REST ->
                if (!s.resting) Outcome.Done("Nu ești în pauza dintre serii acum.")
                else { WorkoutLink.request(WorkoutLink.Request.AddRest); Outcome.Done("Încă 15 secunde de pauză.") }
            WorkoutAction.NEXT_EXERCISE -> when {
                s.paused -> Outcome.Done("Antrenamentul e în pauză. Spune „continuă” mai întâi.")
                s.next == null -> Outcome.Done("E ultimul exercițiu: ${ex?.name}. Spune „am terminat seria” după fiecare serie sau „termină antrenamentul”.")
                else -> {
                    val nx = s.next!!
                    WorkoutLink.request(WorkoutLink.Request.NextExercise)
                    Outcome.Done("Trecem la ${nx.name}: ${nx.sets} serii de ${nx.reps} repetări.")
                }
            }
            WorkoutAction.SUMMARY -> Outcome.Done(workoutSummary(s))
        }
    }

    private suspend fun workoutSummary(s: LiveState?): String {
        if (s != null && !s.finished && s.current != null) {
            val ex = s.current!!
            val sb = StringBuilder()
            sb.append("Antrenamentul „${s.planName}”: ${spokenDuration((s.elapsedSec() / 60).toInt())} de când ai început")
            sb.append(if (s.paused) ", acum pe pauză. " else ". ")
            sb.append("Ești la exercițiul ${s.exPos + 1} din ${s.exercises.size}, ${ex.name}, seria ${s.setNo} din ${ex.sets}")
            sb.append(if (s.resting) ", în pauza dintre serii: ${s.restLeft} secunde. " else ". ")
            sb.append("Ai făcut ${s.totalSetsDone} ${if (s.totalSetsDone == 1) "serie" else "serii"} din ${s.plannedSets}. ")
            s.next?.let { sb.append("Urmează: ${it.name}.") }
            return sb.toString().trim()
        }
        val last = try { app.db.workoutDao().lastSession().first() } catch (_: Exception) { null }
        val week = try { app.db.workoutDao().sessionCountSince(Fmt.startOfWeekMillis()).first() } catch (_: Exception) { 0 }
        val sb = StringBuilder("Nu e niciun antrenament pornit acum. ")
        if (last != null) {
            val whenSpoken = when {
                last.startedAt >= Fmt.startOfDayMillis(0) -> "azi"
                last.startedAt >= Fmt.startOfDayMillis(1) -> "ieri"
                else -> "pe " + DateTimeFormatter.ofPattern("d MMMM", Locale("ro")).format(java.time.Instant.ofEpochMilli(last.startedAt).atZone(java.time.ZoneId.systemDefault()))
            }
            val end = last.endedAt
            val dur = if (end != null) ", ${spokenDuration(((end - last.startedAt) / 60000).toInt())}" else ""
            sb.append("Ultimul: „${last.planName}”, $whenSpoken$dur, ${last.totalSets} ${if (last.totalSets == 1) "serie" else "serii"}. ")
        }
        sb.append("Săptămâna asta: $week ${if (week == 1) "antrenament" else "antrenamente"}. Spune „începe antrenamentul” ca să pornești unul.")
        return sb.toString()
    }

    // ── Muzica (sesiunile media ale playerului tău) ─────────────────────────────────

    /** Sesiunile media se citesc doar după ce motorul muzicii a pornit (pe firul principal, sincron). */
    private suspend fun musicWarm() {
        try { withContext(Dispatchers.Main.immediate) { Music.ensureStarted(app) } } catch (_: Exception) { }
    }

    private suspend fun musicControl(action: MusicAction): Outcome {
        musicWarm()
        val access = Music.hasAccess(app)
        val track = Music.nowPlaying.value
        val audible = Music.audible.value
        val noAccess = " Ca să văd ce cântă și să controlez exact playerul, dă acces la notificări din Echipare, la Muzică."
        return when (action) {
            MusicAction.PLAY -> when {
                track?.playing == true -> Outcome.Done("Muzica merge deja: „${track.title}” de ${track.artist}.")
                track != null -> { Music.play(app); Outcome.Done("Reiau „${track.title}” de ${track.artist}.") }
                else -> { Music.play(app); Outcome.Done("Pornesc muzica." + if (!access) noAccess else "") }
            }
            MusicAction.PAUSE -> when {
                track?.playing == true || audible -> {
                    // Lista FORJA pornită cu vocea (fără antrenament): se închide de tot, nu doar pauză.
                    if (!WorkoutLink.active() && MusicStarter.origin.value == MusicSource.WORKOUT) MusicStarter.endWorkout(app, finished = false)
                    else Music.pause(app)
                    Outcome.Done("Pauză la muzică.")
                }
                else -> Outcome.Done("Nu cântă nimic acum.")
            }
            MusicAction.NEXT -> when {
                track != null || audible || !access -> { Music.next(app); Outcome.Done("Următoarea melodie.") }
                else -> Outcome.Done("Nu cântă nimic acum. Spune „pornește muzica”.")
            }
            MusicAction.PREVIOUS -> when {
                track != null || audible || !access -> { Music.previous(app); Outcome.Done("Melodia dinainte.") }
                else -> Outcome.Done("Nu cântă nimic acum. Spune „pornește muzica”.")
            }
            MusicAction.NOW_PLAYING -> when {
                track != null -> Outcome.Done("${if (track.playing) "Cântă" else "E pe pauză"} „${track.title}”${if (track.artist.isNotBlank()) " de ${track.artist}" else ""}, pe ${track.app}.")
                !access && audible -> Outcome.Done("Se aude ceva, dar nu văd ce." + noAccess)
                !access -> Outcome.Done("Nu cântă nimic acum." + noAccess)
                else -> Outcome.Done("Nu cântă nimic acum. Spune „pornește muzica” sau „pornește un playlist”.")
            }
        }
    }

    private suspend fun playPlaylist(cmd: VoiceCommand.PlayPlaylist): Outcome {
        val name = cmd.name.trim()
        val mixWord = cmd.mix ?: CommandParser.mixWord(name)
        if (name.isBlank() || mixWord != null) {
            // Lista FORJA, clădită din ce asculți de obicei (Mix / Noi / Vechi / Apreciate), în playerul tău.
            musicWarm()
            val mix = when (mixWord) {
                "new" -> Mix.NEW
                "old" -> Mix.OLD
                "liked" -> Mix.LIKED
                "mix" -> Mix.MIX
                else -> try { MusicStats.workoutMix(app) } catch (_: Exception) { Mix.MIX }
            }
            val access = Music.hasAccess(app)
            val target = WorkoutLink.live.value?.let { s -> com.forja.app.core.music.Playlist.targetMinutes(s.exercises.map { it.sets to it.reps }) } ?: 25
            // Fără antrenament pornit, muzica nu ia „permisul” de sală (un inventar o poate opri ca de obicei).
            MusicStarter.startWorkout(app, mix, target, tap = isForeground(), session = WorkoutLink.active())
            val label = when (mix) {
                Mix.MIX -> "mixul FORJA"
                Mix.NEW -> "lista cu melodii noi"
                Mix.OLD -> "lista cu melodii vechi"
                Mix.LIKED -> "melodiile apreciate"
            }
            return Outcome.Done(
                "Pornesc $label în playerul tău." +
                    (if (!access) " Fără acces la notificări pot doar să apăs Play; dă accesul din Echipare, la Muzică." else "") +
                    (if (!isForeground()) " Dacă playerul e închis, deschide FORJA și spune din nou." else "")
            )
        }
        // Un playlist cu nume: căutare de playlist în Spotify / YouTube Music / playerul implicit
        val spotify = "com.spotify.music"
        val ytm = "com.google.android.apps.youtube.music"
        val pkg = when (cmd.service) {
            MusicService.SPOTIFY -> spotify
            MusicService.YOUTUBE_MUSIC -> ytm
            else -> null
        }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/playlist")
            .putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, name)
            .putExtra(SearchManager.QUERY, name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when {
            pkg != null && isInstalled(pkg) -> i.setPackage(pkg)
            isInstalled(spotify) -> i.setPackage(spotify)
            isInstalled(ytm) -> i.setPackage(ytm)
        }
        if (i.resolveActivity(app.packageManager) == null) {
            return Outcome.Done("Nu am găsit un player care să pornească playlisturi după nume. Spune „pornește un playlist” pentru lista FORJA.")
        }
        val where = i.`package`?.let { if (it == spotify) " pe Spotify" else if (it == ytm) " pe YouTube Music" else "" } ?: ""
        return launch(i, "Pornesc playlistul „$name”$where.", "Playlist: $name")
    }

    // ── „pauză” / „continuă” / „următorul” fără obiect: după ce se întâmplă acum ───────

    private suspend fun pauseAny(): Outcome {
        val s = WorkoutLink.live.value
        if (s != null && !s.finished && !s.paused) return workout(WorkoutAction.PAUSE)
        musicWarm()
        val track = Music.nowPlaying.value
        if (track?.playing == true || Music.audible.value) return musicControl(MusicAction.PAUSE)
        if (s != null && !s.finished && s.paused) return Outcome.Done("Antrenamentul e deja în pauză. Spune „continuă” când ești gata.")
        return Outcome.Done("Nimic de pus pe pauză acum.")
    }

    private suspend fun continueAny(): Outcome {
        val s = WorkoutLink.live.value
        if (s != null && !s.finished && s.paused) return workout(WorkoutAction.RESUME)
        musicWarm()
        val track = Music.nowPlaying.value
        if (track != null && !track.playing) return musicControl(MusicAction.PLAY)
        if (ScreenAgent.isConnected() && ScreenAgent.foregroundPackage().isNotBlank() && ScreenAgent.foregroundPackage() != app.packageName) {
            return screen(ScreenAction.Scroll(down = true))
        }
        return Outcome.Done("Nimic de continuat acum. Spune „începe antrenamentul” sau „pornește muzica”.")
    }

    private suspend fun nextAny(): Outcome {
        // În antrenament, „următorul” e exercițiul (chiar cu muzica pornită — ea are „următoarea melodie”).
        val s = WorkoutLink.live.value
        if (s != null && !s.finished) return workout(WorkoutAction.NEXT_EXERCISE)
        musicWarm()
        val track = Music.nowPlaying.value
        if (track != null || Music.audible.value) return musicControl(MusicAction.NEXT)
        return Outcome.Done("Nimic de sărit acum. În antrenament spune „următorul exercițiu”; la muzică, „următoarea melodie”.")
    }

    // ── Focus / Detox digital ──────────────────────────────────────────────────────

    private suspend fun startFocus(cmd: VoiceCommand.StartFocus): Outcome {
        val hasUsage = try { FocusMonitorService.hasUsageAccess(app) } catch (_: Exception) { false }
        val hasOverlay = try { Settings.canDrawOverlays(app) } catch (_: Exception) { false }
        if (!hasUsage || !hasOverlay) {
            return Outcome.Done("Focusul are nevoie întâi de două acorduri Android: accesul la utilizare și afișarea peste alte aplicații. Le dai din ecranul Focus, cu butonul „Permite accesul”.", Route.FOCUS)
        }
        val minutes = cmd.detoxMinutes
        if (minutes != null) {
            val min = minutes.coerceIn(5, 24 * 60)
            app.prefs.setDetoxUntil(System.currentTimeMillis() + min * 60_000L)
            try { FocusMonitorService.start(app) } catch (_: Exception) { }
            return Outcome.Done("Detox digital pornit pentru ${spokenDuration(min)}: rămân doar telefonul, mesajele, setările și FORJA. Spune „oprește detoxul” ca să-l închei mai devreme.", Route.FOCUS)
        }
        if (app.prefs.focusActive.first()) return Outcome.Done("Focusul e deja pornit. Spune „cât mai am din focus” sau „oprește focusul”.")
        val rules = try { app.db.focusDao().enabledRules() } catch (_: Exception) { emptyList() }
        if (rules.isEmpty()) {
            return Outcome.Done("Alege întâi, din ecranul Focus, aplicațiile pe care să le blochez. Sau spune „pornește detoxul digital 30 de minute”.", Route.FOCUS)
        }
        try { FocusMonitorService.start(app) } catch (_: Exception) { return Outcome.Done("Nu am putut porni Focusul acum.") }
        app.prefs.setFocusActive(true)
        val names = rules.take(3).joinToString(", ") { it.label }
        return Outcome.Done("Focus pornit: ${rules.size} ${if (rules.size == 1) "aplicație blocată" else "aplicații blocate"} ($names). Respiră.", Route.FOCUS)
    }

    private suspend fun stopFocus(): Outcome {
        val detoxOn = app.prefs.detoxUntil.first() > System.currentTimeMillis()
        val focusOn = app.prefs.focusActive.first()
        if (!detoxOn && !focusOn) return Outcome.Done("Nici Focusul, nici detoxul digital nu sunt pornite.")
        val parts = mutableListOf<String>()
        if (detoxOn) { app.prefs.setDetoxUntil(0L); parts += "detoxul digital" }
        if (focusOn) { try { FocusMonitorService.stop(app) } catch (_: Exception) { }; app.prefs.setFocusActive(false); parts += "Focusul" }
        app.prefs.witherFocusTree()
        return Outcome.Done("Am oprit ${parts.joinToString(" și ")}. Cât ai rezistat, contează.", Route.FOCUS)
    }

    private suspend fun focusStatus(): String {
        val now = System.currentTimeMillis()
        val detoxUntil = app.prefs.detoxUntil.first()
        val focusOn = app.prefs.focusActive.first()
        val forest = try { app.prefs.focusForest.first() } catch (_: Exception) { Triple(0, 0, 0) }
        val focusMin = forest.first * 15 + forest.third / 60
        val sb = StringBuilder()
        if (detoxUntil > now) sb.append("Detoxul digital mai ține ${spokenDuration(((detoxUntil - now) / 60000).toInt().coerceAtLeast(1))}. ")
        if (focusOn) {
            val rules = try { app.db.focusDao().enabledRules() } catch (_: Exception) { emptyList() }
            sb.append("Focusul e pornit${if (rules.isNotEmpty()) ", cu ${rules.size} ${if (rules.size == 1) "aplicație blocată" else "aplicații blocate"}" else ""}. ")
        }
        if (detoxUntil <= now && !focusOn) sb.append("Nici Focusul, nici detoxul nu sunt pornite acum. ")
        if (focusMin > 0 || forest.first > 0) sb.append("Azi: $focusMin minute de focus, ${forest.first} ${if (forest.first == 1) "copac crescut" else "copaci crescuți"}.")
        return sb.toString().trim()
    }

    // ── Respirație, nutriție, somn ──────────────────────────────────────────────────

    private fun startBreath(): Outcome {
        BreathLinks.requestStart()
        return Outcome.Done("Respirăm împreună: inspiră patru secunde, ține, expiră, ține. Apasă „Oprește” sau ieși din ecran când vrei să te oprești.", Route.BREATH)
    }

    private suspend fun nutritionSummary(): String {
        val day = Fmt.epochDay()
        val meals = try { app.db.mealDao().mealsForDay(day).first() } catch (_: Exception) { emptyList() }
        val np = NutritionPrefs.of(app)
        val target = try { Targets.of(np.profile.first())?.kcal ?: np.kcalTarget.first() } catch (_: Exception) { 0 }
        val kcal = meals.sumOf { it.kcal }
        if (meals.isEmpty()) {
            return "Nicio masă în jurnal azi." + (if (target > 0) " Ținta ta e $target calorii." else "") + " Poți adăuga o masă din Nutriție, cu poza sau codul de bare."
        }
        val list = meals.joinToString(", ") { "${it.name.take(40)} (${it.kcal} calorii)" }
        val sb = StringBuilder("Azi ai ${meals.size} ${if (meals.size == 1) "masă" else "mese"}: $list. ")
        sb.append("În total $kcal calorii")
        if (target > 0) {
            val left = target - kcal
            sb.append(if (left >= 0) " din $target; mai ai loc de $left. " else " din $target; ai depășit ținta cu ${-left}. ")
        } else sb.append(". ")
        val p = meals.sumOf { it.protein }
        if (p > 0) sb.append("Proteine: $p grame.")
        return sb.toString().trim()
    }

    private suspend fun sleepSummary(): String {
        val s = try { app.db.sleepDao().lastFinished().first() } catch (_: Exception) { null }
            ?: return "Nu am nicio noapte înregistrată încă. Spune „pornește somnul” la culcare și „m-am trezit” dimineața."
        val end = s.endAt ?: s.startAt
        val min = ((end - s.startAt) / 60000).toInt()
        val whenSpoken = when {
            end >= Fmt.startOfDayMillis(0) -> "Azi-noapte"
            end >= Fmt.startOfDayMillis(1) -> "Noaptea trecută, ieri,"
            else -> "Ultima noapte înregistrată, pe " + DateTimeFormatter.ofPattern("d MMMM", Locale("ro")).format(java.time.Instant.ofEpochMilli(end).atZone(java.time.ZoneId.systemDefault())) + ","
        }
        val sb = StringBuilder("$whenSpoken ai dormit ${spokenDuration(min)}, de la ${Fmt.clock(s.startAt)} la ${Fmt.clock(end)}. ")
        if (s.score > 0) sb.append("Scor: ${s.score} din 100. ")
        if (s.deepMin > 0 || s.remMin > 0) sb.append("Somn profund ${spokenDuration(s.deepMin)}, REM ${spokenDuration(s.remMin)}. ")
        if (s.summary.isNotBlank()) sb.append(s.summary.trim())
        return sb.toString().trim()
    }

    // ── Casca în uniformă ────────────────────────────────────────────────────────────

    private suspend fun soldierStatus(): Outcome {
        val r = try { com.forja.app.core.soldier.Missions.sync(app, foreground = isForeground()) } catch (_: Exception) { null }
            ?: return Outcome.Done("Nu am putut citi starea Cascăi acum.", Route.SOLDIER)
        val s = r.state
        val rank = s.rank
        val next = com.forja.app.core.soldier.Ranks.next(rank)
        val done = r.today.filter { it.done }
        val left = r.today.filter { !it.done && it.mission.route != null }
        val sb = StringBuilder("Casca e ${rank.name}, cu ${s.earned} puncte")
        sb.append(if (next != null) "; mai are ${next.minPoints - s.earned} până la ${next.name}. " else " — gradul cel mai înalt. ")
        sb.append("Azi: ${done.size} ${if (done.size == 1) "misiune bifată" else "misiuni bifate"}")
        if (done.isNotEmpty()) sb.append(" (${done.joinToString(", ") { it.mission.short.lowercase() }})")
        sb.append(". ")
        if (left.isNotEmpty()) sb.append("Mai poți face: ${left.take(4).joinToString(", ") { it.mission.title.lowercase() }}. ")
        if (r.newPoints > 0) sb.append("Tocmai a primit ${r.newPoints} puncte. ")
        sb.append("Soldul pentru garderobă: ${s.balance} puncte.")
        return Outcome.Done(sb.toString(), Route.SOLDIER)
    }

    // ── Prieteni pe hartă, ture (GO) ────────────────────────────────────────────────

    private suspend fun friendWhere(cmd: VoiceCommand.FriendWhere): Outcome {
        val who = cmd.name.trim()
        val list = if (app.auth.currentUid != null) friendsSnapshot(2500) else emptyList()
        val matched = MapLinks.match(who, list.map { it.name })
        val f = list.firstOrNull { it.name == matched }
            // nu e un prieten FORJA: cu „pe hartă” / „prietenul”, un loc în aplicația de hărți; altfel, o întrebare pentru web
            ?: return if (cmd.onMap) openPlace(VoiceCommand.OpenPlace(who, navigate = false)) else webSearch("unde e $who")
        val now = System.currentTimeMillis()
        MapLinks.requestFriend(f.name)
        val seen = if (f.lat != null && (!f.ghost || f.viaFamily)) ", văzut ${Fmt.freshness(f.locUpdatedAt)}" else if (f.ghost) ", fără locație (fantomă)" else ", fără locație"
        val music = f.listening(now)?.let { ", ascultă $it" } ?: ""
        return Outcome.Done("${f.name} ${friendState(f)}$seen$music. Îl arăt pe hartă.", Route.MAP)
    }

    private fun sportLabel(sport: String) = when (sport) { "walk" -> "Plimbarea"; "ride" -> "Tura pe bicicletă"; else -> "Alergarea" }

    private fun startGo(cmd: VoiceCommand.StartGo): Outcome {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            return Outcome.NeedPermission(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                "Pentru tură am nevoie de locația telefonului. Permite accesul și pornesc.", cmd
            )
        }
        val st = GoTrackService.state.value
        if (st.recording) return Outcome.Done("${sportLabel(st.sport)} e deja pornită: ${Fmt.km(st.distanceM)} kilometri până acum.", Route.MAP)
        GoTrackService.start(app, cmd.sport)
        return Outcome.Done("${sportLabel(cmd.sport)} a pornit. Harta te urmărește; spune „oprește tura” la final.", Route.MAP)
    }

    private fun stopGo(): Outcome {
        val st = GoTrackService.state.value
        if (!st.recording) return Outcome.Done("Nicio tură pornită acum. Spune „pornește o alergare”, „o plimbare” sau „o tură pe bicicletă”.")
        GoTrackService.stop(app)
        val min = ((System.currentTimeMillis() - st.startedAt) / 60000).toInt()
        return Outcome.Done("${sportLabel(st.sport)} s-a încheiat: ${Fmt.km(st.distanceM)} kilometri în ${spokenDuration(min)}." + if (st.distanceM <= 30) " Prea scurtă ca să o salvez." else "", Route.MAP)
    }

    private fun isInstalled(pkg: String): Boolean = try {
        app.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { false }

    private fun start(i: Intent): Boolean = try {
        app.startActivity(i); true
    } catch (_: ActivityNotFoundException) { false } catch (_: Exception) { false }

    /** FORJA e vizibilă (sau are voie să deseneze peste alte aplicații)? Altfel Android ignoră în tăcere startActivity din fundal. */
    private fun isForeground(): Boolean = try {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    } catch (_: Exception) { true }

    private fun canLaunch(): Boolean = isForeground() || ScreenAgent.isConnected() || (try { Settings.canDrawOverlays(app) } catch (_: Exception) { false })

    /**
     * Pornește ceva pe ecran. Din fundal (ecran stins, altă aplicație în față) Android ar arunca intenția fără să spună:
     * lăsăm o notificare care o deschide la o atingere și îi spunem utilizatorului ce să facă.
     */
    private fun launch(i: Intent, spokenOk: String, title: String, readAfterMs: Long = 0): Outcome = launchResult(i, spokenOk, title, readAfterMs).first

    /** Ca [launch], plus dacă activitatea chiar a pornit acum (nu doar notificarea). */
    private fun launchResult(i: Intent, spokenOk: String, title: String, readAfterMs: Long): Pair<Outcome, Boolean> {
        if (canLaunch()) {
            return if (start(i)) Outcome.Done(spokenOk, readScreenAfterMs = if (ScreenAgent.isConnected()) readAfterMs else 0) to true
            else Outcome.Done("Nu am putut deschide. Mai încearcă.") to false
        }
        return try {
            val pi = PendingIntent.getActivity(app, (System.currentTimeMillis() and 0x7fffffff).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(app, "voice")
                .setSmallIcon(R.drawable.ic_voice_mic)
                .setContentTitle(title)
                .setContentText("Apasă ca să continui comanda „Hei FORJA”.")
                .setAutoCancel(true).setContentIntent(pi).build()
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(LAUNCH_NOTIF_ID, n)
            Outcome.Done("Telefonul e blocat sau FORJA nu e pe ecran. Deblochează-l și apasă notificarea FORJA ca să continui.") to false
        } catch (_: Exception) {
            Outcome.Done("Deschide FORJA pe ecran și repetă comanda.") to false
        }
    }

    private companion object {
        const val SMS_SENT_ACTION = "com.forja.app.voice.SMS_SENT"
        const val LAUNCH_NOTIF_ID = 74
    }
}
