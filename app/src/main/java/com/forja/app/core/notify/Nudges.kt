package com.forja.app.core.notify

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.forja.app.ForjaApp
import com.forja.app.core.data.db.PlaceEntity
import com.forja.app.navigation.Route
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Casca — mesajele personale ale FORJA (notifications-design.md §B–§I). Punctul unic de intrare:
 *  - [start] din ForjaApp: lucrătorul periodic unic (KEEP) și reminderul de culcare urmărit după setări;
 *  - [onAppOpen] din MainActivity (ON_START): „a mers” pentru mesajul în așteptare, pozele mascotei, replica
 *    notificării permanente;
 *  - evenimentele venite din module (raportul nopții, loc nou, camarad nou, Focus, mese găsite), trecute prin
 *    aceleași reguli: orele de liniște, anti-repetare, versiunea publică, grupuri, id-uri distincte.
 */
object Nudges {
    const val WORK = "forja_coach"
    /** Lucrarea 4.3 (o singură dată pe zi, la oră aleatoare) — anulată la prima pornire. */
    private const val OLD_WORK = "forja_nudge"

    fun start(app: ForjaApp) {
        schedule(app)
        app.appScope.launch {
            // Reminderul de culcare urmează comutatorul din Profil și ora alarmei, oricând se schimbă.
            try {
                combine(app.prefs.sleepReminder, app.prefs.alarmEnabled, app.prefs.alarmHour, app.prefs.alarmMinute) { a, b, c, d ->
                    listOf(a, b, c, d)
                }.distinctUntilChanged().collect { Bedtime.sync(app) }
            } catch (_: Exception) { }
        }
        app.appScope.launch {
            // „Zero după Revocă” (§D.15): orice semnătură văzută cât trăiește procesul (și una revocată între două ture
            // ale lucrătorului) rămâne ținută minte; contractul nu mai e cerut niciodată pe acest telefon.
            try { app.prefs.contractSignedAt.collect { at -> if (at > 0L) markSigned(app) } } catch (_: Exception) { }
        }
    }

    private fun markSigned(c: Context) {
        if (!NudgeStore.read(c).everSigned) NudgeStore.update(c) { it.copy(everSigned = true) }
    }

