package com.forja.app.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "forja_prefs")

class Prefs(private val context: Context) {
    private object K {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val kcalTarget = intPreferencesKey("kcal_target")
        val weekKmTarget = intPreferencesKey("week_km_target")
        val notifOn = booleanPreferencesKey("notif_on")
        val sleepReminder = booleanPreferencesKey("sleep_reminder")
        val focusUnlockUntil = longPreferencesKey("focus_unlock_until")
        val focusActive = booleanPreferencesKey("focus_active")
        val cachedName = stringPreferencesKey("cached_name")
        val geminiKey = stringPreferencesKey("gemini_key")
        val alarmEnabled = booleanPreferencesKey("alarm_enabled")
        val alarmHour = intPreferencesKey("alarm_hour")
        val alarmMinute = intPreferencesKey("alarm_minute")
        val bgShareOn = booleanPreferencesKey("bg_share_on")
        val ghostUntilLocal = longPreferencesKey("ghost_until_local")
        val bgBannerDismissed = booleanPreferencesKey("bg_banner_dismissed")
        val alarmWindowMin = intPreferencesKey("alarm_window_min")
        val detoxUntil = longPreferencesKey("detox_until")
        val galleryScanOn = booleanPreferencesKey("gallery_scan_on")
        val detoxOn = booleanPreferencesKey("detox_addiction_on")
        val detoxStreakStart = longPreferencesKey("detox_streak_start")
        val detoxLetter = stringPreferencesKey("detox_letter")
        val detoxWords = stringPreferencesKey("detox_words")
        val detoxSlips = intPreferencesKey("detox_slips")
        val permsIntroSeen = booleanPreferencesKey("perms_intro_seen")
        val nudgesOn = booleanPreferencesKey("nudges_on")
        val focusForestDay = longPreferencesKey("focus_forest_day")
        val focusGrown = intPreferencesKey("focus_grown")
        val focusWithered = intPreferencesKey("focus_withered")
        val focusPartialSecs = intPreferencesKey("focus_partial_secs")
        // v4.0 — start
        val introSeenVersion = intPreferencesKey("intro_seen_version")
        val gearSeenVersion = intPreferencesKey("gear_seen_version")
        // v4.0 — explorare / hartă
        val exploreOn = booleanPreferencesKey("explore_on")
        val placeThresholdMin = intPreferencesKey("place_threshold_min")
        val showExplore = booleanPreferencesKey("show_explore")
        val map3d = booleanPreferencesKey("map_3d")
        val familyUids = stringSetPreferencesKey("family_uids")
        val exploreCandidate = stringPreferencesKey("explore_candidate")
        val exploreSyncSite = booleanPreferencesKey("explore_sync_site")
        val exploreSyncedAt = longPreferencesKey("explore_synced_at")
        val exploreDeviceId = stringPreferencesKey("explore_device_id")
        // v4.0 — sincronizarea în cont (site)
        val syncStatus = stringPreferencesKey("sync_status")
        // v4.0 — curățenie
        val cleanupCursor = stringPreferencesKey("cleanup_cursor")
        val cleanupLastScope = stringPreferencesKey("cleanup_last_scope")
        val cleanupDocsTree = stringPreferencesKey("cleanup_docs_tree")
        val cleanupAiOn = booleanPreferencesKey("cleanup_ai_on")
        val cleanupDocsUndo = stringPreferencesKey("cleanup_docs_undo")
        // v4.4 — Inventar: „Ultimele N” și destinațiile alese
        val inventoryLastN = intPreferencesKey("inventory_last_n")
        val inventoryPhotoRoot = stringPreferencesKey("inventory_photo_root")
        val inventoryDocsDest = stringPreferencesKey("inventory_docs_dest")
        // v4.1 — prieteni din agendă (ca la Telegram)
        val phoneDeclared = stringPreferencesKey("phone_declared")
        val contactsOn = booleanPreferencesKey("contacts_on")
        val contactsSyncedAt = longPreferencesKey("contacts_synced_at")
        val contactMatches = stringPreferencesKey("contact_matches")
        val contactsStatus = stringPreferencesKey("contacts_status")
        // v4.2 — contractul de securitate (un singur acord în locul comutatoarelor de sincronizare)
        val contractSignedAt = longPreferencesKey("contract_signed_at")
        val contractVersion = intPreferencesKey("contract_version")
        // v4.4 — foaia de re-semnare a apărut o dată pentru această versiune
        val contractPromptVersion = intPreferencesKey("contract_prompt_version")
        // v4.4 — contul care a semnat (semnătura e a contului, nu a telefonului)
        val contractUid = stringPreferencesKey("contract_uid")
        // mirror (contract v4) — cuvintele de care te lași și scrisoarea, pe site: acord separat, oprit implicit
        val detoxWordsOnSite = booleanPreferencesKey("detox_words_on_site")
        // mirror D — istoriile zilnice: pădurea („zi:crescuți:uscați;…”) și opririle paznicului pe pachete („zi:pachet:n;…”)
        val focusForestHistory = stringPreferencesKey("focus_forest_history")
        val detoxHits = stringPreferencesKey("detox_hits")
        // „Hei FORJA" — asistentul vocal
        val voiceWakeOn = booleanPreferencesKey("voice_wake_on")
        val voiceSpeakOn = booleanPreferencesKey("voice_speak_on")
        val voiceConfirmSend = booleanPreferencesKey("voice_confirm_send")
        val voiceLang = stringPreferencesKey("voice_lang")
        val voiceIntroSeen = booleanPreferencesKey("voice_intro_seen")
    }

