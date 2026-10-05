package com.forja.app.core.voice

import android.app.Activity
import android.os.Bundle
import com.forja.app.ForjaApp

/**
 * Activitate transparentă, de o clipă: „Ascultă acum” din notificare și tile-ul „FORJA Voce” o pornesc ca să
 * se închidă panoul de notificări / setări rapide, apoi FORJA ascultă pe loc — în aplicația care rămâne pe ecran.
 * (Fără ea, panoul ar rămâne deschis și comenzile pe ecran ar nimeri în bara de sistem.)
 */
class VoiceListenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { ForjaApp.from(this).voice.listen() } catch (_: Exception) { }
        finish()
        overridePendingTransition(0, 0)
    }
}