    /** Lucrătorul orar: unic, KEEP (nu se reprogramează la fiecare pornire, cum făcea 4.3 cu REPLACE). */
    fun schedule(c: Context) {
        try {
            val wm = WorkManager.getInstance(c)
            wm.cancelUniqueWork(OLD_WORK)
            val req = PeriodicWorkRequestBuilder<NudgeWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (_: Exception) { }
    }

    /** Aplicația a fost deschisă (MainActivity, ON_START). */
    fun onAppOpen(app: ForjaApp) {
        val now = System.currentTimeMillis()
        NudgeStore.update(app) { NudgeRules.onOpened(it, now) }
        // Mesajul „coach” și-a făcut treaba: ești în aplicație.
        Notifier.cancel(app, NotifIds.COACH)
        app.appScope.launch(Dispatchers.Default) {
            try { MascotIcons.ensure(app) } catch (_: Throwable) { }
            try { SyncNotice.refresh(app) } catch (_: Exception) { }
            try { Bedtime.sync(app) } catch (_: Exception) { }
            schedule(app)
        }
    }

    // ─────────────────────────── Tura orară (NudgeWorker) ───────────────────────────

    suspend fun tick(app: ForjaApp, now: Long = System.currentTimeMillis()) {
        val day = NudgeSnapshot.dayOf(now)
        val clock = NudgeSnapshot.clock(now)
        var state = NudgeStore.update(app) { s ->
            val r = NudgeRules.resolvePending(s, now)
            if (r.lastOpen == 0L) r.copy(lastOpen = now) else r
        }
        if (NudgeRules.isQuiet(clock.hour, clock.minute)) return
        deliverHeld(app, now, post = notifOn(app))
        if (!togglesOn(app) || appInForeground(app)) return
        if (now - state.lastOpen < NudgeRules.OPEN_QUIET_MS) return

        val friends = if (Notifier.canPost(app, Channels.SOCIAL)) {
            try { NudgeSnapshot.friends(app, now) } catch (_: Exception) { emptyList() }
        } else emptyList()
        var data = NudgeSnapshot.read(app, now, friends)
        val signedAt = try { app.prefs.contractSignedAt.first() } catch (_: Exception) { 0L }
        state = NudgeStore.update(app) { s ->
            NudgeRules.trackRuns(s, data.streaks, day).let { if (signedAt > 0 && !it.everSigned) it.copy(everSigned = true) else it }
        }

        // Cel mult o notificare pe tură: un prag sau un prieten aproape amână mesajul ferestrei la tura următoare.
        var posted = false

        // 1) Pragul sau recordul seriei (eveniment: nu intră în plafon).
        if (Notifier.canPost(app, Channels.COACH)) {
            val ms = NudgeRules.milestoneDue(state, data.streaks)
            val rec = if (ms == null) NudgeRules.recordDue(state, data.streaks) else null
            val hit = ms ?: rec?.first
            if (hit != null) {
                val d = data.copy(milestone = hit, recordOld = rec?.second ?: 0)
                val r = Nudge.pick(NudgeContext.Milestone, d, NudgeRules.recent(state, Channels.COACH))
                if (r != null && Notifier.post(app, r, coachSpec(NotifIds.MILESTONE, NudgeContext.Milestone, d, tracked = false))) {
                    state = NudgeStore.update(app) {
                        NudgeRules.onMilestone(NudgeRules.onPosted(it, r, Channels.COACH, now, day), hit, record = rec != null)
                    }
                    posted = true
                }
            }
        }

        // 2) Un prieten aproape (≤ 1/prieten/zi, ≤ 2/zi), niciodată fantomă, familie sau somn.
        val near = friends.filter { (it.distanceM ?: Int.MAX_VALUE) in 150..999 && NudgeRules.friendAllowed(state, it.uid, day) }
            .minByOrNull { it.distanceM ?: Int.MAX_VALUE }
        if (near != null && !posted) {
            val d = data.copy(friend = near)
            val r = Nudge.pick(NudgeContext.FriendNear, d, NudgeRules.recent(state, Channels.SOCIAL))
            val spec = Notifier.Spec(
                id = NotifIds.friendNear(near.uid), channel = Channels.SOCIAL, group = Groups.SOCIAL, route = Route.MAP,
                publicTitle = "Un camarad e pe hartă.", category = NotificationCategory.SOCIAL
            )
            if (r != null && Notifier.post(app, r, spec)) {
                state = NudgeStore.update(app) { NudgeRules.onFriend(NudgeRules.onPosted(it, r, Channels.SOCIAL, now, day), near.uid, day) }
                posted = true
            }
        }
        if (posted) return

        // 3) Mesajul „coach” al ferestrei (plafon 3/zi, ≥ 3 h, auto-reglare, revenire, permisiuni).
        val absent = NudgeRules.absentDays(state.lastOpen, now, NudgeSnapshot::dayOf)
        data = data.copy(
            absentDays = absent,
            comebackStep = NudgeRules.comebackStep(absent, state.comebackSteps),
            oldStreak = NudgeRules.oldStreak(state),
            permission = if (NudgeRules.window(clock.hour, clock.minute) == Window.Midday) permissionSubject(app, state, now, signedAt) else null
        )
        val plan = NudgeRules.planCoach(state, data, day) ?: return
        if (!Notifier.canPost(app, Channels.COACH)) return
        val r = Nudge.pick(plan.context, data, NudgeRules.recent(state, Channels.COACH)) ?: return
        if (!Notifier.post(app, r, coachSpec(NotifIds.COACH, plan.context, data, tracked = true))) return
        NudgeStore.update(app) { s ->
            var n = NudgeRules.onPosted(s, r, Channels.COACH, now, day, plan.windowKey)
            if (plan.context == NudgeContext.Comeback) n = NudgeRules.onComeback(n, data.comebackStep)
            if (plan.context == NudgeContext.Permission) data.permission?.let { n = NudgeRules.onPermission(n, it, now) }
            n
        }
    }

    private fun coachSpec(id: Int, ctx: NudgeContext, d: NudgeData, tracked: Boolean): Notifier.Spec {
        val pub = Nudge.publicOf(ctx, d)
        return Notifier.Spec(
            id = id, channel = Channels.COACH, group = Groups.COACH, route = Route.DASHBOARD, echo = true,
            publicTitle = pub?.title ?: "FORJA", publicText = pub?.body, trackDismiss = tracked
        )
    }

    /** Contract nesemnat niciodată, locația „tot timpul” sau agenda — doar cu contractul semnat (zero după Revocă). */
    private suspend fun permissionSubject(app: ForjaApp, s: NudgeState, now: Long, signedAt: Long): String? {
        fun has(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
        val wantsContacts = signedAt > 0L && !has(Manifest.permission.READ_CONTACTS) && contactsWanted(app)
        val subjects = buildList {
            if (signedAt == 0L && !s.everSigned) add("contract")
            if (signedAt > 0L) {
                val loc = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
                // „Tot timpul” există doar de la Android 10 (API 29); pe 8/9 locația dată e deja și în fundal.
                if (Build.VERSION.SDK_INT >= 29 && loc && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) add("bg_location")
                if (wantsContacts) add("contacts")
            }
        }
        val due = subjects.filter { NudgeRules.permissionDue(s, it, now) }
        // Contractul se cere doar unui cont care nu l-a semnat niciodată (nici revocat înainte de 4.4, nici pe alt
        // telefon): users/{uid}.contract lipsește. La îndoială (fără rețea), tăcem.
        return due.firstOrNull { it != "contract" || neverSignedOnAccount(app) }
    }

    private suspend fun neverSignedOnAccount(app: ForjaApp): Boolean {
        val uid = app.auth.currentUid ?: return false
        val doc = try {
            withTimeoutOrNull(5_000L) { FirebaseFirestore.getInstance().collection("users").document(uid).get().await() }
        } catch (_: Exception) { null } ?: return false
        if (doc.contains("contract")) { markSigned(app); return false }
        return true
    }

    private suspend fun contactsWanted(app: ForjaApp): Boolean =
        try { app.prefs.contactsOn.first() } catch (_: Exception) { false }

    /** Mesajele reținute în orele de liniște (camarad nou) pleacă după 08:00, dacă nu s-au învechit și „Notificări” e pornit. */
    private fun deliverHeld(app: ForjaApp, now: Long, post: Boolean) {
        var held: List<HeldRec> = emptyList()
        NudgeStore.update(app) { held = it.held; if (it.held.isEmpty()) it else it.copy(held = emptyList()) }
        if (!post) return
        for (h in held) {
            if (now - h.at > NudgeRules.HELD_TTL_MS) continue
            val ctx = NudgeContext.entries.firstOrNull { it.name == h.ctx } ?: continue
            val pose = NudgePose.entries.firstOrNull { it.name == h.pose } ?: NudgePose.Talking
            val r = Rendered(h.id, ctx, h.title, h.body, pose, h.private, h.localOnly)
            Notifier.post(app, r, specFor(ctx, h.notifId))
        }
    }

    /** „Notificări” (Profil) și comutatorul mesajelor: amândouă pornite. */
    private suspend fun togglesOn(app: ForjaApp): Boolean = try {
        app.prefs.notifOn.first() && app.prefs.nudgesOn.first()
    } catch (_: Exception) { true }

    /**
     * „Notificări” din Profil e comutatorul general al Cascăi: oprit, nu pleacă nimic de aici — nici camarad nou, nici
     * raportul nopții, nici culcarea. Rămân doar notificările serviciilor pornite de tine (sincronizare, GO, Stingerea).
     */
    private suspend fun notifOn(app: ForjaApp): Boolean = try { app.prefs.notifOn.first() } catch (_: Exception) { true }

    /** Cu FORJA pe ecran, Casca tace. (Serviciul de sincronizare nu contează ca „pe ecran”.) */
    private fun appInForeground(c: Context): Boolean = try {
        val am = c.getSystemService(ActivityManager::class.java)
        am?.runningAppProcesses?.any {
            it.pid == Process.myPid() && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    } catch (_: Exception) { false }

    // ─────────────────────────── Evenimente din module ───────────────────────────

    /** Trimite un eveniment după regulile orelor de liniște (§E.5): Drop / Hold / Silent / Pass. */
    private fun postEvent(c: Context, ctx: NudgeContext, d: NudgeData, spec: Notifier.Spec, channel: String): Boolean {
        val now = d.now
        val quiet = NudgeRules.isQuiet(d.hour, d.minute)
        val policy = NudgeRules.quietPolicy(ctx)
        val state = NudgeStore.read(c)
        val r = Nudge.pick(ctx, d, NudgeRules.recent(state, channel)) ?: return false
        if (quiet && policy == QuietPolicy.Drop) return false
        if (quiet && policy == QuietPolicy.Hold) {
            NudgeStore.update(c) {
                it.copy(held = (it.held + HeldRec(ctx.name, r.id, r.title, r.body, r.pose.name, r.private, r.localOnly, now, spec.id, channel)).takeLast(10))
            }
            return false
        }
        val ok = Notifier.post(c, r, if (quiet) spec.copy(silent = true) else spec)
        if (ok) NudgeStore.update(c) { NudgeRules.onPosted(it, r, channel, now, NudgeSnapshot.dayOf(now)) }
        return ok
    }

    private fun specFor(ctx: NudgeContext, id: Int): Notifier.Spec = when (ctx) {
        NudgeContext.NewFriend -> Notifier.Spec(id, Channels.SOCIAL, Groups.SOCIAL, route = Route.MAP,
            publicTitle = "Camarad nou.", publicText = "Cineva din agenda ta e pe FORJA.", category = NotificationCategory.SOCIAL)
        NudgeContext.NewPlace -> Notifier.Spec(id, Channels.EXPLORE, Groups.PLACES, route = Route.MAP,
            publicTitle = "Un loc nou pe hartă.", category = null)
        NudgeContext.SleepReport -> Notifier.Spec(id, Channels.SLEEP, Groups.SLEEP, route = Route.SLEEP,
            publicTitle = "Raportul nopții e gata.", category = null)
        NudgeContext.FocusDone -> Notifier.Spec(id, Channels.COACH, Groups.COACH, route = Route.FOCUS,
            publicTitle = "Postul de pază s-a încheiat.", category = null)
        NudgeContext.MealLog -> Notifier.Spec(id, Channels.COACH, Groups.COACH, route = Route.NUTRITION,
            publicTitle = "Mese găsite în poze.", category = null)
        else -> Notifier.Spec(id, Channels.COACH, Groups.COACH, route = Route.DASHBOARD)
    }

    /** Raportul nopții e gata (SleepUpload). Mereu „estimat”; pe ecranul de blocare doar „Raportul nopții e gata.” */
    suspend fun sleepReport(app: ForjaApp, view: SleepView) {
        if (!notifOn(app)) return
        val now = System.currentTimeMillis()
        val best = try {
            val nights = app.db.sleepDao().finishedSince(now - 7 * 24 * 3600_000L).first()
            nights.size >= 3 && nights.all { ((it.endAt ?: it.startAt) - it.startAt) / 60_000L <= view.minutes }
        } catch (_: Exception) { false }
        val d = NudgeSnapshot.basics(app, now).copy(sleep = view.copy(bestOfWeek = best))
        if (postEvent(app, NudgeContext.SleepReport, d, specFor(NudgeContext.SleepReport, NotifIds.SLEEP_REPORT), Channels.SLEEP)) {
            NudgeStore.markSyncEvent(app)
        }
    }

    /** Un loc nou (ExploreTracker): ai STAT acolo. Doar cu „Notificări” pornite; nu în orele de liniște. */
    fun newPlace(app: ForjaApp, p: PlaceEntity, revisit: Boolean = false) {
        app.appScope.launch {
            try {
                if (!togglesOn(app)) return@launch
                if (revisit && (p.name.isBlank() || p.visits !in REVISIT_MARKS)) return@launch
                val total = try { app.db.exploreDao().countPlacesOnce() } catch (_: Exception) { 0 }
                val view = PlaceView(p.name, (p.stayMs / 60_000L).toInt(), p.visits, total, revisit)
                val d = NudgeSnapshot.basics(app).copy(place = view)
                // Fără markSyncEvent: vestea a plecat deja aici, iar rândul permanent n-o mai repetă (NudgeRules.placeAnnounced).
                postEvent(app, NudgeContext.NewPlace, d, specFor(NudgeContext.NewPlace, NotifIds.place(p.id)), Channels.EXPLORE)
            } catch (_: Exception) { }
        }
    }

    private val REVISIT_MARKS = setOf(3, 5, 10, 25, 50, 100)

    /** Camarad nou din agendă (ContactsSync) — păstrează dezvăluirea „Sunteți prieteni și vă vedeți pe hartă.” */
    fun newFriend(app: ForjaApp, uid: String, name: String) {
        app.appScope.launch {
            try {
                if (!notifOn(app)) return@launch
                val d = NudgeSnapshot.clock().copy(friend = FriendView(uid, name, null, null))
                postEvent(app, NudgeContext.NewFriend, d, specFor(NudgeContext.NewFriend, NotifIds.newFriend(uid)), Channels.SOCIAL)
            } catch (_: Exception) { }
        }
    }

    /** O sesiune de Focus s-a încheiat singură (FocusMonitorService). */
    fun focusDone(app: ForjaApp, sessionMin: Int, newTrees: Int) {
        app.appScope.launch {
            try {
                if (!notifOn(app)) return@launch
                val forest = try { app.prefs.focusForest.first() } catch (_: Exception) { Triple(0, 0, 0) }
                val d = NudgeSnapshot.basics(app).copy(
                    focusSessionMin = sessionMin, focusSessionTrees = newTrees, treesToday = forest.first
                )
                postEvent(app, NudgeContext.FocusDone, d, specFor(NudgeContext.FocusDone, NotifIds.FOCUS_DONE), Channels.COACH)
            } catch (_: Exception) { }
        }
    }

    /** Mese găsite în pozele de azi (GalleryScan, strict la cerere). */
    suspend fun mealsFound(app: ForjaApp, count: Int) {
        if (!notifOn(app)) return
        val d = NudgeSnapshot.basics(app).copy(mealsFound = count)
        postEvent(app, NudgeContext.MealLog, d, specFor(NudgeContext.MealLog, NotifIds.MEALS_FOUND), Channels.COACH)
    }

    /** Culcarea (Bedtime, din alarmă): trece prin orele de liniște, doar cu „Amintește-mi de somn” și „Notificări” pornite. */
    suspend fun bedtime(app: ForjaApp, minutesToBedtime: Int) {
        if (!notifOn(app) || !Notifier.canPost(app, Channels.COACH)) return
        val now = System.currentTimeMillis()
        val state = NudgeStore.read(app)
        val data = NudgeSnapshot.read(app, now).copy(minutesToBedtime = minutesToBedtime)
        val r = Nudge.pick(NudgeContext.Bedtime, data, NudgeRules.recent(state, Channels.COACH)) ?: return
        val pub = Nudge.publicOf(NudgeContext.Bedtime, data)
        val spec = Notifier.Spec(
            NotifIds.BEDTIME, Channels.COACH, Groups.COACH, route = Route.SLEEP,
            publicTitle = pub?.title ?: "Noapte bună.", publicText = pub?.body
        )
        if (Notifier.post(app, r, spec)) NudgeStore.update(app) { NudgeRules.onPosted(it, r, Channels.COACH, now, NudgeSnapshot.dayOf(now)) }
    }

    /** Mesajul a fost atins și Casca a apărut pe Panou (ecoul). */
    fun onTapped(c: Context, ctx: String) {
        if (ctx.isNotBlank()) NudgeStore.update(c) { NudgeRules.onTapped(it, ctx) }
    }

    /** Swipe pe un mesaj: ignorat (auto-reglarea). */
    fun onDismissed(c: Context, ctx: String, id: String) {
        NudgeStore.update(c) { NudgeRules.onDismissed(it, ctx, id) }
    }
}

/** Categoriile NotificationCompat folosite (constantă locală, ca să nu importăm peste tot). */
internal object NotificationCategory {
    const val SOCIAL = androidx.core.app.NotificationCompat.CATEGORY_SOCIAL
}

/** Tura orară a Cascăi: culcarea re-armată, replica notificării permanente, apoi mesajele (dacă e cazul). */
class NudgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? ForjaApp ?: return Result.success()
        if (app.auth.currentUid == null) return Result.success()
        try { Bedtime.sync(app) } catch (_: Exception) { }
        try { SyncNotice.refresh(app) } catch (_: Exception) { }
        try { Nudges.tick(app) } catch (_: Exception) { }
        return Result.success()
    }
}