    companion object {
        /** Prezentarea de început: se arată o dată pentru fiecare versiune — și conturilor existente. */
        const val INTRO_VERSION = 2
        /** „Echipare” (permisiunile): la fel, o dată per versiune. */
        const val GEAR_VERSION = 3
        /**
         * Versiunea textului contractului de securitate; o versiune nouă cere semnătură nouă.
         * v3 (4.4): găsirea telefonului, Inventarul pe site, muzica, antrenamentele și ținta, plus corecturile de text.
         * v4 (mirror): urma Găsirii, cronologia nopții, concentrarea / detoxul / respirația, Casca, jurnalul de ascultare,
         * jocurile, numărătorile galeriei, poza mesei și coperțile dosarelor; „Cine vede” corectat.
         */
        const val CONTRACT_VERSION = 4
        /**
         * Tot ce a pornit cu v3 merge mai departe cu o semnătură v3, și după trecerea la v4, până la re-semnare
         * ([contractSigned] = [contractAtLeast] (CONTRACT_BASE)). Ce e nou în v4 întreabă contractAtLeast(4).
         */
        const val CONTRACT_BASE = 3
    }

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[K.onboardingDone] ?: false }
    /** Versiunea prezentării văzute (0 = niciodată). Compară cu [INTRO_VERSION]. */
    val introSeenVersion: Flow<Int> = context.dataStore.data.map { it[K.introSeenVersion] ?: 0 }
    /** Prezentarea a fost parcursă: bifează și versiunea curentă. */
    suspend fun setIntroSeen() = context.dataStore.edit {
        it[K.onboardingDone] = true
        it[K.introSeenVersion] = INTRO_VERSION
    }
    suspend fun setOnboardingDone() = setIntroSeen()

    val gearSeenVersion: Flow<Int> = context.dataStore.data.map { it[K.gearSeenVersion] ?: 0 }
    suspend fun setGearSeen() = context.dataStore.edit { it[K.gearSeenVersion] = GEAR_VERSION }

    // ── Explorare (hartă) ──
    /** Urmărirea zonelor/locurilor — pornită implicit, se oprește din Profil. */
    val exploreOn: Flow<Boolean> = context.dataStore.data.map { it[K.exploreOn] ?: true }
    suspend fun setExploreOn(v: Boolean) = context.dataStore.edit { it[K.exploreOn] = v }
    /** Un „loc” = ai STAT cel puțin atâtea minute (30 / 60 / 120 / 300). */
    val placeThresholdMin: Flow<Int> = context.dataStore.data.map { it[K.placeThresholdMin] ?: 300 }
    suspend fun setPlaceThresholdMin(v: Int) = context.dataStore.edit { it[K.placeThresholdMin] = v }
    val showExplore: Flow<Boolean> = context.dataStore.data.map { it[K.showExplore] ?: true }
    suspend fun setShowExplore(v: Boolean) = context.dataStore.edit { it[K.showExplore] = v }
    val map3d: Flow<Boolean> = context.dataStore.data.map { it[K.map3d] ?: false }
    suspend fun setMap3d(v: Boolean) = context.dataStore.edit { it[K.map3d] = v }
    /** Oglinda locală a users/{me}.familyUids — prietenii care te văd și în fantomă. */
    val familyUids: Flow<Set<String>> = context.dataStore.data.map { it[K.familyUids] ?: emptySet() }
    suspend fun setFamilyUids(v: Set<String>) = context.dataStore.edit { it[K.familyUids] = v }
    /** Candidatul de „ședere” curent (JSON, format intern ExploreTracker). */
    val exploreCandidate: Flow<String> = context.dataStore.data.map { it[K.exploreCandidate] ?: "" }
    suspend fun setExploreCandidate(v: String) = context.dataStore.edit { it[K.exploreCandidate] = v }
    /** Trimite zonele și locurile în panoul online (site) — opt-in explicit. */
    val exploreSyncSite: Flow<Boolean> = context.dataStore.data.map { it[K.exploreSyncSite] ?: false }
    suspend fun setExploreSyncSite(v: Boolean) = context.dataStore.edit { it[K.exploreSyncSite] = v }
    /** Marca serverului (server_at) până la care explorarea a fost trimisă. */
    val exploreSyncedAt: Flow<Long> = context.dataStore.data.map { it[K.exploreSyncedAt] ?: 0L }
    suspend fun setExploreSyncedAt(v: Long) = context.dataStore.edit { it[K.exploreSyncedAt] = v }
    /** Identitatea acestui telefon față de site (UUID), generată o singură dată. */
    val exploreDeviceId: Flow<String> = context.dataStore.data.map { it[K.exploreDeviceId] ?: "" }
    suspend fun setExploreDeviceId(v: String) = context.dataStore.edit { it[K.exploreDeviceId] = v }
    /** Starea sincronizării în cont, în cuvinte — afișată în Echipare și Profil. */
    val syncStatus: Flow<String> = context.dataStore.data.map { it[K.syncStatus] ?: "" }
    suspend fun setSyncStatus(v: String) = context.dataStore.edit { it[K.syncStatus] = v }

    // ── Curățenie ──
    /** Cursorul de reluare (JSON CleanupCursor) — OBLIGATORIU: continuăm de unde am rămas. */
    val cleanupCursor: Flow<String> = context.dataStore.data.map { it[K.cleanupCursor] ?: "" }
    suspend fun setCleanupCursor(v: String) = context.dataStore.edit { it[K.cleanupCursor] = v }
    val cleanupLastScope: Flow<String> = context.dataStore.data.map { it[K.cleanupLastScope] ?: "" }
    suspend fun setCleanupLastScope(v: String) = context.dataStore.edit { it[K.cleanupLastScope] = v }
    /** Dosarul de documente ales (URI SAF persistat). */
    val cleanupDocsTree: Flow<String> = context.dataStore.data.map { it[K.cleanupDocsTree] ?: "" }
    suspend fun setCleanupDocsTree(v: String) = context.dataStore.edit { it[K.cleanupDocsTree] = v }
    /** Sugestii AI (miniaturi ≤512px și fragmente de text către serverul FORJA) — strict opt-in. */
    val cleanupAiOn: Flow<Boolean> = context.dataStore.data.map { it[K.cleanupAiOn] ?: false }
    suspend fun setCleanupAiOn(v: Boolean) = context.dataStore.edit { it[K.cleanupAiOn] = v }
    val cleanupDocsUndo: Flow<String> = context.dataStore.data.map { it[K.cleanupDocsUndo] ?: "" }
    suspend fun setCleanupDocsUndo(v: String) = context.dataStore.edit { it[K.cleanupDocsUndo] = v }
    /** Inventar: câte poze din „Ultimele N” (ultima alegere; implicit 500). */
    val inventoryLastN: Flow<Int> = context.dataStore.data.map { it[K.inventoryLastN] ?: 500 }
    suspend fun setInventoryLastN(v: Int) = context.dataStore.edit { it[K.inventoryLastN] = v }
    /** Inventar: rădăcina pozelor aleasă ultima dată („Pictures/FORJA/”, „DCIM/FORJA/”…; gol = implicita). */
    val inventoryPhotoRoot: Flow<String> = context.dataStore.data.map { it[K.inventoryPhotoRoot] ?: "" }
    suspend fun setInventoryPhotoRoot(v: String) = context.dataStore.edit { it[K.inventoryPhotoRoot] = v }
    /** Inventar: folderul destinație al documentelor (URI SAF persistat; gol = „Organizate” în folderul ales). */
    val inventoryDocsDest: Flow<String> = context.dataStore.data.map { it[K.inventoryDocsDest] ?: "" }
    suspend fun setInventoryDocsDest(v: String) = context.dataStore.edit { it[K.inventoryDocsDest] = v }

    val kcalTarget: Flow<Int> = context.dataStore.data.map { it[K.kcalTarget] ?: 2250 }
    suspend fun setKcalTarget(v: Int) = context.dataStore.edit { it[K.kcalTarget] = v }

    val weekKmTarget: Flow<Int> = context.dataStore.data.map { it[K.weekKmTarget] ?: 29 }
    suspend fun setWeekKmTarget(v: Int) = context.dataStore.edit { it[K.weekKmTarget] = v }

    val notifOn: Flow<Boolean> = context.dataStore.data.map { it[K.notifOn] ?: true }
    suspend fun setNotifOn(v: Boolean) = context.dataStore.edit { it[K.notifOn] = v }

    val sleepReminder: Flow<Boolean> = context.dataStore.data.map { it[K.sleepReminder] ?: false }
    suspend fun setSleepReminder(v: Boolean) = context.dataStore.edit { it[K.sleepReminder] = v }

    val focusUnlockUntil: Flow<Long> = context.dataStore.data.map { it[K.focusUnlockUntil] ?: 0L }
    suspend fun setFocusUnlockUntil(v: Long) = context.dataStore.edit { it[K.focusUnlockUntil] = v }

    val focusActive: Flow<Boolean> = context.dataStore.data.map { it[K.focusActive] ?: false }
    suspend fun setFocusActive(v: Boolean) = context.dataStore.edit { it[K.focusActive] = v }

    val cachedName: Flow<String> = context.dataStore.data.map { it[K.cachedName] ?: "" }
    suspend fun setCachedName(v: String) = context.dataStore.edit { it[K.cachedName] = v }

    val geminiKey: Flow<String> = context.dataStore.data.map { it[K.geminiKey] ?: "" }
    suspend fun setGeminiKey(v: String) = context.dataStore.edit { it[K.geminiKey] = v.trim() }

    val alarmEnabled: Flow<Boolean> = context.dataStore.data.map { it[K.alarmEnabled] ?: false }
    suspend fun setAlarmEnabled(v: Boolean) = context.dataStore.edit { it[K.alarmEnabled] = v }

    val alarmHour: Flow<Int> = context.dataStore.data.map { it[K.alarmHour] ?: 7 }
    val alarmMinute: Flow<Int> = context.dataStore.data.map { it[K.alarmMinute] ?: 0 }
    suspend fun setAlarmTime(h: Int, m: Int) = context.dataStore.edit {
        it[K.alarmHour] = h; it[K.alarmMinute] = m
    }

    val bgShareOn: Flow<Boolean> = context.dataStore.data.map { it[K.bgShareOn] ?: false }
    suspend fun setBgShareOn(v: Boolean) = context.dataStore.edit { it[K.bgShareOn] = v }

    val ghostUntilLocal: Flow<Long> = context.dataStore.data.map { it[K.ghostUntilLocal] ?: 0L }
    suspend fun setGhostUntilLocal(v: Long) = context.dataStore.edit { it[K.ghostUntilLocal] = v }

    val bgBannerDismissed: Flow<Boolean> = context.dataStore.data.map { it[K.bgBannerDismissed] ?: false }
    suspend fun setBgBannerDismissed() = context.dataStore.edit { it[K.bgBannerDismissed] = true }

    /** Fereastra alarmei circadiene: cu câte minute înainte de ora-limită are voie să sune. */
    val alarmWindowMin: Flow<Int> = context.dataStore.data.map { it[K.alarmWindowMin] ?: 40 }
    suspend fun setAlarmWindowMin(v: Int) = context.dataStore.edit { it[K.alarmWindowMin] = v }

    /** Detox: totul blocat (în afară de esențiale) până la această oră. 0 = oprit. */
    val detoxUntil: Flow<Long> = context.dataStore.data.map { it[K.detoxUntil] ?: 0L }
    suspend fun setDetoxUntil(v: Long) = context.dataStore.edit { it[K.detoxUntil] = v }

    /** Scanarea galeriei (à la Bixby Vision) — strict opt-in. */
    val galleryScanOn: Flow<Boolean> = context.dataStore.data.map { it[K.galleryScanOn] ?: false }
    suspend fun setGalleryScanOn(v: Boolean) = context.dataStore.edit { it[K.galleryScanOn] = v }

    /** Reminder-e blânde, la ore aleatoare — pornit implicit, se poate opri. */
    val nudgesOn: Flow<Boolean> = context.dataStore.data.map { it[K.nudgesOn] ?: true }
    suspend fun setNudgesOn(v: Boolean) = context.dataStore.edit { it[K.nudgesOn] = v }

    // ── Pădurea de Focus: copaci care cresc pe durata focus-ului, se ofilesc la renunțare ──
    /** (crescuți, ofiliți, secunde spre următorul) pentru ziua de azi. */
    val focusForest: Flow<Triple<Int, Int, Int>> = context.dataStore.data.map { p ->
        val today = java.time.LocalDate.now().toEpochDay()
        if ((p[K.focusForestDay] ?: -1L) == today)
            Triple(p[K.focusGrown] ?: 0, p[K.focusWithered] ?: 0, p[K.focusPartialSecs] ?: 0)
        else Triple(0, 0, 0)
    }
    suspend fun addFocusProgress(secs: Int) {
        val today = java.time.LocalDate.now().toEpochDay()
        context.dataStore.edit { p ->
            val same = (p[K.focusForestDay] ?: -1L) == today
            var grown = if (same) (p[K.focusGrown] ?: 0) else 0
            val withered = if (same) (p[K.focusWithered] ?: 0) else 0
            var partial = (if (same) (p[K.focusPartialSecs] ?: 0) else 0) + secs
            while (partial >= 900) { partial -= 900; grown++ }   // un copac la 15 min de focus
            p[K.focusForestDay] = today
            p[K.focusGrown] = grown
            p[K.focusWithered] = withered
            p[K.focusPartialSecs] = partial
            p[K.focusForestHistory] = forestHistoryWith(p[K.focusForestHistory], today, grown, withered)
        }
    }
    /** Oprire manuală: copacul început (≥1 min) se ofilește, cronometrul pleacă de la zero. */
    suspend fun witherFocusTree() {
        val today = java.time.LocalDate.now().toEpochDay()
        context.dataStore.edit { p ->
            val same = (p[K.focusForestDay] ?: -1L) == today
            val grown = if (same) (p[K.focusGrown] ?: 0) else 0
            val partial = if (same) (p[K.focusPartialSecs] ?: 0) else 0
            val withered = (if (same) (p[K.focusWithered] ?: 0) else 0) + (if (partial >= 60) 1 else 0)
            p[K.focusForestDay] = today
            p[K.focusGrown] = grown
            p[K.focusWithered] = withered
            p[K.focusPartialSecs] = 0
            p[K.focusForestHistory] = forestHistoryWith(p[K.focusForestHistory], today, grown, withered)
        }
    }

    suspend fun addFocusBreak() {
        val today = java.time.LocalDate.now().toEpochDay()
        context.dataStore.edit { p ->
            val same = (p[K.focusForestDay] ?: -1L) == today
            val grown = if (same) (p[K.focusGrown] ?: 0) else 0
            val withered = (if (same) (p[K.focusWithered] ?: 0) else 0) + 1
            val partial = if (same) (p[K.focusPartialSecs] ?: 0) else 0
            p[K.focusForestDay] = today
            p[K.focusGrown] = grown
            p[K.focusWithered] = withered
            p[K.focusPartialSecs] = partial
            p[K.focusForestHistory] = forestHistoryWith(p[K.focusForestHistory], today, grown, withered)
        }
    }

    private fun forestHistoryWith(old: String?, today: Long, grown: Int, withered: Int): String =
        com.forja.app.core.focus.MindDocs.forestEncode(com.forja.app.core.focus.MindDocs.forestDecode(old) + (today to (grown to withered)), today)

    /** Pădurea pe zile (epochDay → crescuți, uscați), ultimele 40 de zile — pentru site (FocusMirror). */
    val focusForestHistory: Flow<Map<Long, Pair<Int, Int>>> = context.dataStore.data.map { com.forja.app.core.focus.MindDocs.forestDecode(it[K.focusForestHistory]) }

    /** Opririle paznicului pe zile și pachete (epochDay → cod → n). Doar numărători, niciodată textul prins. */
    val detoxHits: Flow<Map<Long, Map<String, Int>>> = context.dataStore.data.map { com.forja.app.core.focus.MindDocs.hitsDecode(it[K.detoxHits]) }
    suspend fun addDetoxHit(pack: String) {
        val today = java.time.LocalDate.now().toEpochDay()
        context.dataStore.edit { it[K.detoxHits] = com.forja.app.core.focus.MindDocs.addPackHit(it[K.detoxHits], today, pack) }
    }

    // ── Detox de adicție — pe telefon; pe site doar numărătorile (contract v4), cuvintele doar cu detoxWordsOnSite ──
    val detoxOn: Flow<Boolean> = context.dataStore.data.map { it[K.detoxOn] ?: false }
    suspend fun setDetoxOn(v: Boolean) = context.dataStore.edit {
        it[K.detoxOn] = v
        if (v && (it[K.detoxStreakStart] ?: 0L) == 0L) it[K.detoxStreakStart] = System.currentTimeMillis()
    }

    val detoxStreakStart: Flow<Long> = context.dataStore.data.map { it[K.detoxStreakStart] ?: 0L }
    val detoxSlips: Flow<Int> = context.dataStore.data.map { it[K.detoxSlips] ?: 0 }
    /** „Am alunecat” — resetează seria fără rușine, dar păstrează numărul de reveniri. */
    suspend fun detoxSlip() = context.dataStore.edit {
        it[K.detoxStreakStart] = System.currentTimeMillis()
        it[K.detoxSlips] = (it[K.detoxSlips] ?: 0) + 1
    }

    /** Scrisoarea către mine — de ce vreau să scap. Rămâne pe telefon, în afară de acordul separat [detoxWordsOnSite]. */
    val detoxLetter: Flow<String> = context.dataStore.data.map { it[K.detoxLetter] ?: "" }
    suspend fun setDetoxLetter(v: String) = context.dataStore.edit { it[K.detoxLetter] = v }

    /** Cuvintele-declanșator, setate de ea. Pleacă pe site doar cu contractul v4 și acordul separat [detoxWordsOnSite]. */
    val detoxWords: Flow<String> = context.dataStore.data.map { it[K.detoxWords] ?: "" }
    suspend fun setDetoxWords(v: String) = context.dataStore.edit { it[K.detoxWords] = v }

    /** Alt cont a intrat pe telefon (MindOwner): urmele detoxului și ale pădurii celui dinainte nu trec la el. */
    suspend fun clearMindTraces() = context.dataStore.edit {
        it.remove(K.detoxHits); it.remove(K.focusForestHistory); it.remove(K.detoxStreakStart); it.remove(K.detoxSlips)
        it.remove(K.detoxWords); it.remove(K.detoxLetter); it.remove(K.detoxWordsOnSite)
    }

    // ── Prieteni din agendă (ca la Telegram) ──
    /** Numărul MEU, declarat de mine în format E.164 (+407…). Pleacă doar ca antet `x-forja-phone-declared`; serverul ține o amprentă. */
    val phoneDeclared: Flow<String> = context.dataStore.data.map { it[K.phoneDeclared] ?: "" }
    suspend fun setPhoneDeclared(v: String) = context.dataStore.edit { it[K.phoneDeclared] = v.trim() }
    /** Comutatorul „Pot fi găsit după număr” + compararea zilnică a agendei — strict opt-in. */
    val contactsOn: Flow<Boolean> = context.dataStore.data.map { it[K.contactsOn] ?: false }
    suspend fun setContactsOn(v: Boolean) = context.dataStore.edit { it[K.contactsOn] = v }
    val contactsSyncedAt: Flow<Long> = context.dataStore.data.map { it[K.contactsSyncedAt] ?: 0L }
    suspend fun setContactsSyncedAt(v: Long) = context.dataStore.edit { it[K.contactsSyncedAt] = v }
    /** Potrivirile (JSON: uid, numele din agendă, mutual, verified, at) — doar pe telefon. */
    val contactMatches: Flow<String> = context.dataStore.data.map { it[K.contactMatches] ?: "" }
    suspend fun setContactMatches(v: String) = context.dataStore.edit { it[K.contactMatches] = v }
    /** Starea ultimei sincronizări, în cuvinte — arătată în Echipare și Profil. */
    val contactsStatus: Flow<String> = context.dataStore.data.map { it[K.contactsStatus] ?: "" }
    suspend fun setContactsStatus(v: String) = context.dataStore.edit { it[K.contactsStatus] = v }

    // ── Contractul de securitate ──
    /** Momentul semnării (0 = nesemnat). */
    val contractSignedAt: Flow<Long> = context.dataStore.data.map { it[K.contractSignedAt] ?: 0L }
    /** Versiunea semnată (0 = nesemnat). */
    val contractVersion: Flow<Int> = context.dataStore.data.map { it[K.contractVersion] ?: 0 }
    /** Semnat cel puțin la versiunea `v` (și nerevocat: revocarea șterge semnătura). Ce e nou în v4: contractAtLeast(4). */
    fun contractAtLeast(v: Int): Flow<Boolean> = context.dataStore.data.map {
        (it[K.contractSignedAt] ?: 0L) > 0L && (it[K.contractVersion] ?: 0) >= v
    }
    /**
     * Semnat cel puțin la v3 ([CONTRACT_BASE]) — condiția pentru tot ce pleacă pe site din v3 (sincronizarea, galeria,
     * explorarea, agenda, găsirea, muzica, antrenamentele, Inventarul). Nu se oprește nimic la trecerea la v4.
     */
    val contractSigned: Flow<Boolean> = contractAtLeast(CONTRACT_BASE)
    /** Semnat la versiunea curentă ([CONTRACT_VERSION]): ecranul contractului arată „Semnat.” doar atunci. */
    val contractCurrent: Flow<Boolean> = contractAtLeast(CONTRACT_VERSION)
    /** Semnat, dar o versiune mai veche: contractul are rânduri noi; lucrul legat de contract stă până la re-semnare. */
    val contractNeedsResign: Flow<Boolean> = context.dataStore.data.map {
        (it[K.contractSignedAt] ?: 0L) > 0L && (it[K.contractVersion] ?: 0) in 1 until CONTRACT_VERSION
    }
    /** Versiunea pentru care foaia de re-semnare s-a arătat deja (o singură dată pe versiune). */
    val contractPromptVersion: Flow<Int> = context.dataStore.data.map { it[K.contractPromptVersion] ?: 0 }
    suspend fun setContractPromptSeen() = context.dataStore.edit { it[K.contractPromptVersion] = CONTRACT_VERSION }
    /** Contul care a semnat (null = semnătură dinainte de 4.4, fără cont reținut). */
    val contractUid: Flow<String?> = context.dataStore.data.map { it[K.contractUid] }
    suspend fun setContractSigned(at: Long, version: Int = CONTRACT_VERSION, uid: String? = null) = context.dataStore.edit {
        it[K.contractSignedAt] = at
        it[K.contractVersion] = version
        if (uid != null) it[K.contractUid] = uid else it.remove(K.contractUid)
    }
    /**
     * Cuvintele de care te lași și scrisoarea (detox), pe site. Oprit implicit: sunt promise „Rămân pe telefon”; pleacă
     * doar cu contractul v4 și cu acest acord separat, pornit de ea.
     */
    val detoxWordsOnSite: Flow<Boolean> = context.dataStore.data.map { it[K.detoxWordsOnSite] ?: false }
    suspend fun setDetoxWordsOnSite(v: Boolean) = context.dataStore.edit { it[K.detoxWordsOnSite] = v }
    /** Revocare sau ieșire din cont: semnătura dispare de pe telefon. */
    suspend fun clearContract() = context.dataStore.edit {
        it.remove(K.contractSignedAt)
        it.remove(K.contractVersion)
        it.remove(K.contractUid)
        // Acordul separat pentru cuvintele detoxului ține de semnătură: revocat sau alt cont, pornește iar oprit.
        it.remove(K.detoxWordsOnSite)
    }

    // ── „Hei FORJA" — asistentul vocal, pentru cei care nu pot (sau nu vor) să se uite la ecran ──
    /** Ascultare continuă a cuvântului „Hei FORJA" (serviciu în fundal, microfon pornit). */
    val voiceWakeOn: Flow<Boolean> = context.dataStore.data.map { it[K.voiceWakeOn] ?: false }
    suspend fun setVoiceWakeOn(v: Boolean) = context.dataStore.edit { it[K.voiceWakeOn] = v }

    /** Răspunsurile asistentului sunt citite cu voce tare. */
    val voiceSpeakOn: Flow<Boolean> = context.dataStore.data.map { it[K.voiceSpeakOn] ?: true }
    suspend fun setVoiceSpeakOn(v: Boolean) = context.dataStore.edit { it[K.voiceSpeakOn] = v }

    /** Înainte de a trimite un mesaj, asistentul îl citește și cere „da". */
    val voiceConfirmSend: Flow<Boolean> = context.dataStore.data.map { it[K.voiceConfirmSend] ?: true }
    suspend fun setVoiceConfirmSend(v: Boolean) = context.dataStore.edit { it[K.voiceConfirmSend] = v }

    /** Limba în care ascultă („ro-RO" sau „en-US"). Comenzile se înțeleg în ambele. */
    val voiceLang: Flow<String> = context.dataStore.data.map { it[K.voiceLang] ?: "ro-RO" }
    suspend fun setVoiceLang(v: String) = context.dataStore.edit { it[K.voiceLang] = v }

    /** Prezentarea asistentului a fost citită o dată la prima deschidere. */
    val voiceIntroSeen: Flow<Boolean> = context.dataStore.data.map { it[K.voiceIntroSeen] ?: false }
    suspend fun setVoiceIntroSeen() = context.dataStore.edit { it[K.voiceIntroSeen] = true }

    /** Ecranul de pornire cu permisiuni a fost arătat o dată. */
    val permsIntroSeen: Flow<Boolean> = context.dataStore.data.map { it[K.permsIntroSeen] ?: false }
    suspend fun setPermsIntroSeen() = context.dataStore.edit { it[K.permsIntroSeen] = true }

    /** „De la capăt": la ieșirea din cont, prezentarea și permisiunile inițiale se văd din nou. */
    suspend fun resetFirstRun() = context.dataStore.edit {
        it.remove(K.onboardingDone)
        it.remove(K.permsIntroSeen)
        it.remove(K.introSeenVersion)
        it.remove(K.gearSeenVersion)
    }
}
