package com.forja.app.core.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.forja.app.ForjaApp
import com.forja.app.R
import com.forja.app.core.sync.CollectionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Notificarea permanentă „Sincronizare în cont” (notifications-design.md §D.1, §F, blocantele B1–B5).
 *
 *  - Restrânsă (B1): titlul e replica caldă (≤ 28), dar rândul de sub el spune mereu ce urcă — „Urcă în cont:
 *    locație, aplicații, fotografii” — din categoriile care rulează acum; antetul: „Sincronizare activă”
 *    (sau „Microfon + sincronizare”); butonul „Oprește”.
 *  - Extinsă: replica (paragraful 1) + rândul onest (paragraful 2): ce urcă, „Oprești de aici. Revoci din Profil → Contract.”
 *  - Rotația (B2): doar din bucla serviciului, doar cât e în prim-plan și nu se oprește; la schimbarea ferestrei
 *    zilei (08/12/17/21), la trecerea de la rezervă la o replică personală și la un eveniment (≥ 3 h).
 *  - Swipe (B3): pe Android 14+ se poate da la o parte; nu o mai re-postăm până la următorul start al serviciului.
 *  - Ecranul de blocare (B5): versiunea publică păstrează ce urcă și antetul; replica personală rămâne privată.
 *  - Liniștită: canal LOW, fără sunet, fără oră, o singură alertă.
 */
object SyncNotice {
    private const val EVENT_GAP_MS = 3 * 3600_000L
    private const val RETRY_MS = 3600_000L

    /** Cheia ferestrei: data + fereastra (noaptea de după 00:00 aparține serii de dinainte). */
    fun slotKey(now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = Instant.ofEpochMilli(now).atZone(zone)
        val slot = NudgeBank.slot(z.hour)
        val date = if (slot == 0 && z.hour < 8) z.toLocalDate().minusDays(1) else z.toLocalDate()
        return "$date#$slot"
    }

    /** Pregătește replica pentru fereastra curentă (în fundal: la deschidere, în tura orară, în rotație). */
    internal suspend fun refresh(app: ForjaApp, now: Long = System.currentTimeMillis()): NudgeStore.SyncLine? {
        if (CollectionSettings.enabled(app).isEmpty()) return null
        val key = slotKey(now)
        val cached = NudgeStore.syncLine(app)
        // Replica ferestrei curente rămâne (nu sărim de la una la alta în aceeași fereastră); o rezervă se reîncearcă
        // cel mult o dată pe oră, un eveniment nou cere o replică nouă.
        if (cached != null && cached.slotKey == key && NudgeStore.syncEventAt(app) <= cached.at &&
            (cached.personal || now - cached.at < RETRY_MS)
        ) return cached
        val snapshot = withContext(Dispatchers.IO) { NudgeSnapshot.read(app, now) }
        val state = NudgeStore.read(app)
        // Locul nou a venit deja ca notificare pe „Locurile tale”: aceeași veste nu se repetă în rândul permanent.
        val data = if (NudgeRules.placeAnnounced(state, NudgeSnapshot.dayOf(now), NudgeSnapshot::dayOf)) {
            snapshot.copy(newPlaceToday = null)
        } else snapshot
        val r = Nudge.pick(NudgeContext.SyncOngoing, data, NudgeRules.recent(state, Channels.SYNC)) ?: return cached
        val personal = NudgeBank.sync.firstOrNull { it.id == r.id }?.reserve == false
        val line = NudgeStore.SyncLine(key, r.id, r.title, r.body, r.pose.name, now, personal)
        NudgeStore.setSyncLine(app, line)
        return line
    }

    /** Replica pentru construcția sincronă (startForeground): doar dacă e a ferestrei curente, altfel o rezervă. */
    private fun currentLine(c: Context, now: Long): Rendered? {
        val key = slotKey(now)
        val l = NudgeStore.syncLine(c)?.takeIf { it.slotKey == key }
        if (l != null) {
            val pose = NudgePose.entries.firstOrNull { it.name == l.pose } ?: NudgePose.Talking
            return Rendered(l.id, NudgeContext.SyncOngoing, l.title, l.body, pose, private = l.personal, localOnly = false)
        }
        // Fără replică pregătită: o rezervă fără date, aleasă după zi (sincron, fără disc lent).
        return Nudge.pick(NudgeBank.sync.filter { it.reserve }, NudgeSnapshot.clock(now), emptyList(), Nudge.daySeed(now))
    }

