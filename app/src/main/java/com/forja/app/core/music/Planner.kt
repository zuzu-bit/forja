package com.forja.app.core.music

/**
 * Planurile de pornire (music-start.md §6.3–6.4, workout-music.md §3.8): ordinea treptelor pentru o intenție, din ce
 * se vede acum. Funcție pură: mașina ([StartMachine]) o recheamă după fiecare treaptă eșuată, cu un instantaneu nou,
 * și ia primul pas neîncercat — așa o sesiune trezită de tasta media schimbă planul din mers.
 *
 * Reguli care nu se negociază:
 * - Play pe o sesiune arătată comandă exact sesiunea aceea (niciodată Spotify în locul cărții).
 * - Tasta media se trimite, cu acces, doar dacă ținta ei e un player de muzică (altfel ar trezi o carte, YouTube…).
 * - Treptele vizibile vin după cele invizibile; cele terminale (doar deschid playerul) la urmă.
 */
object Planner {

    private val SPOTIFY = MusicKind.SPOTIFY

    fun plan(want: Want, s: Snapshot): List<Step> = when (want) {
        is Want.Resume -> resume(want, s)
        Want.MyMusic -> myMusic(s)
        Want.Top -> top(s)
        is Want.Workout -> workout(want, s)
        is Want.Probe -> probe(want, s)
    }

    /** Muzica cântă deja (intenții „muzica mea”): nu se trimite nimic. */
    fun alreadyPlaying(want: Want, s: Snapshot): Boolean = when (want) {
        Want.MyMusic, is Want.Workout -> s.musicPlaying
        else -> false
    }

    // ───────────────────────────── Resume ─────────────────────────────

    private fun resume(want: Want.Resume, s: Snapshot): List<Step> {
        val t = s.session(want.sessionId)
        if (t == null) {
            // Sesiunea a dispărut (playerul a murit): Play pe cartea arătată nu pornește altceva.
            return listOf(Step(Rung.S_PLAY, sessionId = want.sessionId, skip = "no-session"))
        }
        return listOf(
            sessionPlay(t, s),
            Step(Rung.S_BTN, t.pkg, t.id),
            Step(Rung.O_SESSION, t.pkg, t.id)
        )
    }

    private fun sessionPlay(t: SessionView, s: Snapshot): Step =
        if (t.can(SessionView.ACTION_PLAY) || t.can(SessionView.ACTION_PLAY_PAUSE)) Step(Rung.S_PLAY, t.pkg, t.id)
        else Step(Rung.S_PLAY, t.pkg, t.id, skip = "no-play-action:0x${t.actions.toString(16)}")

    // ───────────────────────────── „Pornește muzica” ─────────────────────────────

    private fun myMusic(s: Snapshot): List<Step> {
        val out = ArrayList<Step>()
        if (s.access) {
            out += invisibleMusic(s, top = s.top, wantLiked = true)
        } else {
            out += keyNoAccess(s)
        }
        out += visibleMusic(s, s.top)
        out += terminal(s)
        return filterLearned(out, s)
    }

    /**
     * Treptele invizibile „muzica mea”, cu acces:
     * 1) o sesiune de MUZICĂ pe pauză → S_PLAY, S_BTN;
     * 2) o sesiune a playerului tău care ține altceva (carte, podcast, nimic) → S_TOP (piesa ta), S_LIKED (Spotify);
     * 3) fără sesiune: tasta media doar dacă ajunge la un player de muzică (K_TOKEN, apoi K_PLAY).
     */
    private fun invisibleMusic(s: Snapshot, top: TrackRef?, wantLiked: Boolean): List<Step> {
        val out = ArrayList<Step>()
        val pausedMusic = s.sessions
            .filter { it.kind == MediaKind.MUSIC && it.hasMetadata && it.state != PState.PLAYING && !it.remote }
            .sortedByDescending { it.pkg == s.preferredPkg }
            .firstOrNull()
        if (pausedMusic != null) {
            out += sessionPlay(pausedMusic, s)
            out += Step(Rung.S_BTN, pausedMusic.pkg, pausedMusic.id)
        }
        val playerPkg = top?.pkg?.takeIf { s.localOf(it) != null } ?: s.preferredPkg
        val player = s.localOf(playerPkg)
        if (player != null) {
            if (top != null) {
                out += if (player.can(SessionView.ACTION_PLAY_FROM_MEDIA_ID) || player.can(SessionView.ACTION_PLAY_FROM_SEARCH) ||
                    player.can(SessionView.ACTION_PLAY_FROM_URI)
                ) Step(Rung.S_TOP, player.pkg, player.id, top)
                else Step(Rung.S_TOP, player.pkg, player.id, top, skip = "no-from-actions")
            }
            if (wantLiked && player.pkg == SPOTIFY) out += Step(Rung.S_LIKED, SPOTIFY, player.id)
            // „Orice muzică”: doar dacă ce ține playerul acum e muzică (altfel ar relua cartea).
            if (player.kind == MediaKind.MUSIC && player.hasMetadata && pausedMusic?.id != player.id) {
                out += Step(Rung.S_ANY, player.pkg, player.id)
            }
        }
        out += keyWithAccess(s, pausedMusic == null && player == null)
        return out
    }

