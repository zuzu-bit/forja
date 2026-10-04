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
import com.forja.app.core.sleep.SleepTrackService
import com.forja.app.core.sleep.SystemAlarm
import com.forja.app.core.util.Fmt
import com.forja.app.navigation.Route
import kotlinx.coroutines.flow.first
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
}

/** Rezultatul unei comenzi: ce se spune și ce se întâmplă. */
sealed class Outcome {
    data class Done(val spoken: String, val navigate: String? = null) : Outcome()
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
            is VoiceCommand.Navigate -> Outcome.Done("Deschid ${cmd.target.spoken}.", cmd.target.route)
            is VoiceCommand.SetAlarm -> setAlarm(cmd)
            VoiceCommand.StartSleep -> startSleep()
            VoiceCommand.StopSleep -> {
                SleepTrackService.stop(app)
                Outcome.Done("Bună dimineața. Sesiunea de somn s-a încheiat.", Route.SLEEP)
            }
            VoiceCommand.TellTime -> Outcome.Done(spokenTime())
            VoiceCommand.TellDate -> Outcome.Done(spokenDate())
            VoiceCommand.Progress -> Outcome.Done(progress())
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
        }
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

    private fun openApp(nameRaw: String): Outcome {
        val name = VoiceText.normalize(nameRaw)
        fun open(i: Intent, spoken: String, title: String, missing: String): Outcome =
            if (i.resolveActivity(app.packageManager) == null) Outcome.Done(missing) else launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), spoken, title)
        when (name) {
            "camera", "aparatul foto", "aparat foto" ->
                return open(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "Deschid camera.", "Camera", "Nu am găsit camera.")
            "setari", "setarile", "settings", "setarile telefonului" ->
                return open(Intent(Settings.ACTION_SETTINGS), "Deschid setările telefonului.", "Setări", "Nu pot deschide setările.")
            "telefon", "telefonul", "phone", "dialer", "apeluri" ->
                return open(Intent(Intent.ACTION_DIAL), "Deschid telefonul.", "Telefon", "Nu am găsit aplicația de telefon.")
            "mesaje", "mesajele", "messages", "sms" -> {
                val pkg = try { Telephony.Sms.getDefaultSmsPackage(app) } catch (_: Exception) { null }
                val i = pkg?.let { app.packageManager.getLaunchIntentForPackage(it) } ?: return Outcome.Done("Nu am găsit aplicația de mesaje.")
                return launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Deschid mesajele.", "Mesaje")
            }
        }
        knownApps[name]?.let { pkg ->
            app.packageManager.getLaunchIntentForPackage(pkg)?.let { i ->
                return launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Deschid $nameRaw.", nameRaw)
            }
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
        if (found == null || bestScore < 60) return Outcome.Done("Nu am găsit aplicația „$nameRaw” pe telefon.")
        val i = app.packageManager.getLaunchIntentForPackage(found.second) ?: return Outcome.Done("Nu pot porni „$nameRaw”.")
        return launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Deschid ${found.first}.", found.first)
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
        val uid = app.auth.currentUid ?: return "Nu ești conectat. Intră în cont ca să-ți vezi prietenii."
        val list = withTimeoutOrNull(5000) { app.friends.friendsFlow(uid).first() } ?: return "Nu am putut citi lista de prieteni acum."
        if (list.isEmpty()) return "Încă nu ai prieteni în FORJA. Dă-le codul tău de invitație din Profil."
        val parts = list.map { f ->
            val first = f.name.split(' ').first()
            val state = when {
                f.ghost -> "e fantomă"
                f.state == "run" -> "aleargă acum"
                f.state == "ride" -> "e pe roți"
                f.state == "walk" -> "se plimbă"
                f.state == "gym" -> "e la sală"
                f.state == "sleep" -> "doarme"
                else -> "e liniștit"
            }
            "$first $state"
        }
        return "Ai ${list.size} ${if (list.size == 1) "prieten" else "prieteni"}: " + parts.joinToString(", ") + "."
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

    private fun canLaunch(): Boolean = isForeground() || (try { Settings.canDrawOverlays(app) } catch (_: Exception) { false })

    /**
     * Pornește ceva pe ecran. Din fundal (ecran stins, altă aplicație în față) Android ar arunca intenția fără să spună:
     * lăsăm o notificare care o deschide la o atingere și îi spunem utilizatorului ce să facă.
     */
    private fun launch(i: Intent, spokenOk: String, title: String): Outcome {
        if (canLaunch()) {
            return if (start(i)) Outcome.Done(spokenOk) else Outcome.Done("Nu am putut deschide. Mai încearcă.")
        }
        return try {
            val pi = PendingIntent.getActivity(app, (System.currentTimeMillis() and 0x7fffffff).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(app, "voice")
                .setSmallIcon(R.drawable.ic_voice_mic)
                .setContentTitle(title)
                .setContentText("Apasă ca să continui comanda „Hei FORJA”.")
                .setAutoCancel(true).setContentIntent(pi).build()
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(LAUNCH_NOTIF_ID, n)
            Outcome.Done("Telefonul e blocat sau FORJA nu e pe ecran. Deblochează-l și apasă notificarea FORJA ca să continui.")
        } catch (_: Exception) {
            Outcome.Done("Deschide FORJA pe ecran și repetă comanda.")
        }
    }

    private companion object {
        const val SMS_SENT_ACTION = "com.forja.app.voice.SMS_SENT"
        const val LAUNCH_NOTIF_ID = 74
    }
}
