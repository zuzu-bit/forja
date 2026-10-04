package com.forja.app.core.voice

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import com.forja.app.ForjaApp
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
    data class Body(val draft: VoiceCommand.SendMessage, val contact: Contact) : Pending()
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

    /** Ultima permisiune cerută a fost refuzată — folosim căile care nu au nevoie de ea. */
    @Volatile var permissionJustDenied: Boolean = false

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    }

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    suspend fun execute(cmd: VoiceCommand, confirmSend: Boolean, lastSpoken: String): Outcome = try {
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
                    if (confirmSend) Outcome.Ask(confirmQuestion(pending.contact, body, draft.channel), Pending.Confirm(draft, pending.contact))
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
        if (directNumber != null) {
            contact = Contact(directNumber, directNumber)
        } else {
            if (!Contacts.hasPermission(app)) {
                if (permissionJustDenied) return Outcome.Done("Fără acces la contacte nu pot găsi numărul. Spune numărul de telefon sau pornește permisiunea din setări.")
                return Outcome.NeedPermission(
                    listOf(Manifest.permission.READ_CONTACTS),
                    "Am nevoie de acces la contacte ca să găsesc numărul. Permite accesul și reiau.", cmd
                )
            }
            val contacts = Contacts.all(app)
            if (cmd.splitByContact) {
                val r = Contacts.resolvePrefix(contacts, cmd.recipient)
                if (r == null) {
                    val first = cmd.recipient.split(" ").first()
                    return Outcome.Ask("Nu am găsit pe „$first” în agendă. Cui să trimit?", Pending.Recipient(cmd.copy(recipient = "", splitByContact = false)))
                }
                contact = r.first
                body = r.second
            } else {
                contact = Contacts.find(contacts, cmd.recipient)
                    ?: return Outcome.Ask("Nu am găsit pe „${cmd.recipient}” în agendă. Cui să trimit?", Pending.Recipient(cmd.copy(recipient = "")))
            }
        }
        if (body.isBlank()) {
            return Outcome.Ask("Ce mesaj să-i trimit lui ${contact.name}?", Pending.Body(cmd.copy(recipient = contact.number, splitByContact = false), contact))
        }
        val draft = cmd.copy(recipient = contact.number, body = body, splitByContact = false)
        if (confirmSend && !cmd.confirmed) return Outcome.Ask(confirmQuestion(contact, body, cmd.channel), Pending.Confirm(draft, contact))
        return deliver(draft, contact)
    }

    /** Trimite efectiv: SMS direct (dacă avem voie) sau aplicația de mesaje / WhatsApp deschisă cu textul gata scris. */
    private fun deliver(draft: VoiceCommand.SendMessage, contact: Contact): Outcome {
        val number = contact.number
        val body = draft.body
        if (draft.channel == MessageChannel.WHATSAPP) {
            val digits = number.replace(Regex("[^\\d+]"), "").removePrefix("+")
            val url = "https://wa.me/$digits?text=" + URLEncoder.encode(body, "UTF-8")
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isInstalled("com.whatsapp")) i.setPackage("com.whatsapp")
            else if (isInstalled("com.whatsapp.w4b")) i.setPackage("com.whatsapp.w4b")
            return if (start(i)) Outcome.Done("Am deschis WhatsApp cu mesajul pentru ${contact.name}. Apasă „Trimite”.")
            else Outcome.Done("Nu am găsit WhatsApp pe telefon.")
        }
        if (granted(Manifest.permission.SEND_SMS)) {
            return try {
                val sms = (if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault())
                    ?: throw IllegalStateException("SmsManager indisponibil")
                val parts = sms.divideMessage(body)
                if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
                else sms.sendTextMessage(number, null, body, null, null)
                Outcome.Done("Trimis lui ${contact.name}: „$body”.")
            } catch (e: Exception) {
                openSmsApp(number, body, contact)
            }
        }
        if (!permissionJustDenied) {
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
        return if (start(i)) Outcome.Done("Am deschis mesajul către ${contact.name}, cu textul scris. Apasă „Trimite”.")
        else Outcome.Done("Nu am găsit o aplicație de mesaje.")
    }

    // ── Apeluri ─────────────────────────────────────────────────────────────────────

    private fun call(cmd: VoiceCommand.Call): Outcome {
        if (cmd.who.isBlank()) return Outcome.Ask("Pe cine să sun?", Pending.CallWho)
        val number: String
        val label: String
        val direct = Contacts.asNumber(cmd.who)
        if (direct != null) { number = direct; label = direct.chunked(3).joinToString(" ") }
        else {
            if (!Contacts.hasPermission(app)) {
                if (permissionJustDenied) return Outcome.Done("Fără acces la contacte nu pot găsi numărul lui ${cmd.who}.")
                return Outcome.NeedPermission(listOf(Manifest.permission.READ_CONTACTS), "Am nevoie de acces la contacte ca să-l sun pe ${cmd.who}.", cmd)
            }
            val c = Contacts.find(app, cmd.who) ?: return Outcome.Ask("Nu am găsit pe „${cmd.who}” în agendă. Pe cine să sun?", Pending.CallWho)
            number = c.number; label = c.name
        }
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val emergency = number.length <= 4
        if (!emergency && granted(Manifest.permission.CALL_PHONE)) {
            if (start(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return Outcome.Done("Sun pe $label.")
        } else if (!emergency && !permissionJustDenied) {
            return Outcome.NeedPermission(listOf(Manifest.permission.CALL_PHONE), "Ca să sun direct, am nevoie de permisiunea pentru apeluri.", cmd)
        }
        return if (start(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            Outcome.Done("Am format numărul lui $label. Apasă butonul de apel.")
        else Outcome.Done("Nu am găsit aplicația de telefon.")
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
        return if (start(i)) Outcome.Done(if (q.isBlank()) "Pun muzică pe $label." else "Pun „$q” pe $label.") else null
    }

    private suspend fun youtube(q: String): Outcome {
        val yt = "com.google.android.youtube"
        val id = withTimeoutOrNull(6500) { firstYoutubeVideoId(q) }
        if (id != null) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isInstalled(yt)) i.setPackage(yt)
            if (start(i)) return Outcome.Done("Pun „$q” pe YouTube.")
        }
        val search = Intent(Intent.ACTION_SEARCH).setPackage(yt).putExtra("query", q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isInstalled(yt) && start(search)) return Outcome.Done("Caut „$q” pe YouTube. Alege primul rezultat.")
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (start(web)) Outcome.Done("Caut „$q” pe YouTube în browser.") else Outcome.Done("Nu am găsit YouTube pe telefon.")
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
        when (name) {
            "camera", "aparatul foto", "aparat foto" ->
                return if (start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) Outcome.Done("Deschid camera.") else Outcome.Done("Nu am găsit camera.")
            "setari", "setarile", "settings", "setarile telefonului" ->
                return if (start(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) Outcome.Done("Deschid setările telefonului.") else Outcome.Done("Nu pot deschide setările.")
            "telefon", "telefonul", "phone", "dialer", "apeluri" ->
                return if (start(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) Outcome.Done("Deschid telefonul.") else Outcome.Done("Nu am găsit aplicația de telefon.")
            "mesaje", "mesajele", "messages", "sms" -> {
                val pkg = try { Telephony.Sms.getDefaultSmsPackage(app) } catch (_: Exception) { null }
                val i = pkg?.let { app.packageManager.getLaunchIntentForPackage(it) }
                return if (i != null && start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) Outcome.Done("Deschid mesajele.") else Outcome.Done("Nu am găsit aplicația de mesaje.")
            }
        }
        knownApps[name]?.let { pkg ->
            app.packageManager.getLaunchIntentForPackage(pkg)?.let { i ->
                if (start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return Outcome.Done("Deschid $nameRaw.")
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
        return if (start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) Outcome.Done("Deschid ${found.first}.") else Outcome.Done("Nu pot porni „$nameRaw”.")
    }

    // ── Diverse ─────────────────────────────────────────────────────────────────────

    private fun setAlarm(cmd: VoiceCommand.SetAlarm): Outcome {
        val ok = SystemAlarm.set(app, cmd.hour, cmd.minute, "FORJA")
        val t = String.format(Locale.ROOT, "%d:%02d", cmd.hour, cmd.minute)
        return if (ok) Outcome.Done("Alarma e pusă la $t.") else Outcome.Done("Nu am găsit o aplicație de ceas pentru alarmă.")
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
            sb.append("Aseară ai dormit ${Fmt.durationHm(min)}. ")
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
}