    /**
     * Notificarea completă. `fresh = true` când serviciul intră în prim-plan (un start nou): swipe-ul de dinainte
     * se uită, iar pauza impusă de Android dispare.
     */
    fun build(
        c: Context,
        configured: Set<String>,
        open: PendingIntent,
        stop: PendingIntent,
        fresh: Boolean
    ): Notification {
        val now = System.currentTimeMillis()
        if (fresh) {
            NudgeStore.setSyncDismissed(c, false)
            Notifier.cancel(c, NotifIds.SYNC_PAUSED)
        }
        val line = currentLine(c, now)
        val text = SyncCopy.compose(configured, line, CollectionSettings::label)
        val public = NotificationCompat.Builder(c, Channels.SYNC)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(text.publicTitle)
            .setContentText(text.publicText)
            .setSubText(text.subText)
            .setGroup(Groups.SYNC)
            .build()
        val b = NotificationCompat.Builder(c, Channels.SYNC)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(Ro.glue(text.title))
            .setContentText(text.collapsed)
            .setSubText(text.subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(Ro.glue(text.big)))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setGroup(Groups.SYNC)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setDeleteIntent(NudgeReceiver.syncDismissIntent(c))
            .addAction(0, text.action, stop)
        line?.let { l -> MascotIcons.bitmap(c, l.pose)?.let { b.setLargeIcon(it) } }
        val personal = line != null && NudgeBank.sync.firstOrNull { it.id == line.id }?.reserve == false
        NudgeStore.setSyncPosted(c, NudgeStore.SyncPosted(slotKey(now), line?.id ?: "", now, personal))
        return b.build()
    }

    /**
     * Rotația, apelată din bucla serviciului (o singură linie acolo). `live()` = „în prim-plan și nu se oprește”,
     * reverificat pe firul serviciului chiar înainte de notify (blocanta B2: nicio notificare „activă” după oprire).
     */
    @SuppressLint("MissingPermission")
    suspend fun rotate(c: Context, id: Int, live: () -> Boolean, build: () -> Notification) {
        if (!live() || NudgeStore.syncDismissed(c)) return
        val now = System.currentTimeMillis()
        val posted = NudgeStore.syncPosted(c)
        val key = slotKey(now)
        val event = NudgeStore.syncEventAt(c)
        val cached = NudgeStore.syncLine(c)
        val due = posted == null || posted.slotKey != key ||
            (event > posted.at && now - posted.at >= EVENT_GAP_MS) ||
            // de la rezervă la o replică personală: când e gata una (sau o dată pe oră, să vedem dacă a apărut)
            (!posted.personal && (cached?.let { it.slotKey == key && it.personal && it.id != posted.id } == true || now - posted.at >= RETRY_MS))
        if (!due) return
        val app = c.applicationContext as? ForjaApp ?: return
        val line = try { refresh(app, now) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        // Aceeași replică (sau tot o rezervă), deja afișată: nimic de rescris — doar ținem minte că am verificat.
        if (posted != null && line != null && posted.slotKey == key && event <= posted.at &&
            (posted.id == line.id || (!posted.personal && !line.personal))
        ) {
            NudgeStore.setSyncPosted(c, posted.copy(at = now))
            return
        }
        if (!live() || NudgeStore.syncDismissed(c)) return
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(c).notify(id, build())
            NudgeStore.update(c) { s ->
                val r = line ?: return@update s
                NudgeRules.onPosted(s, Rendered(r.id, NudgeContext.SyncOngoing, r.title, r.body, NudgePose.Talking, true, false),
                    Channels.SYNC, now, NudgeSnapshot.dayOf(now))
            }
        } catch (_: SecurityException) { } catch (_: Exception) { }
    }

    /**
     * Android a oprit sincronizarea după bugetul de 6 h (onTimeout) — nu după un „Oprește” dat de tine. Un singur
     * mesaj, liniștit, pe canalul „sync”; dispare la următorul start. `live()` = serviciul e tot în prim-plan (de ex.
     * a repornit fără dataSync și merge mai departe): atunci „a luat pauză” ar minți, deci nu se trimite nimic.
     */
    fun paused(c: Context, live: () -> Boolean = { false }) {
        if (live()) return
        val clock = NudgeSnapshot.clock()
        val r = Nudge.render(SyncCopy.paused, clock) ?: return
        val spec = Notifier.Spec(
            id = NotifIds.SYNC_PAUSED, channel = Channels.SYNC, group = Groups.SYNC, silent = true,
            content = PendingIntent.getActivity(
                c, NotifIds.SYNC_PAUSED, CollectionSettings.settings(c),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ),
            category = NotificationCompat.CATEGORY_STATUS
        )
        Notifier.post(c, r, spec)
    }
}
