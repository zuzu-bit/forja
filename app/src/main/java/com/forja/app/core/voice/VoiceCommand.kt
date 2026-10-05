package com.forja.app.core.voice

import com.forja.app.navigation.Route
import java.util.Locale

/** Pe ce canal pleacă un mesaj dictat. */
enum class MessageChannel { SMS, WHATSAPP }

/** Unde se pune muzica cerută. */
enum class MusicService(val spoken: String) {
    YOUTUBE("YouTube"), YOUTUBE_MUSIC("YouTube Music"), SPOTIFY("Spotify"), ANY("player")
}

/** Ecranele FORJA la care se poate ajunge cu vocea. */
enum class AppTarget(val route: String, val spoken: String) {
    DASHBOARD(Route.DASHBOARD, "Ziua ta"),
    WORKOUT(Route.WORKOUT, "Antrenament"),
    MAP(Route.MAP, "Harta"),
    NUTRITION(Route.NUTRITION, "Nutriție"),
    SLEEP(Route.SLEEP, "Somn"),
    FOCUS(Route.FOCUS, "Focus"),
    BREATH(Route.BREATH, "Respiră"),
    PROFILE(Route.PROFILE, "Profil"),
    ACTIVITIES(Route.ACTIVITIES, "Activități"),
    SCANNER(Route.SCANNER, "Scanner de cod de bare"),
    MEAL_CAMERA(Route.MEAL_CAMERA, "Poză la masă"),
    PERMISSIONS(Route.PERMISSIONS, "Permisiuni"),
    CLEANUP(Route.CLEANUP, "Curățenie"),
    VOICE(Route.VOICE, "Asistent vocal")
}

/** Comenzile pe care le înțelege „Hei FORJA" — în română și engleză. */
sealed class VoiceCommand {
    /**
     * Mesaj către un contact. [recipient] e numele așa cum a fost rostit; dacă [splitByContact]
     * e true, numele și textul sunt lipite („ion ajung in 10 minute") și se separă pe baza
     * agendei. Un câmp gol înseamnă că asistentul mai întreabă o dată („Cui?", „Ce mesaj?").
     */
    data class SendMessage(
        val recipient: String,
        val body: String,
        val channel: MessageChannel = MessageChannel.SMS,
        val splitByContact: Boolean = false,
        /** Utilizatorul a confirmat deja textul (ex. după acordarea permisiunii SMS). */
        val confirmed: Boolean = false
    ) : VoiceCommand()

    data class Call(val who: String) : VoiceCommand()
    data class PlayMusic(val query: String, val service: MusicService) : VoiceCommand()
    data class OpenApp(val name: String) : VoiceCommand()
    /** „deschide X și fă Y”: aplicația, apoi acțiunea — în ea (pe ecran) sau prin intenție. */
    data class OpenAppThen(val app: String, val then: VoiceCommand) : VoiceCommand()
    /** Căutare pe Google / internet. */
    data class WebSearch(val query: String) : VoiceCommand()
    /** Un loc pe hartă: arată-l sau pornește navigarea; [app] = „waze” / „maps” / null. */
    data class OpenPlace(val place: String, val navigate: Boolean, val app: String? = null) : VoiceCommand()
    /** Acțiune pe ecranul aplicației din față (prin serviciul de accesibilitate). */
    data class Screen(val action: ScreenAction) : VoiceCommand()
    /** Deschide setările de accesibilitate ca să pornească controlul ecranului. */
    object EnableScreenControl : VoiceCommand()
    data class Navigate(val target: AppTarget) : VoiceCommand()
    data class SetAlarm(val hour: Int, val minute: Int) : VoiceCommand()
    object StartSleep : VoiceCommand()
    object StopSleep : VoiceCommand()
    object TellTime : VoiceCommand()
    object TellDate : VoiceCommand()
    object Progress : VoiceCommand()
    object Friends : VoiceCommand()
    object Help : VoiceCommand()
    object Repeat : VoiceCommand()
    object Stop : VoiceCommand()
    object StopListening : VoiceCommand()
    data class Unknown(val text: String) : VoiceCommand()
}

/** Ce se poate face pe ecranul altei aplicații. */
sealed class ScreenAction {
    object Read : ScreenAction()
    data class Tap(val target: String) : ScreenAction()
    data class Type(val text: String) : ScreenAction()
    data class Scroll(val down: Boolean) : ScreenAction()
    data class Search(val query: String) : ScreenAction()
    object Enter : ScreenAction()
    object Back : ScreenAction()
    object Home : ScreenAction()
}