    private fun keyWithAccess(s: Snapshot, noSession: Boolean): List<Step> {
        val target = s.keyTarget
        if (s.sdk < 33) {
            // Android < 13 nu spune cine primește tasta: doar când nu există nicio sesiune care s-o fure.
            return if (noSession && s.sessions.isEmpty()) listOf(Step(Rung.K_PLAY, s.preferredPkg)) else emptyList()
        }
        // Nimeni nu primește tasta (niciun player folosit de la pornire): ar cădea în gol, deci nu așteptăm degeaba.
        if (target == null) return listOf(Step(Rung.K_PLAY, null, skip = "keyTarget:none"))
        if (!s.wanted(target)) return listOf(Step(Rung.K_PLAY, target, skip = "keyTarget:${diagPkg(target)}"))
        // Ținta e deja o sesiune din listă: treptele S_* de mai sus o acoperă.
        if (s.localOf(target) != null) return emptyList()
        val out = ArrayList<Step>()
        if (s.keyTokenOutside) out += Step(Rung.K_TOKEN, target)
        out += Step(Rung.K_PLAY, target)
        return out
    }

    /** Fără acces: singura treaptă invizibilă e tasta media, verificată prin tipul de conținut al playerului. */
    private fun keyNoAccess(s: Snapshot): List<Step> = listOf(Step(Rung.K_PLAY, null))

    /** Treptele vizibile (o atingere): Spotify în ordinea învățată, celelalte playere prin „caută și cântă”. */
    private fun visibleMusic(s: Snapshot, top: TrackRef?): List<Step> {
        val pkg = visiblePlayer(s) ?: return emptyList()
        val out = ArrayList<Step>()
        if (pkg == SPOTIFY) {
            val trackId = MusicKind.spotifyTrackId(top?.mediaId)?.takeIf { top?.pkg == null || top.pkg == SPOTIFY }
            val order = s.learned.order(SPOTIFY, s.versions[SPOTIFY], listOf(Rung.V_TRACK, Rung.V_LIKED_PLAY, Rung.V_PFS_DATA, Rung.V_PFS_TOP, Rung.V_PFS_ANY))
            for (r in order) when (r) {
                Rung.V_TRACK -> if (trackId != null && top != null) out += Step(Rung.V_TRACK, SPOTIFY, track = top)
                Rung.V_PFS_TOP -> if (top != null) out += Step(Rung.V_PFS_TOP, SPOTIFY, track = top)
                Rung.V_PFS_ANY -> if (SPOTIFY in s.searchable) out += Step(Rung.V_PFS_ANY, SPOTIFY)
                else -> out += Step(r, SPOTIFY)
            }
        } else {
            if (top != null && (top.pkg == null || top.pkg == pkg) && pkg in s.searchable) out += Step(Rung.V_PFS_TOP, pkg, track = top)
            if (pkg in s.searchable) out += Step(Rung.V_PFS_ANY, pkg)
        }
        return out
    }

    /**
     * Playerul în care sare o treaptă vizibilă: al tău (instalat sau din istoric); altfel Spotify, cât nu s-a dovedit
     * că lipsește (startActivity nu cere vizibilitatea pachetului: o detecție care minte nu mai ascunde Spotify, 29.09);
     * altfel primul player de muzică știut. null = nimic de deschis.
     */
    private fun visiblePlayer(s: Snapshot): String? =
        s.preferredPkg?.takeIf { (it in s.installed || it in s.historyPkgs) && it !in s.absent }
            ?: SPOTIFY.takeIf { it !in s.absent }
            ?: s.installed.firstOrNull { MusicKind.isMusicPlayer(it, s.historyPkgs) && it !in s.absent }

