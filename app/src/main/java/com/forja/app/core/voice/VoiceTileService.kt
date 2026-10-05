package com.forja.app.core.voice

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.forja.app.MainActivity

/**
 * Butonul „FORJA Voce" din panoul de setări rapide: o atingere (sau TalkBack) de oriunde,
 * chiar cu telefonul blocat, și FORJA ascultă comanda.
 */
class VoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = Tile.STATE_ACTIVE
            it.label = "FORJA Voce"
            if (Build.VERSION.SDK_INT >= 29) it.subtitle = "Hei FORJA"
            it.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Cu serviciul de microfon pornit: închidem panoul printr-o activitate transparentă și ascultăm în aplicația din față.
        val intent = if (VoiceWakeService.running && !isLocked)
            Intent(this, VoiceListenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_VOICE_LISTEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val launch = Runnable {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(
                        PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
            } catch (_: Exception) { }
        }
        if (isLocked) unlockAndRun(launch) else launch.run()
    }
}