/** Normalizare text pentru potrivire: minuscule, fără diacritice, fără punctuație zgomotoasă. */
object VoiceText {
    fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace('ă', 'a').replace('â', 'a').replace('î', 'i')
        .replace('ș', 's').replace('ş', 's').replace('ț', 't').replace('ţ', 't')
        .replace(Regex("[\"„”“‘’«»']"), "")
        .replace(Regex("[,;!?]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace(Regex("[.]+$"), "")
        .trim()

    private val WHATSAPP_RAW = Regex("\\b(?:pe|on|prin|via|through|in|la)?\\s*(?:whatsapp|whats app|whatsap|uatsap|wasap|wats app)\\b", RegexOption.IGNORE_CASE)
    private val POLITE_RAW = Regex("[\\s,]*(?:te rog|please|mul[tț]umesc|thanks|thank you)[\\s.!]*$", RegexOption.IGNORE_CASE)

    /** Textul original, curățat ca cel normalizat (fără „pe whatsapp", fără „te rog" la final), cu majuscule și diacritice. */
    fun cleanRaw(raw: String): String =
        POLITE_RAW.replace(WHATSAPP_RAW.replace(raw.trim(), " "), "").replace(Regex("\\s+"), " ").trim().replace(Regex("[.!?]+$"), "").trim()

    /** Ca [cleanRaw], dar păstrează orice cuvânt (și „whatsapp"): pentru ținte de pe ecran, căutări, locuri. */
    fun cleanRawLight(raw: String): String =
        POLITE_RAW.replace(raw.trim(), "").replace(Regex("\\s+"), " ").trim().replace(Regex("[.!?]+$"), "").trim()

    /** Ultimele [words] cuvinte din [raw] — corpul mesajului e mereu coada frazei; așa păstrăm diacriticele și majusculele. */
    fun tailWords(raw: String, words: Int): String {
        if (words <= 0) return ""
        val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return parts.takeLast(minOf(words, parts.size)).joinToString(" ")
    }

    private const val WAKE_NAMES = "forja|forjă|forta|forza|forgia|forge|forgea|forjia|fortza|horja|hoja|foja|forija|forcha|forjea|forsha"
    private const val WAKE_HEY = "hei|hey|hi|he|ei|ey|ok|okay|oche|salut|alo|hello"

    /** „Hei FORJA" oriunde în frază (modul mereu-la-ascultare cere prefixul sau scrierea exactă). */
    private val WAKE_STRICT = Regex("(?:\\b(?:$WAKE_HEY)\\s+(?:$WAKE_NAMES)\\b|\\bforja\\b)[\\s,.:!-]*")

    /** Orice variantă, și fără „hei" — folosit după ce utilizatorul a apăsat deja microfonul. */
    private val WAKE_LOOSE = Regex("^(?:(?:$WAKE_HEY)\\s+)?(?:$WAKE_NAMES)\\b[\\s,.:!-]*")

    /** Conține cuvântul de trezire? Întoarce textul de după el sau null. */
    fun afterWakeWord(normalized: String): String? {
        val m = WAKE_STRICT.find(normalized) ?: return null
        return normalized.substring(m.range.last + 1).trim()
    }

    /** Scoate „hei forja" de la începutul frazei, dacă există. */
    fun stripWakeWord(normalized: String): String = WAKE_LOOSE.replace(normalized, "").trim()

    private val UNITS = mapOf(
        "zero" to 0, "unu" to 1, "una" to 1, "un" to 1, "o" to 1, "doi" to 2, "doua" to 2, "trei" to 3, "patru" to 4,
        "cinci" to 5, "sase" to 6, "sapte" to 7, "opt" to 8, "noua" to 9, "zece" to 10,
        "unsprezece" to 11, "unspe" to 11, "doisprezece" to 12, "douasprezece" to 12, "doispe" to 12,
        "treisprezece" to 13, "treispe" to 13, "paisprezece" to 14, "paispe" to 14, "patrusprezece" to 14,
        "cincisprezece" to 15, "cinspe" to 15, "saisprezece" to 16, "saispe" to 16, "saptesprezece" to 17,
        "saptespe" to 17, "optsprezece" to 18, "optspe" to 18, "nouasprezece" to 19, "nouaspe" to 19,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf(
        "douazeci" to 20, "treizeci" to 30, "patruzeci" to 40, "cincizeci" to 50,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50
    )

    /** „sapte", „douazeci si cinci", „7", „twenty five" → număr; altfel null. */
    fun numberFrom(words: List<String>): Int? {
        if (words.isEmpty()) return null
        words.first().toIntOrNull()?.let { return it }
        val first = words[0]
        TENS[first]?.let { tens ->
            val rest = words.drop(1).filter { it != "si" && it != "and" }
            val unit = rest.firstOrNull()?.let { UNITS[it] }
            return tens + (if (unit != null && unit < 10) unit else 0)
        }
        return UNITS[first]
    }

    fun isNumberWord(w: String): Boolean = w.toIntOrNull() != null || UNITS.containsKey(w) || TENS.containsKey(w)
}

/**
 * Parser de comenzi — reguli simple, în ordine, pe textul normalizat.
 * Fără rețea, fără AI: rulează instant, și offline.
 */
object CommandParser {

    private val PLACEHOLDER_RECIPIENT = setOf(
        "this contact", "that contact", "the contact", "a contact", "someone", "somebody", "him", "her", "them",
        "acest contact", "contactul asta", "contactul acesta", "contactul", "un contact", "cineva", "lui", "ei",
        "el", "ea", "persoana asta", "acestei persoane", "acestui contact", "acelui contact", "contact"
    )
    private val PLACEHOLDER_BODY = setOf(
        "this text", "this", "that", "the text", "this message", "that message", "the message", "a message",
        "a text", "something", "asta", "acest text", "textul asta", "mesajul asta", "acest mesaj", "mesajul",
        "textul", "ceva", "un mesaj", "un text", "text", "message", "mesaj"
    )

    private val WHATSAPP = Regex("\\b(?:pe|on|prin|via|through|in|la)?\\s*(?:whatsapp|whats app|whatsap|uatsap|wasap|wats app)\\b")

    private val STOP = Regex("^(?:stop|opreste(?:-te)?|oprestete|gata|anuleaza|anulare|renunta|taci|lasa|lasa-ma|cancel|never ?mind|forget it|quiet|shut up|nimic|nothing)(?:\\s+(?:te rog|please|tot|acum|now|it))*\\s*$")
    private val STOP_LISTENING = Regex("^(?:(?:opreste|inchide|dezactiveaza|stop|turn off|disable)\\s+(?:ascultarea|ascultatul|asistentul|microfonul|listening|the assistant|the microphone|the mic)|nu mai asculta|stop listening|go to sleep|dormi)\\b")
    private val HELP = Regex("^(?:ajutor|ajuta-ma|ajutama|ce (?:poti|stii|pot) (?:sa )?(?:fac[ia]?|faci)|ce comenzi|comenzi|lista de comenzi|help|what can you do|what can i say|commands)\\b")
    private val REPEAT = Regex("^(?:repeta|mai spune o data|mai zi o data|inca o data|repeat|say (?:that |it )?again|again|what\\s*$)")
    private val TIME = Regex("^(?:(?:cat|ce|care) (?:e|este|i) (?:ceasul|ora)|cat e ora|ce ora (?:e|este|avem)|ora exacta|what time is it|what is the time|whats the time|what's the time|the time|time)\\b")
    private val DATE = Regex("^(?:(?:ce|care) (?:zi|data) (?:e|este|avem)(?: azi| astazi)?|ce (?:zi|data) e azi|in ce zi suntem|data de azi|what day is it|what is the date|whats the date|what's the date|todays date|today's date|what is today|what day is today)\\b")
    private val PROGRESS = Regex("^(?:cum stau(?: azi| astazi)?|cum merge(?: azi)?|(?:citeste|spune|arata)(?:-mi|mi)? (?:progresul|ziua|rezumatul|statisticile)|progresul(?: meu)?|ce am facut azi|rezumatul zilei|statistici|my progress|how am i doing|read (?:me )?my (?:day|progress|stats)|(?:daily|today's|todays) summary|summary)\\b")
    private val FRIENDS = Regex("^(?:cine (?:e|este|mai e|mai este) (?:online|activ|activa|pe harta|treaz)|unde (?:sunt|is|imi sunt) prietenii|prietenii(?: mei)?|ce fac prietenii(?: mei)?|who is online|who's online|whos online|where are my friends|my friends|what are my friends doing)\\b")

    private val SLEEP_START = Regex("^(?:(?:porneste|incepe|start|activeaza|begin|pune)\\s+(?:sesiunea de\\s+|the\\s+)?(?:somn(?:ul)?|sleep(?: tracking| session| mode)?|dormitul|monitorizarea somnului|urmarirea somnului)|ma culc|ma duc la culcare|merg la culcare|noapte buna|good ?night|going to (?:sleep|bed)|i'?m going to (?:sleep|bed)|time to sleep)\\b")
    private val SLEEP_STOP = Regex("^(?:(?:opreste|termina|stop|end|incheie|finish)\\s+(?:sesiunea de\\s+|the\\s+)?(?:somn(?:ul)?|sleep(?: tracking| session| mode)?|dormitul|monitorizarea somnului)|m-am trezit|mam trezit|sunt treaz|buna dimineata|good morning|i'?m awake|i woke up|wake up)\\b")

    private val ALARM = Regex("^(?:(?:seteaza|pune|porneste|programeaza|fixeaza|trezeste-ma|trezestema|set|wake me(?: up)?|put|schedule)\\s+)(?:te rog\\s+|please\\s+)?(?:o\\s+|un\\s+|an?\\s+|the\\s+|my\\s+)?(?:alarma|alarm|desteptator(?:ul)?|ceasul|wake-?up)?\\s*(?:la|pentru|pe|at|for|to)?\\s*(?:ora\\s+|the hour\\s+)?(.+)$")

    private val MSG_RO = Regex("^(?:te rog\\s+)?(?:trimite(?:-i|i|-mi)?|scrie(?:-i|i)?|da(?:-i|i)?|expediaza|transmite(?:-i|i)?)\\s+(?:te rog\\s+)?(?:un\\s+|o\\s+|acest\\s+|aceasta\\s+|acel\\s+|niste\\s+)?(?:mesaj(?:ul)?|sms(?:-ul|ul)?|text(?:ul)?|mesaje|mesajul asta|textul asta|un mesaj|un text)?\\s*(?:asta\\s+|acesta\\s+|acest\\s+)?(?:lui|la|catre|pentru)?\\s*(.+)$")
    private val TELL_RO = Regex("^(?:spune(?:-i|i)?|zi(?:-i|i)?|zice(?:-i|i)?|anunta(?:-l|-o|l|o)?)\\s+(?:te rog\\s+)?(?:lui|la|catre|pe)?\\s*(.+)$")
    private val MSG_EN = Regex("^(?:please\\s+)?(?:send|text|message|write|shoot|forward)\\s+(?:please\\s+)?(?:a\\s+|the\\s+|this\\s+|that\\s+|an\\s+|my\\s+)?(?:text message|message|sms|text|txt|msg)?\\s*(?:to\\s+)?(.+)$")
    private val TELL_EN = Regex("^(?:tell|let)\\s+(.+)$")

    private val SEPARATORS_RO = listOf(
        ":", " cu textul ", " cu mesajul ", " cu urmatorul text ", " urmatorul mesaj ", " urmatorul text ",
        " urmatoarele ", " mesajul ", " textul ", " mesaj ", " text ", " spunand ", " zicand ", " sa-i spui ",
        " sa-i spun ", " sai spui ", " continutul ", " ca ", " sa "
    )
    private val SEPARATORS_EN = listOf(
        ":", " a message saying ", " a text saying ", " a message that says ", " a message ", " a text ",
        " the message ", " the text ", " message ", " text ", " with the text ", " with text ", " that says ",
        " saying ", " says ", " telling him ", " telling her ", " telling them ", " the following ",
        " as follows ", " that ", " to say "
    )
    private val BODY_LEAD = Regex("^(?:ca|sa|saying|that|says|to say|the following|urmatorul|urmatoarea|urmatoarele|textul|mesajul)\\s+")

    private val CALL_RO = Regex("^(?:suna|apeleaza|cheama|telefoneaza|formeaza)(?:-l|-o|-i|l|o|i)?\\s*(?:te rog\\s+)?(?:pe|la|lui)?\\s*(.+)$")
    private val CALL_RO2 = Regex("^(?:da(?:-i|i)?\\s+(?:un\\s+)?(?:telefon|apel)|fa(?:-i|i)?\\s+un\\s+apel)\\s+(?:lui|la|pe)?\\s*(.+)$")
    private val CALL_EN = Regex("^(?:call|dial|phone|ring)(?:\\s+up)?\\s+(.+)$")

    private val MUSIC_SERVICE = Regex("\\b(?:pe|on|de pe|from|in|la|via|cu|with)\\s+(youtube music|yt music|you tube music|youtube|yutub|you tube|iutub|u tube|iu tub|spotify|spotifai|spotifai)\\b")
    private val MUSIC_VERB = Regex("^(?:pune(?:-mi|mi)?|porneste|da drumul la|da-i drumul la|dai drumul la|canta(?:-mi|mi)?|reda|ruleaza|play|put on|cauta(?:-mi|mi)?|search for|search|find|gaseste(?:-mi|mi)?|asculta|listen to|arata(?:-mi|mi)?)(?:\\s+te rog|\\s+please)?\\s*(?:la\\s+|the\\s+)?(?:(?:muzica|melodia|piesa|cantecul|un cantec|o melodie|o piesa|niste muzica|videoclipul|clipul|videoul|song|the song|music|some music|a song|video|the video|a video|videos|track|the track)\\s*)?(?:(?:asta|aceasta|acesta|acest|this|that|the)\\s*)?(.*)$")
    private val MUSIC_NOUN_ONLY = Regex("^(?:muzica|melodia|piesa|cantecul|song|music|video)\\s*(?:asta|this)?\\s*$")

    // „deschide X și fă Y” — aplicația, apoi ce să facă în ea
    private val COMPOUND = Regex("^(?:deschide(?:-mi|mi)?|porneste|lanseaza|intra in|open|launch|start|go to)\\s+(?:te rog\\s+|please\\s+)?(?:aplicatia\\s+|app\\s+|the app\\s+|the\\s+)?([a-z0-9][a-z0-9 .+'-]{0,30}?)\\s+(?:si|apoi|and then|and|then|dupa care)\\s+(.+)$")
    private val YT_APPS = setOf("youtube", "iutub", "yutub", "you tube", "u tube")
    private val WEB_APPS = setOf("google", "chrome", "browser", "browserul", "internet", "internetul", "web", "firefox", "brave", "edge", "samsung internet", "opera", "the browser", "the internet")
    private val MAP_APPS = setOf("maps", "google maps", "harti", "hartile", "harta google", "waze", "navigatia", "navigation")
    private val SEARCH_VERB = Regex("^(?:cauta(?:-mi|mi)?|cautati|search(?: for| about| up)?|look(?: for| up)?|find|google|go to|gaseste(?:-mi|mi)?|arata(?:-mi|mi)?|show(?: me)?)\\s+(?:pe\\s+(?:google|internet|net|web)\\s+|on\\s+(?:google|the internet|the web|internet)\\s+|online\\s+|the web\\s+|the internet\\s+)?(?:for\\s+|about\\s+|despre\\s+|informatii despre\\s+|something about\\s+|ceva despre\\s+)?(.+)$")
    private val PLAY_VERB = Regex("^(?:pune(?:-mi|mi)?|porneste|da drumul la|canta(?:-mi|mi)?|reda|play|put on|cauta(?:-mi|mi)?|search for|search|find|look for|asculta|listen to|deschide|open|arata(?:-mi|mi)?|show me|show)\\s+(?:te rog\\s+|please\\s+)?(?:(?:melodia|piesa|cantecul|muzica|un cantec|o melodie|videoclipul|clipul|videoul|song|the song|music|some music|a song|video|the video|a video|videos|the track|track)\\s+)?(?:(?:cu|de la|by|from|despre|about|for)\\s+)?(.+)$")
    private val WEB_SEARCH = Regex("^(?:cauta(?:-mi|mi)?|cautati|search|google|look up|lookup|look for)\\s+(?:pe\\s+(?:google|internet|net|web)\\s+|on\\s+(?:google|the internet|the web|internet)\\s+|google\\s+|online\\s+|the web\\s+|the internet\\s+|in browser\\s+)?(?:for\\s+|about\\s+|despre\\s+|informatii despre\\s+|something about\\s+|ceva despre\\s+)?(.+)$")
    private val WEB_QUESTION = Regex("^(?:ce (?:e|este|inseamna|sunt)|cine (?:e|este|a fost|sunt)|cat (?:costa|face|e|este)|cum (?:se face|fac|se scrie|se traduce)|unde (?:e|este|se afla|gasesc)|cand (?:e|este|a fost|incepe)|de ce |what (?:is|are|was|does|do)|what's|who (?:is|was|are)|who's|how (?:much|many|do|to|does|can)|why |when (?:is|was|does)|where (?:is|can)|define |definitia )")
    private val PLACE_NAV = Regex("^(?:navigheaza|navigate|du-ma|duma|drive|mergi|mergem|go|take me|directions|traseu|drum|ruta|cum ajung)(?:\\s+(?:la|catre|spre|pana la|to|in|into|on))?\\s+(.+)$")
    private val PLACE_SHOW = Regex("^(?:arata(?:-mi|mi)?|show me|show|cauta|search|find|gaseste(?:-mi|mi)?|unde (?:e|este|se afla|sunt)|where (?:is|are))(?:\\s+(?:pe harta|on the map|the map|harta))?\\s+(?:pe harta\\s+|on the map\\s+|for\\s+)?(.+?)(?:\\s+(?:pe harta|on the map))?$")
    // ── pe ecranul altei aplicații ──
    private val SCREEN_READ = Regex("^(?:(?:citeste(?:-mi|mi)?|read(?: me)?|spune(?:-mi|mi)?|tell me|zi(?:-mi|mi)?)\\s+(?:ce (?:e|este|scrie|vezi|vad) pe ecran|ce (?:e|este) pe pagina|ecranul|ce scrie|pagina|the screen|whats on (?:the )?screen|what's on (?:the )?screen|what is on (?:the )?screen|the page|rezultatele|the results|textul|the text|tot|everything|mesajul|the message)|ce (?:e|este|scrie|vezi|vad|avem) pe ecran|ce scrie(?: aici| pe ecran)?|what(?:'s|s| is) on (?:the |my )?screen|what does it say|describe the screen|descrie ecranul|unde sunt)\\s*$")
    private val SCREEN_TAP = Regex("^(?:apasa(?:-l|l)?|atinge|da click|click|clic|tap|press|push|hit|select|selecteaza|alege|activeaza|deschide-l|bifeaza|check)(?:\\s+(?:pe|on))?\\s+(?:butonul\\s+|the button\\s+|the\\s+|optiunea\\s+|pe\\s+)?(.+)$")
    private val SCREEN_TYPE = Regex("^(?:scrie|tasteaza|type|write|introdu|enter text|dicteaza|dictate)\\s+(?!(?:-i\\s|i\\s|lui\\s|la\\s|catre\\s|pentru\\s|un\\s|o\\s|a\\s|an\\s|the\\s|this\\s|that\\s|to\\s|mesaj|sms|text|pe whatsapp|on whatsapp))(?:textul\\s+|the text\\s+|aici\\s+|here\\s+)?(.+)$")
    private val SCREEN_SCROLL = Regex("^(?:deruleaza|scroll|da (?:in |mai )?(?:jos|sus)|mergi (?:mai )?(?:jos|sus)|swipe|mai (?:jos|sus)|pagina urmatoare|next page|page down|page up|continua)\\s*(?:in\\s+|pe\\s+)?(jos|sus|down|up|mai jos|mai sus|in jos|in sus)?\\s*$")
    private val SCREEN_BACK = Regex("^(?:inapoi|mergi inapoi|du-te inapoi|go back|back|navigate back|iesi|exit|close|inchide(?: asta| pagina| aplicatia)?)\\s*$")
    private val SCREEN_HOME = Regex("^(?:ecranul principal|ecranul de start|home screen|go to home screen|go to the home screen|acasa pe telefon|la ecranul principal|ecranul principal al telefonului)\\s*$")
    private val SCREEN_ENTER = Regex("^(?:enter|apasa(?: pe)? enter|press enter|apasa(?: pe)? cauta|apasa(?: pe)? trimite|apasa(?: pe)? ok|hit enter|submit|go)\\s*$")
    private val SCREEN_SEARCH_HERE = Regex("^(?:cauta(?:-mi|mi)?|search(?: for)?|find|gaseste(?:-mi|mi)?)\\s+(?:aici\\s+|here\\s+|in aplicatie\\s+|in aplicatia asta\\s+|in app\\s+|in the app\\s+|in this app\\s+)(.+)$|^(?:cauta(?:-mi|mi)?|search(?: for)?|find|gaseste(?:-mi|mi)?)\\s+(.+?)\\s+(?:aici|here|in aplicatie|in aplicatia asta|in app|in the app|in this app)$")
    private val ENABLE_SCREEN = Regex("^(?:activeaza|porneste|enable|turn on)\\s+(?:controlul (?:ecranului|pe ecran)|comenzile pe ecran|accesibilitatea|screen control|accessibility)\\b")

    private val OPEN_APP = Regex("^(?:deschide(?:-mi|mi)?|porneste|lanseaza|ruleaza|intra in|open|launch|start|run|go to)\\s+(?:te rog\\s+|please\\s+)?(?:aplicatia\\s+|app\\s+|the app\\s+|the\\s+)?(.+?)(?:\\s+(?:aplicatia|app|te rog|please))?$")
    private val TRAILING_POLITE = Regex("\\s*(?:te rog|please|multumesc|thanks|thank you)\\s*$")

    private val YES = Regex("^(?:da|sigur|desigur|confirm|confirma|trimite|trimite-l|ok|okay|oche|bine|corect|exact|yes|yeah|yep|yup|sure|send|send it|go|go ahead|correct|right|do it|afirmativ)\\b")
    private val NO = Regex("^(?:nu|ba|anuleaza|anulare|stop|renunta|opreste|gresit|no|nope|nah|cancel|wrong|negative|don'?t)\\b")

    private data class NavRule(val target: AppTarget, val keywords: String)
    private val NAV_RULES = listOf(
        NavRule(AppTarget.DASHBOARD, "acasa|dashboard|azi|ziua mea|ziua ta|pagina principala|inceput|home|today|main page|main screen"),
        NavRule(AppTarget.WORKOUT, "antrenament|antrenamentul|antrenamente|antrenamentele|exercitii|exercitiile|sala|workout|workouts|training|exercises|gym"),
        NavRule(AppTarget.MAP, "harta|hartile|teren|terenul|camarazi|camarazii|map|the map|maps"),
        NavRule(AppTarget.NUTRITION, "nutritie|nutritia|mancare|mancarea|mese|mesele|masa|jurnalul de mese|meals|food|nutrition|diet|dieta"),
        NavRule(AppTarget.SLEEP, "somn|somnul|sleep|dormit"),
        NavRule(AppTarget.FOCUS, "focus|focusul|concentrare|concentrarea|detox|detoxul|focus mode"),
        NavRule(AppTarget.BREATH, "respira|respiratie|respiratia|respiro|respiratul|exercitiul de respiratie|breath|breathe|breathing|breathing exercise"),
        NavRule(AppTarget.PROFILE, "profil|profilul|profilul meu|cont|contul|contul meu|setari|setarile|profile|my profile|account|settings"),
        NavRule(AppTarget.ACTIVITIES, "activitati|activitatile|activitatile mele|ture|turele|alergari|alergarile|istoricul|istoric|activities|my activities|runs|history"),
        NavRule(AppTarget.SCANNER, "scanner|scannerul|scannerul de cod de bare|scaneaza|scanare|scaneaza codul|scaneaza codul de bare|scaneaza un cod de bare|scaneaza cod de bare|cod de bare|codul de bare|scaneaza un produs|scaneaza produsul|barcode|barcode scanner|scan|scan a barcode|scan a product|scan the barcode"),
        NavRule(AppTarget.MEAL_CAMERA, "poza la masa|poza masa|fotografiaza masa|fotografiaza mancarea|poza la mancare|fa o poza la masa|fa o poza mancarii|camera de mese|photo meal|meal photo|take a photo of my meal|photograph my meal|photograph the food|food photo"),
        NavRule(AppTarget.PERMISSIONS, "permisiuni|permisiunile|pornire|echipare|echiparea|permissions"),
        NavRule(AppTarget.CLEANUP, "curatenie|curatenia|galerie|galeria|inventar|inventarul|cleanup|gallery|inventory"),
        NavRule(AppTarget.VOICE, "voce|vocea|asistent|asistentul|asistentul vocal|comenzi vocale|voice|assistant|voice assistant")
    )
    private val NAV_REGEX: List<Pair<Regex, AppTarget>> = NAV_RULES.map { r ->
        Regex("^(?:(?:deschide(?:-mi|mi)?|du-ma la|du-ma in|duma la|arata(?:-mi|mi)?|mergi la|hai la|intra in|treci la|navigheaza (?:la|spre|catre)|navigheaza|porneste|incepe|start|open|go to|show me|show|take me to|switch to|navigate to|launch)\\s+)?(?:la\\s+|in\\s+|the\\s+|pagina\\s+|ecranul\\s+|modulul\\s+|sectiunea\\s+|pagina de\\s+|ecranul de\\s+|modulul de\\s+|un\\s+|o\\s+)?(?:de\\s+)?(?:${r.keywords})(?:\\s+(?:te rog|please|acum|now))?$") to r.target
    }

    /** Textul rostit → comandă. Acceptă „hei forja" la început, îl ignoră. */
    fun parse(raw: String): VoiceCommand {
        var t = VoiceText.stripWakeWord(VoiceText.normalize(raw))
        if (t.isBlank()) return VoiceCommand.Unknown("")
        t = TRAILING_POLITE.replace(t, "").trim()
        t = t.removePrefix("te rog ").removePrefix("please ").trim()

        if (STOP_LISTENING.containsMatchIn(t)) return VoiceCommand.StopListening
        if (STOP.containsMatchIn(t)) return VoiceCommand.Stop
        if (HELP.containsMatchIn(t)) return VoiceCommand.Help
        if (TIME.containsMatchIn(t)) return VoiceCommand.TellTime
        if (DATE.containsMatchIn(t)) return VoiceCommand.TellDate
        if (REPEAT.containsMatchIn(t)) return VoiceCommand.Repeat
        if (PROGRESS.containsMatchIn(t)) return VoiceCommand.Progress
        if (FRIENDS.containsMatchIn(t)) return VoiceCommand.Friends
        if (SLEEP_START.containsMatchIn(t)) return VoiceCommand.StartSleep
        if (SLEEP_STOP.containsMatchIn(t)) return VoiceCommand.StopSleep

        if (ENABLE_SCREEN.containsMatchIn(t)) return VoiceCommand.EnableScreenControl
        parseCompound(t, raw)?.let { return it }
        parseScreen(t, raw)?.let { return VoiceCommand.Screen(it) }
        parseAlarm(t)?.let { return it }
        parseMessage(t, VoiceText.cleanRaw(raw))?.let { return it }
        parseCall(t)?.let { return it }
        parseMusic(t)?.let { return it }
        parsePlace(t, raw)?.let { return it }
        for ((rx, target) in NAV_REGEX) if (rx.matches(t)) return VoiceCommand.Navigate(target)
        OPEN_APP.find(t)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotBlank()) return VoiceCommand.OpenApp(name)
        }
        parseWebSearch(t, raw)?.let { return it }
        return VoiceCommand.Unknown(t)
    }

    /** Textul original (majuscule, diacritice) pentru ultimele [words] cuvinte ale frazei normalizate. */
    private fun rawTail(raw: String, normalizedPart: String): String {
        val n = normalizedPart.split(" ").count { it.isNotBlank() }
        return VoiceText.tailWords(VoiceText.cleanRawLight(raw), n).ifBlank { normalizedPart }
    }

    /** „deschide youtube și pune meniato”, „open google and search about strawberries”, „deschide waze și du-mă la…”. */
    private fun parseCompound(t: String, raw: String): VoiceCommand? {
        val m = COMPOUND.find(t) ?: return null
        val app = m.groupValues[1].trim()
        val rest = m.groupValues[2].trim()
        if (app.isBlank() || rest.isBlank()) return null
        val external = app in YT_APPS || app in WEB_APPS || app in MAP_APPS || app.startsWith("spotif") ||
            app in setOf("youtube music", "yt music", "settings", "setari", "setarile", "setarile telefonului", "whatsapp", "telefonul", "phone")
        // „deschide antrenamentul și …” nu e o aplicație externă: lăsăm restul regulilor.
        if (!external && NAV_REGEX.any { (rx, _) -> rx.matches(app) }) return null
        fun query(rx: Regex): String = (rx.find(rest)?.groupValues?.get(1) ?: rest).trim().let { rawTail(raw, it) }
        return when {
            app in YT_APPS -> VoiceCommand.PlayMusic(query(PLAY_VERB), MusicService.YOUTUBE)
            app == "youtube music" || app == "yt music" -> VoiceCommand.PlayMusic(query(PLAY_VERB), MusicService.YOUTUBE_MUSIC)
            app.startsWith("spotif") -> VoiceCommand.PlayMusic(query(PLAY_VERB), MusicService.SPOTIFY)
            app in WEB_APPS -> VoiceCommand.WebSearch(query(SEARCH_VERB))
            app in MAP_APPS -> {
                val nav = PLACE_NAV.find(rest)
                val which = if (app == "waze") "waze" else "maps"
                if (nav != null) VoiceCommand.OpenPlace(rawTail(raw, nav.groupValues[1].trim()), navigate = true, app = which)
                else VoiceCommand.OpenPlace(query(PLACE_SHOW), navigate = false, app = which)
            }
            app in setOf("whatsapp", "telefonul", "phone") -> {
                val inner = parse(rawTail(raw, rest))
                when {
                    app == "whatsapp" && inner is VoiceCommand.SendMessage -> inner.copy(channel = MessageChannel.WHATSAPP)
                    inner is VoiceCommand.SendMessage || inner is VoiceCommand.Call -> inner
                    else -> parseScreen(rest, rawTail(raw, rest))?.let { VoiceCommand.OpenAppThen(app, VoiceCommand.Screen(it)) }
                        ?: VoiceCommand.OpenAppThen(app, VoiceCommand.Unknown(rest))
                }
            }
            else -> {
                val rawRest = rawTail(raw, rest)
                val action = parseScreen(rest, rawRest)
                val inner = parse(rawRest)
                when {
                    action != null -> VoiceCommand.OpenAppThen(app, VoiceCommand.Screen(action))
                    // „caută X” / „pune X” într-o aplicație oarecare = căutare pe ecranul ei
                    SEARCH_VERB.containsMatchIn(rest) -> VoiceCommand.OpenAppThen(app, VoiceCommand.Screen(ScreenAction.Search(query(SEARCH_VERB))))
                    PLAY_VERB.containsMatchIn(rest) && inner !is VoiceCommand.SendMessage && inner !is VoiceCommand.Call ->
                        VoiceCommand.OpenAppThen(app, VoiceCommand.Screen(ScreenAction.Search(query(PLAY_VERB))))
                    inner !is VoiceCommand.Unknown && inner !is VoiceCommand.OpenApp -> VoiceCommand.OpenAppThen(app, inner)
                    else -> VoiceCommand.OpenAppThen(app, VoiceCommand.Unknown(rest))
                }
            }
        }
    }

    /** Acțiunile pe ecranul aplicației din față. */
    fun parseScreen(t: String, raw: String): ScreenAction? {
        if (SCREEN_READ.containsMatchIn(t)) return ScreenAction.Read
        if (SCREEN_BACK.matches(t)) return ScreenAction.Back
        if (SCREEN_HOME.matches(t)) return ScreenAction.Home
        if (SCREEN_ENTER.matches(t)) return ScreenAction.Enter
        SCREEN_SCROLL.find(t)?.let { m ->
            val dir = m.groupValues[1]
            val up = dir.contains("sus") || dir.contains("up") || t.contains(" sus") || t == "page up"
            return ScreenAction.Scroll(down = !up)
        }
        SCREEN_SEARCH_HERE.find(t)?.let { m ->
            val q = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            val rawNoTail = VoiceText.cleanRawLight(raw).replace(Regex("\\s+(?:aici|here|[iî]n aplica[tț]ie|[iî]n aplica[tț]ia asta|in app|in the app|in this app)\\s*$", RegexOption.IGNORE_CASE), "")
            if (q.isNotBlank()) return ScreenAction.Search(VoiceText.tailWords(rawNoTail, q.split(" ").count { it.isNotBlank() }).ifBlank { q })
        }
        SCREEN_TYPE.find(t)?.let { m ->
            val text = m.groupValues[1].trim()
            if (text.isNotBlank()) return ScreenAction.Type(rawTail(raw, text))
        }
        SCREEN_TAP.find(t)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isNotBlank() && !Regex("^(?:alarma|alarm|enter)$").matches(target)) return ScreenAction.Tap(rawTail(raw, target))
        }
        return null
    }

    private fun parseWebSearch(t: String, raw: String): VoiceCommand? {
        WEB_SEARCH.find(t)?.let { m ->
            val q = m.groupValues[1].trim()
            if (q.isNotBlank()) return VoiceCommand.WebSearch(rawTail(raw, q))
        }
        if (WEB_QUESTION.containsMatchIn(t) && t.split(" ").size >= 3) return VoiceCommand.WebSearch(rawTail(raw, t))
        return null
    }

    /** „du-mă la gară”, „navighează spre spital”, „arată-mi pe hartă farmacia” — doar dacă e clar că e un loc. */
    private val MAP_PHRASE = Regex("\\b(?:pe harta|on the map|cu waze|on waze|pe waze|cu google maps|on google maps|in maps|pe maps)\\b")
    private val MAP_PHRASE_RAW = Regex("(?<![\\p{L}])(?:pe hart[aă]|on the map|cu waze|on waze|pe waze|cu google maps|on google maps|in maps|pe maps)(?![\\p{L}])", RegexOption.IGNORE_CASE)

    private fun parsePlace(t: String, raw: String): VoiceCommand? {
        if (MAP_PHRASE.containsMatchIn(t)) {
            val which = if (t.contains("waze")) "waze" else "maps"
            val clean = MAP_PHRASE.replace(t, " ").replace(Regex("\\s+"), " ").trim()
            // „du-mă pe hartă”, „arată-mi pe hartă”, „deschide harta pe waze”: a rămas doar verbul → ecranul Hartă din FORJA
            if (clean.isBlank() || Regex("^(?:du-ma|duma|arata(?:-mi|mi)?|show me|show|deschide|open|mergi|go|navigheaza|navigate|cauta|search|harta|the map|map)$").matches(clean)) {
                return VoiceCommand.Navigate(AppTarget.MAP)
            }
            // „deschide harta pe waze / google maps” = aplicația de hărți, nu un loc
            if (Regex("^(?:deschide(?:-mi|mi)?|open|porneste|start|lanseaza|launch)\\s+(?:harta|hartile|the map|map|maps|navigatia|navigation)$").matches(clean)) {
                return VoiceCommand.OpenApp(if (which == "waze") "waze" else "google maps")
            }
            val rawClean = MAP_PHRASE_RAW.replace(VoiceText.cleanRawLight(raw), " ").replace(Regex("\\s+"), " ").trim()
            fun tail(part: String) = VoiceText.tailWords(rawClean, part.split(" ").count { it.isNotBlank() }).ifBlank { part }
            PLACE_NAV.find(clean)?.let { return VoiceCommand.OpenPlace(tail(it.groupValues[1].trim()), navigate = true, app = which) }
            PLACE_SHOW.find(clean)?.let { return VoiceCommand.OpenPlace(tail(it.groupValues[1].trim()), navigate = false, app = which) }
            return VoiceCommand.OpenPlace(tail(clean), navigate = false, app = which)
        }
        Regex("^(?:navigheaza|navigate|cum ajung|directions|traseu|ruta)(?:\\s+(?:la|catre|spre|pana la|to))?\\s+(.+)$").find(t)?.let { m ->
            val place = m.groupValues[1].trim()
            // „navighează la hartă / profil” = ecranele FORJA, nu un loc pe hartă
            if (NAV_REGEX.any { (rx, _) -> rx.matches(place) }) return null
            return VoiceCommand.OpenPlace(rawTail(raw, place), navigate = true)
        }
        return null
    }

    /** „da" / „nu" pentru confirmări; null dacă nu e clar. */
    fun yesNo(raw: String): Boolean? {
        val t = VoiceText.stripWakeWord(VoiceText.normalize(raw))
        if (NO.containsMatchIn(t)) return false
        if (YES.containsMatchIn(t)) return true
        return null
    }

    /** Răspuns la „Cui să trimit?" — curăță „lui ion" → „ion". */
    fun recipientFromAnswer(raw: String): String {
        var t = VoiceText.stripWakeWord(VoiceText.normalize(raw))
        t = TRAILING_POLITE.replace(t, "").trim()
        t = t.replace(Regex("^(?:lui|la|catre|pentru|pe|to|contactului|contactul)\\s+"), "")
        return if (t in PLACEHOLDER_RECIPIENT) "" else t
    }

    /** Răspuns la „Ce mesaj?" — curăță „ca ajung" → „ajung"; păstrează textul altfel intact. */
    fun bodyFromAnswer(raw: String): String {
        var t = raw.trim().trim('"', '„', '”', '“')
        t = t.replace(Regex("^(?:[Cc]ă|[Cc]a|[Ss]ă|[Ss]a|[Tt]hat|[Ss]aying|[Mm]esajul(?: este| e)?|[Tt]extul(?: este| e)?|[Tt]he message is|[Ss]ay)\\s+"), "")
        return if (VoiceText.normalize(t) in PLACEHOLDER_BODY) "" else t
    }

    private fun parseAlarm(t: String): VoiceCommand? {
        if (!Regex("\\b(?:alarma|alarm|desteptator|trezeste|wake)\\b").containsMatchIn(t)) return null
        val m = ALARM.find(t) ?: return null
        val tail = m.groupValues[1].trim()
        val parsed = parseClock(tail) ?: return VoiceCommand.Unknown(t)
        return VoiceCommand.SetAlarm(parsed.first, parsed.second)
    }

    /** „7", „7:30", „7 si 30", „sapte jumate", „half past seven", „7 pm", „7 seara" → (oră, minut). */
    fun parseClock(text: String): Pair<Int, Int>? {
        var s = text.trim()
        var pm = false
        var am = false
        if (Regex("\\b(?:pm|p\\.m\\.|seara|dupa-amiaza|dupa amiaza|dupa masa|after ?noon|in the evening|evening|tonight|at night|noaptea)\\b").containsMatchIn(s)) pm = true
        if (Regex("\\b(?:am|a\\.m\\.|dimineata|in the morning|morning)\\b").containsMatchIn(s)) am = true
        s = s.replace(Regex("\\b(?:pm|am|p\\.m\\.|a\\.m\\.|seara|dimineata|dupa-amiaza|dupa amiaza|dupa masa|after ?noon|in the evening|evening|tonight|at night|noaptea|in the morning|morning|maine|tomorrow|ora|o'?clock|fix|sharp|la|at)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        var hour: Int? = null
        var minute = 0
        Regex("^(\\d{1,2})[:.h](\\d{1,2})").find(s)?.let { hour = it.groupValues[1].toInt(); minute = it.groupValues[2].toInt() }
        if (hour == null) {
            val halfPast = Regex("^(?:half past|si jumatate dupa)\\s+(.+)$").find(s)
            if (halfPast != null) {
                hour = VoiceText.numberFrom(halfPast.groupValues[1].split(" ")); minute = 30
            }
        }
        if (hour == null) {
            val quarterPast = Regex("^(?:quarter past|si un sfert dupa)\\s+(.+)$").find(s)
            if (quarterPast != null) { hour = VoiceText.numberFrom(quarterPast.groupValues[1].split(" ")); minute = 15 }
        }
        if (hour == null) {
            val words = s.split(" ").filter { it.isNotBlank() }
            if (words.isEmpty()) return null
            hour = VoiceText.numberFrom(words)
            if (hour != null) {
                val consumed = if (TENSLIKE.containsMatchIn(words[0]) && words.size > 2 && (words[1] == "si" || words[1] == "and")) 3
                else if (TENSLIKE.containsMatchIn(words[0]) && words.size > 1 && VoiceText.isNumberWord(words[1])) 2 else 1
                val rest = words.drop(consumed).filter { it != "si" && it != "and" }
                when {
                    rest.isEmpty() -> {}
                    rest[0] == "jumate" || rest[0] == "jumatate" || rest[0] == "thirty" || rest[0] == "half" -> minute = 30
                    rest[0] == "sfert" || rest[0] == "quarter" || rest[0] == "fifteen" -> minute = 15
                    else -> VoiceText.numberFrom(rest)?.let { minute = it }
                }
            }
        }
        val h0 = hour ?: return null
        var h = h0
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        if (h !in 0..23 || minute !in 0..59) return null
        return h to minute
    }
    private val TENSLIKE = Regex("^(?:douazeci|treizeci|patruzeci|cincizeci|twenty|thirty|forty|fifty)$")

    private fun parseMessage(t0: String, rawClean: String): VoiceCommand? {
        var t = t0
        var channel = MessageChannel.SMS
        if (WHATSAPP.containsMatchIn(t)) {
            channel = MessageChannel.WHATSAPP
            t = WHATSAPP.replace(t, " ").replace(Regex("\\s+"), " ").trim()
        }
        if (!Regex("^(?:te rog\\s+)?(?:trimite|scrie|da|dai|expediaza|transmite|spune|zi|zice|anunta|send|text|message|write|shoot|forward|tell|let)\\b").containsMatchIn(t)) return null
        // „trimite-i un telefon" nu e mesaj — îl lăsăm regulii de apel.
        if (Regex("^da(?:-i|i)?\\s+(?:un\\s+)?(?:telefon|apel)\\b").containsMatchIn(t)) return null

        val ro = MSG_RO.find(t)?.groupValues?.get(1) ?: TELL_RO.find(t)?.groupValues?.get(1)
        val en = if (ro == null) (MSG_EN.find(t)?.groupValues?.get(1) ?: TELL_EN.find(t)?.groupValues?.get(1)) else null
        val remainder = (ro ?: en)?.trim() ?: return null
        if (remainder.isBlank()) return VoiceCommand.SendMessage("", "", channel)
        val seps = if (ro != null) SEPARATORS_RO else SEPARATORS_EN

        // Cel mai devreme separator; la egalitate, cel mai lung.
        var bestIdx = -1
        var bestSep = ""
        val padded = " $remainder "
        for (sep in seps) {
            val idx = padded.indexOf(sep)
            if (idx < 0) continue
            if (bestIdx < 0 || idx < bestIdx || (idx == bestIdx && sep.length > bestSep.length)) { bestIdx = idx; bestSep = sep }
        }
        if (bestIdx >= 0) {
            val recipientRaw = padded.substring(0, bestIdx).trim()
            var body = padded.substring(bestIdx + bestSep.length).trim()
            body = BODY_LEAD.replace(body, "").trim()
            val recipient = cleanRecipient(recipientRaw)
            val cleanBody = if (body.isBlank() || VoiceText.normalize(body) in PLACEHOLDER_BODY) ""
            else VoiceText.tailWords(rawClean, body.split(" ").count { it.isNotBlank() })
            // „trimite mesaj: salut" — fără destinatar, dar cu text.
            return VoiceCommand.SendMessage(recipient, cleanBody, channel)
        }
        val recipient = cleanRecipient(remainder)
        if (recipient.isEmpty()) return VoiceCommand.SendMessage("", "", channel)
        if (recipient in PLACEHOLDER_BODY) return VoiceCommand.SendMessage("", "", channel)
        // Numele și textul sunt lipite; păstrăm originalul (majuscule, diacritice) — agenda îl desparte.
        val rawRecipient = VoiceText.tailWords(rawClean, recipient.split(" ").count { it.isNotBlank() })
        return VoiceCommand.SendMessage(rawRecipient.ifBlank { recipient }, "", channel, splitByContact = true)
    }

    private fun cleanRecipient(raw: String): String {
        var r = raw.trim()
        r = r.replace(Regex("^(?:lui|la|catre|pentru|pe|to|for)\\s+"), "")
        r = r.replace(Regex("^(?:contactului|contactul|contact|numarul|numarului|number)\\s+"), "")
        r = TRAILING_POLITE.replace(r, "").trim()
        return if (r in PLACEHOLDER_RECIPIENT || r.isBlank()) "" else r
    }

    private fun parseCall(t: String): VoiceCommand? {
        val who = (CALL_RO2.find(t) ?: CALL_RO.find(t) ?: CALL_EN.find(t))?.groupValues?.get(1)?.trim() ?: return null
        var w = TRAILING_POLITE.replace(who, "").trim()
        w = w.replace(Regex("^(?:pe|la|lui|to|numarul|number|contactul)\\s+"), "")
        // „sună alarma", „call it a day" — nu sunt apeluri.
        if (Regex("^(?:alarma|alarm|it a day|me later|back)\\b").containsMatchIn(w)) return null
        if (w in PLACEHOLDER_RECIPIENT) return VoiceCommand.Call("")
        return VoiceCommand.Call(w)
    }

    private fun parseMusic(t0: String): VoiceCommand? {
        var t = t0
        var service: MusicService? = null
        MUSIC_SERVICE.find(t)?.let { m ->
            service = serviceFrom(m.groupValues[1])
            t = t.removeRange(m.range).replace(Regex("\\s+"), " ").trim()
        }
        // „youtube: numele melodiei" / „youtube numele melodiei"
        if (service == null) {
            Regex("^(youtube music|yt music|youtube|yutub|you tube|iutub|spotify|spotifai)\\s*[:\\-]?\\s*(.+)$").find(t)?.let { m ->
                val q = m.groupValues[2].trim()
                if (q.isNotBlank() && !Regex("^(?:app|aplicatia|the app)$").matches(q)) {
                    return VoiceCommand.PlayMusic(cleanQuery(q), serviceFrom(m.groupValues[1]))
                }
            }
        }
        val m = MUSIC_VERB.find(t)
        if (m == null) {
            // „melodia X pe youtube" — fără verb, dar cu serviciu.
            if (service != null && t.isNotBlank()) {
                val q = cleanQuery(t.replace(Regex("^(?:muzica|melodia|piesa|cantecul|song|music|the song|video|clipul|videoclipul)\\s*"), ""))
                return VoiceCommand.PlayMusic(q, service!!)
            }
            return null
        }
        val verb = t.substring(0, t.length - m.groupValues[1].length).trim()
        val hadMusicNoun = Regex("\\b(?:muzica|melodia|piesa|cantecul|cantec|melodie|videoclipul|clipul|videoul|song|music|video|videos|track)\\b").containsMatchIn(verb)
        var q = cleanQuery(m.groupValues[1])
        val isSearchVerb = Regex("^(?:cauta|search|find|gaseste|arata)").containsMatchIn(verb)
        val isPlayVerb = Regex("^(?:pune|porneste|da|dai|canta|reda|ruleaza|play|put on|asculta|listen)").containsMatchIn(verb)
        if (!isSearchVerb && !isPlayVerb) return null
        if (MUSIC_NOUN_ONLY.matches(q)) q = ""
        // „pornește X" fără muzică/serviciu/nume de melodie e, cel mai probabil, altceva (somn, aplicație).
        if (!hadMusicNoun && service == null && !isPlayVerb) return null
        if (!hadMusicNoun && service == null && Regex("^(?:porneste|start|put on)").containsMatchIn(verb)) return null
        if (isSearchVerb && service == null && !hadMusicNoun) return null
        val svc = service ?: if (isSearchVerb) MusicService.YOUTUBE else MusicService.ANY
        return VoiceCommand.PlayMusic(q, svc)
    }

    private fun serviceFrom(s: String): MusicService = when {
        s.contains("music") -> MusicService.YOUTUBE_MUSIC
        s.startsWith("spotif") -> MusicService.SPOTIFY
        else -> MusicService.YOUTUBE
    }

    private fun cleanQuery(q: String): String {
        var s = q.trim()
        s = TRAILING_POLITE.replace(s, "").trim()
        s = s.replace(Regex("^(?:pe|on|de pe|from|la|the|a|o|un)\\s+"), "")
        s = s.replace(Regex("^(?:melodia|piesa|cantecul|muzica|song|music|video)\\s+(?:asta|this)?\\s*"), "")
        s = s.replace(Regex("\\s+(?:asta|aceasta|this|that)$"), "")
        return s.trim()
    }

    /** Lista rostită la „ajutor". */
    val HELP_TEXT = "Pot să trimit mesaje: „trimite mesaj lui Ion, ajung în zece minute”. Să sun: „sună-l pe Andrei”. " +
        "Să pun muzică: „deschide YouTube și pune Phoenix”. Să caut pe internet: „caută pe Google despre căpșuni”. " +
        "Să deschid aplicații și să lucrez în ele: „deschide WhatsApp”, „citește ecranul”, „apasă pe Abonează-te”, „scrie salut”, „derulează”, „înapoi”. " +
        "Să te duc în FORJA: „deschide antrenamentul”, „pornește somnul”, „respiră”. " +
        "Să îți citesc ziua: „cum stau azi”. Să îți spun ora: „cât e ceasul”. Să pun alarma: „pune alarma la șapte”."
}