    /**
     * Ultima soluție: deschide playerul (Melodii apreciate în Spotify, altfel aplicația playerului), apăsarea pe play
     * rămâne a ei. Fără niciun player nu există pas final: încercarea se termină „Nu a pornit.” fără nimic de deschis
     * (nu mai există O_LAUNCH fără pachet — selectorul de muzică al sistemului nu răspundea pe S23).
     */
    private fun terminal(s: Snapshot): List<Step> = when (val pkg = visiblePlayer(s)) {
        null -> emptyList()
        SPOTIFY -> listOf(Step(Rung.O_LIKED_PAGE, SPOTIFY))
        else -> listOf(Step(Rung.O_LAUNCH, pkg))
    }

    /**
     * „Deschide playerul” fără o încercare în așteptare (apăsarea lungă pe disc, o atingere după final): sesiunea de
     * muzică pe care o vede; altfel playerul ei din istoric, când nu e Spotify; altfel Spotify la Melodii apreciate
     * (cât nu s-a dovedit că lipsește). Niciodată un video sau o carte audio (Music.other): pentru muzică, YouTube nu e
     * un răspuns. null = nimic de deschis. [preferred] = playerul ei văzut cântând (istoric / ultimul), nu o presupunere.
     */
    fun openStep(now: SessionView?, preferred: String?, absent: Set<String>): Step? {
        if (now != null && now.kind != MediaKind.SPOKEN && now.kind != MediaKind.VIDEO && now.pkg !in absent) {
            return Step(Rung.O_SESSION, now.pkg, now.id)
        }
        val own = preferred?.takeIf { it != SPOTIFY && it !in absent && it !in MusicKind.SPOKEN_APPS && it !in MusicKind.VIDEO_APPS }
        if (own != null) return Step(Rung.O_LAUNCH, own)
        if (SPOTIFY !in absent) return Step(Rung.O_LIKED_PAGE, SPOTIFY)
        return null
    }

    // ───────────────────────────── TOP 1 ─────────────────────────────

    private fun top(s: Snapshot): List<Step> {
        val top = s.top ?: return myMusic(s)
        val out = ArrayList<Step>()
        if (s.access) {
            val pkg = top.pkg ?: s.preferredPkg
            val player = s.localOf(pkg)
            if (player != null) {
                out += Step(Rung.S_TOP, player.pkg, player.id, top)
            } else if (pkg != null && s.sdk >= 33 && s.keyTarget == pkg) {
                if (s.keyTokenOutside) out += Step(Rung.K_TOKEN, pkg)
                out += Step(Rung.K_PLAY, pkg)
            } else if (pkg != null) {
                out += Step(Rung.K_PLAY, s.keyTarget ?: pkg, skip = "keyTarget:${diagPkg(s.keyTarget)}")
            }
        }
        val pkg = top.pkg ?: visiblePlayer(s)
        if (pkg == SPOTIFY && SPOTIFY !in s.absent && MusicKind.spotifyTrackId(top.mediaId) != null) {
            out += Step(Rung.V_TRACK, SPOTIFY, track = top)
        }
        if (pkg != null && pkg in s.searchable) out += Step(Rung.V_PFS_TOP, pkg, track = top)
        // Apoi planul „Pornește muzica” de la treptele vizibile.
        out += visibleMusic(s, top)
        out += terminal(s)
        return filterLearned(out.distinctBy { it.key }, s)
    }

    // ───────────────────────────── Antrenament ─────────────────────────────

