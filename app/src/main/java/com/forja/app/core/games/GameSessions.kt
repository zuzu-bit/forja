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
 */
object GameSessions {
    @Volatile var zid: ZidEngine? = null
        private set
    @Volatile var asalt: AsaltEngine? = null
        private set

    /** Partida ZID neterminată: din memorie, altfel de pe disc; null dacă nu e niciuna. */
    suspend fun loadZid(context: Context, cfg: ZidConfig): ZidEngine? {
        zid?.let { if (it.phase.active) return it }
        val raw = GameStore.readSave(context, GameId.Zid) ?: return null
        val e = try { ZidEngine.restore(GameStore.json.decodeFromString(ZidSave.serializer(), raw), cfg) } catch (_: Exception) { null }
        if (e == null || !e.phase.active) {
            GameStore.writeSave(context, GameId.Zid, null)
            return null
        }
        zid = e
        return e
    }

    suspend fun loadAsalt(context: Context): AsaltEngine? {
        asalt?.let { if (it.phase.active) return it }
        val raw = GameStore.readSave(context, GameId.Asalt) ?: return null
        val e = try { AsaltEngine.restore(GameStore.json.decodeFromString(AsaltSave.serializer(), raw)) } catch (_: Exception) { null }
        if (e == null || !e.phase.active) {
            GameStore.writeSave(context, GameId.Asalt, null)
            return null
        }
        asalt = e
        return e
    }

    fun begin(e: ZidEngine) { zid = e }

    fun begin(e: AsaltEngine) { asalt = e }

    /** Scrie partida pe disc (dacă mai e în joc), altfel șterge salvarea. */
    fun persist(context: Context, e: ZidEngine) {
        if (zid !== e) return
        if (e.phase.active) {
            GameStore.writeSave(context, GameId.Zid, GameStore.json.encodeToString(ZidSave.serializer(), e.save()))
        } else {
            finish(context, GameId.Zid)
        }
    }

    fun persist(context: Context, e: AsaltEngine) {
        if (asalt !== e) return
        if (e.phase.active) {
            GameStore.writeSave(context, GameId.Asalt, GameStore.json.encodeToString(AsaltSave.serializer(), e.save()))
        } else {
            finish(context, GameId.Asalt)
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
