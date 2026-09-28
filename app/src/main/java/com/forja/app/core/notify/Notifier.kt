package com.forja.app.core.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.forja.app.MainActivity
import com.forja.app.R

/**
 * Poștașul Cascăi: construiește și trimite o notificare dintr-un [Rendered], cu regulile comune:
 * pictograma mare (poza mascotei), grupul propriu, versiunea publică pentru ecranul de blocare (B5),
 * `setLocalOnly` pentru somn/prieteni/kcal (ceasul nu le oglindește), urmărirea swipe-ului (auto-reglare) și,
 * la atingere, ecranul potrivit + ecoul mascotei pe Panou (trucul Duo).
 */
internal object Notifier {
    const val EXTRA_ROUTE = "forja_route"          // = OrganizerJobs.ROUTE_EXTRA, citit de MainNav
    const val EXTRA_ID = "forja_nudge_id"
    const val EXTRA_CTX = "forja_nudge_ctx"
    const val EXTRA_POSE = "forja_nudge_pose"
    const val EXTRA_TITLE = "forja_nudge_title"
    const val EXTRA_BODY = "forja_nudge_body"
    const val EXTRA_AT = "forja_nudge_at"

    data class Spec(
        val id: Int,
        val channel: String,
        val group: String,
        /** Fila deschisă la atingere (Route.*), sau null = aplicația, unde era. */
        val route: String? = null,
        /** La atingere, Panoul arată mascota în aceeași poză, cu aceeași replică. */
        val echo: Boolean = false,
        /** Fără sunet și fără vibrație (orele de liniște, rezultatele a ce ai pornit tu). */
        val silent: Boolean = false,
        val publicTitle: String? = null,
        val publicText: String? = null,
        /** Swipe-ul se raportează (NudgeReceiver) — contează ca „ignorat”. */
        val trackDismiss: Boolean = false,
        val category: String? = NotificationCompat.CATEGORY_REMINDER,
        val smallIcon: Int = R.drawable.ic_notify,
        val content: PendingIntent? = null,
        val actions: List<Pair<String, PendingIntent>> = emptyList()
    )

    /** Permisiunea, comutatorul global și canalul (un canal oprit nu e „ignorat” — pur și simplu nu trimitem). */
    fun canPost(c: Context, channel: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return false
        val ch = try { nm.getNotificationChannel(channel) } catch (_: Exception) { null }
        return ch == null || ch.importance != NotificationManager.IMPORTANCE_NONE
    }

    @SuppressLint("MissingPermission")   // canPost() verifică POST_NOTIFICATIONS
    fun post(c: Context, r: Rendered, spec: Spec): Boolean {
        if (!canPost(c, spec.channel)) return false
        return try {
            NotificationManagerCompat.from(c).notify(spec.id, build(c, r, spec))
            true
        } catch (_: SecurityException) { false } catch (_: Exception) { false }
    }

    fun build(c: Context, r: Rendered, spec: Spec): Notification {
        val content = spec.content ?: openIntent(c, r, spec)
        val b = NotificationCompat.Builder(c, spec.channel)
            .setSmallIcon(spec.smallIcon)
            .setContentTitle(r.title)
            .setContentText(r.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.body))
            .setAutoCancel(true)
            .setGroup(spec.group)
            .setContentIntent(content)
            .setVisibility(if (r.private) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setLocalOnly(r.localOnly)
        spec.category?.let { b.setCategory(it) }
        if (spec.silent) b.setSilent(true)
        MascotIcons.bitmap(c, r.pose)?.let { b.setLargeIcon(it) }
        if (r.private) {
            val pub = NotificationCompat.Builder(c, spec.channel)
                .setSmallIcon(spec.smallIcon)
                .setContentTitle(spec.publicTitle ?: "FORJA")
                .setGroup(spec.group)
            spec.publicText?.let { pub.setContentText(it) }
            b.setPublicVersion(pub.build())
        }
        if (spec.trackDismiss) b.setDeleteIntent(NudgeReceiver.dismissIntent(c, spec.id, r.context.name, r.id))
        spec.actions.forEach { (label, pi) -> b.addAction(0, label, pi) }
        return b.build()
    }

    /** MainActivity pe fila cerută; pentru mesajele „coach”, cu replica și poza pentru ecoul de pe Panou. */
    fun openIntent(c: Context, r: Rendered?, spec: Spec): PendingIntent {
        val intent = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        spec.route?.let { intent.putExtra(EXTRA_ROUTE, it) }
        if (spec.echo && r != null) {
            intent.putExtra(EXTRA_ID, r.id)
                .putExtra(EXTRA_CTX, r.context.name)
                .putExtra(EXTRA_POSE, r.pose.name)
                .putExtra(EXTRA_TITLE, r.title)
                .putExtra(EXTRA_BODY, r.body)
                .putExtra(EXTRA_AT, System.currentTimeMillis())
        }
        return PendingIntent.getActivity(c, spec.id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun cancel(c: Context, id: Int) {
        try { NotificationManagerCompat.from(c).cancel(id) } catch (_: Exception) { }
    }
}