    /**
     * Pornirea odată cu sesiunea: piesa 1 din lista FORJA pe sesiunea playerului (S_TOP), altfel Melodii apreciate
     * (S_LIKED), altfel reia muzica pusă pe pauză; fără sesiune, tasta media (doar spre un player de muzică). Apoi
     * treptele vizibile: din 4.4.1 „Începe sesiunea” e chiar atingerea, deci cel mult un salt, în 1,5 s de la ea.
     * Fără listă (rece, sau ea a ales Apreciate: hubul spune „APRECIATE”), saltul duce la Melodii apreciate, nu la
     * piesa ei de top (o singură piesă, apoi radioul lui Spotify); TOP 1 și „Pornește muzica” o păstrează.
     */
    private fun workout(want: Want.Workout, s: Snapshot): List<Step> {
        val out = ArrayList<Step>()
        val first = want.first
        if (s.access) {
            val playerPkg = first?.pkg?.takeIf { s.localOf(it) != null } ?: s.preferredPkg
            val player = s.localOf(playerPkg)
            if (player != null) {
                if (first != null) out += Step(Rung.S_TOP, player.pkg, player.id, first)
                if (player.pkg == SPOTIFY) out += Step(Rung.S_LIKED, SPOTIFY, player.id)
            }
            val pausedMusic = s.sessions.firstOrNull { it.kind == MediaKind.MUSIC && it.hasMetadata && it.state != PState.PLAYING && !it.remote }
            if (pausedMusic != null) {
                out += sessionPlay(pausedMusic, s)
                out += Step(Rung.S_BTN, pausedMusic.pkg, pausedMusic.id)
            }
            out += keyWithAccess(s, player == null && pausedMusic == null)
        } else {
            out += keyNoAccess(s)
        }
        out += visibleMusic(s, first)
        out += terminal(s)
        return filterLearned(out.distinctBy { it.key }, s)
    }

    // ───────────────────────────── Proba ─────────────────────────────

    private fun probe(want: Want.Probe, s: Snapshot): List<Step> {
        val pkg = want.pkg
        val session = s.localOf(pkg)
        val r = want.rung
        val step = when (r) {
            Rung.S_PLAY, Rung.S_BTN, Rung.S_ANY, Rung.S_LIKED, Rung.O_SESSION ->
                if (!s.access) Step(r, pkg, skip = "no-access")
                else if (session == null) Step(r, pkg, skip = "no-session")
                else if (r == Rung.S_LIKED && pkg != SPOTIFY) Step(r, pkg, skip = "not-spotify")
                else Step(r, pkg, session.id)
            Rung.S_TOP ->
                if (!s.access) Step(r, pkg, skip = "no-access")
                else if (session == null) Step(r, pkg, skip = "no-session")
                else if (s.top == null) Step(r, pkg, session.id, skip = "no-top")
                else Step(r, pkg, session.id, s.top)
            Rung.K_TOKEN ->
                if (!s.access || s.sdk < 33) Step(r, pkg, skip = "no-access")
                else if (!s.keyTokenOutside || s.keyTarget != pkg) Step(r, pkg, skip = "keyTarget:${diagPkg(s.keyTarget)}")
                else Step(r, pkg)
            Rung.K_PLAY ->
                if (s.access && s.sdk >= 33 && s.keyTarget != pkg) Step(r, pkg, skip = "keyTarget:${diagPkg(s.keyTarget)}")
                else Step(r, pkg)
            Rung.V_TRACK ->
                if (MusicKind.spotifyTrackId(s.top?.mediaId) == null) Step(r, pkg, skip = "no-track-id") else Step(r, pkg, track = s.top)
            Rung.V_PFS_TOP -> if (s.top == null) Step(r, pkg, skip = "no-top") else Step(r, pkg, track = s.top)
            Rung.V_PFS_ANY -> if (pkg !in s.searchable) Step(r, pkg, skip = "no-search-activity") else Step(r, pkg)
            Rung.V_LIKED_PLAY, Rung.V_PFS_DATA, Rung.O_LIKED_PAGE -> if (pkg != SPOTIFY) Step(r, pkg, skip = "not-spotify") else Step(r, pkg)
            Rung.O_LAUNCH -> Step(r, pkg)
            Rung.ALREADY -> Step(r, pkg, skip = "not-a-rung")
        }
        return listOf(step)
    }

    // ───────────────────────────── Ajutoare ─────────────────────────────

    /** Treptele cu 2 eșecuri la rând în ultimele 14 zile rămân în plan, dar marcate „sărite” (se văd în jurnal). */
    private fun filterLearned(steps: List<Step>, s: Snapshot): List<Step> {
        val now = s.clock
        return steps.map { st ->
            if (st.skip == null && !st.rung.terminal && s.learned.skip(st.pkg, s.versions[st.pkg], st.rung, now)) st.copy(skip = "learned-fail")
            else st
        }
    }

    /** Pachetul, pentru jurnal: numele aplicației, fără altceva. */
    fun diagPkg(pkg: String?): String = pkg?.takeIf { it.isNotBlank() }?.take(80) ?: "none"
}
