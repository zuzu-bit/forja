package com.forja.app.core.games

import android.content.Context
import com.forja.app.core.games.asalt.AsaltEngine
import com.forja.app.core.games.asalt.AsaltPhase
import com.forja.app.core.games.asalt.AsaltSave
import com.forja.app.core.games.zid.ZidConfig
import com.forja.app.core.games.zid.ZidEngine
import com.forja.app.core.games.zid.ZidPhase
import com.forja.app.core.games.zid.ZidSave

/**
 * Partida vie a fiecărui joc, în memorie: pastila de progres scoate ruta jocului din stivă (MainActivity.openInventory),
 * iar partida trebuie să supraviețuiască. Pe disc (GameStore, JSON) la pauză, la ieșire, la ON_STOP și la 15 s de joc;
 * un nivel terminat (câștigat sau pierdut) își șterge salvarea. O salvare ilizibilă se aruncă fără zgomot.
 * Un nivel doar ales (încă în Ready, neatins) nu e o partidă neterminată: nu se scrie pe disc și nu se reia.
 */
object GameSessions {
    @Volatile var zid: ZidEngine? = null
        private set
    @Volatile var asalt: AsaltEngine? = null
        private set

    /** Partida ZID neterminată: din memorie, altfel de pe disc; null dacă nu e niciuna. */
    suspend fun loadZid(context: Context, cfg: ZidConfig): ZidEngine? {
        zid?.let { if (it.phase.active) return if (it.resumable) it else null }
        val raw = GameStore.readSave(context, GameId.Zid) ?: return null
        val e = try { ZidEngine.restore(GameStore.json.decodeFromString(ZidSave.serializer(), raw), cfg) } catch (_: Exception) { null }
        if (e == null || !e.resumable) {
            GameStore.writeSave(context, GameId.Zid, null)
            return null
        }
        zid = e
        return e
    }

    suspend fun loadAsalt(context: Context): AsaltEngine? {
        asalt?.let { if (it.phase.active) return if (it.resumable) it else null }
        val raw = GameStore.readSave(context, GameId.Asalt) ?: return null
        val e = try { AsaltEngine.restore(GameStore.json.decodeFromString(AsaltSave.serializer(), raw)) } catch (_: Exception) { null }
        if (e == null || !e.resumable) {
            GameStore.writeSave(context, GameId.Asalt, null)
            return null
        }
        asalt = e
        return e
    }

    fun begin(e: ZidEngine) { zid = e }

    fun begin(e: AsaltEngine) { asalt = e }

    /**
     * Scrie partida pe disc (dacă mai e în joc), altfel șterge salvarea. Un nivel neînceput șterge doar salvarea de pe
     * disc: rămâne în memorie pentru ecranul deschis (prima atingere îl pornește și de atunci se salvează).
     */
    fun persist(context: Context, e: ZidEngine) {
        if (zid !== e) return
        when {
            e.resumable -> GameStore.writeSave(context, GameId.Zid, GameStore.json.encodeToString(ZidSave.serializer(), e.save()))
            e.phase.active -> GameStore.writeSave(context, GameId.Zid, null)
            else -> finish(context, GameId.Zid)
        }
    }

    fun persist(context: Context, e: AsaltEngine) {
        if (asalt !== e) return
        when {
            e.resumable -> GameStore.writeSave(context, GameId.Asalt, GameStore.json.encodeToString(AsaltSave.serializer(), e.save()))
            e.phase.active -> GameStore.writeSave(context, GameId.Asalt, null)
            else -> finish(context, GameId.Asalt)
        }
    }

    /** Nivelul s-a terminat sau a fost abandonat: nu mai e nimic de reluat. */
    fun finish(context: Context, game: GameId) {
        when (game) {
            GameId.Zid -> zid = null
            GameId.Asalt -> asalt = null
        }
        GameStore.writeSave(context, game, null)
    }

    internal fun forget() {
        zid = null
        asalt = null
    }
}

/** Nivelul a pornit (prima atingere) și nu s-a terminat: e o partidă de reluat. ZID e în Ready doar înainte de start. */
val ZidEngine.resumable: Boolean get() = phase.active && phase != ZidPhase.Ready

/** ASALT revine în Ready și după o viață pierdută; neînceput = Ready fără niciun pas de joc. */
val AsaltEngine.resumable: Boolean get() = phase.active && !(phase == AsaltPhase.Ready && elapsedSteps == 0L)
