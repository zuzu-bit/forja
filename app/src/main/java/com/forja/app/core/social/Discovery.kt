package com.forja.app.core.social

import com.forja.app.ForjaApp
import com.forja.app.core.network.InsightsApi
import com.forja.app.core.network.InsightsFailure
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * „Pot fi găsit după număr”: înregistrează pe site amprenta numărului MEU (HMAC, nu numărul) — două niveluri:
 * verificat (claim `phone_number` în token, când Phone Auth e activ în consolă) sau declarat (antetul
 * `x-forja-phone-declared`, pe care gateway-ul îl lasă să treacă doar sub acest nume). Serverul preferă verificatul.
 */
object Discovery {
    const val PATH = "/v2/social/contacts/discovery"
    const val HEADER_DECLARED = "x-forja-phone-declared"

    /** Numărul verificat prin SMS al contului curent (din Firebase), dacă există. */
    fun verifiedPhone(): String? = try {
        FirebaseAuth.getInstance().currentUser?.phoneNumber?.takeIf { PhoneNumbers.isValid(it) }
    } catch (_: Exception) { null }

    /** Antetele pentru cererile de contacte: numărul declarat (gateway-ul îl validează și îl redenumește dacă e cazul). */
    suspend fun headers(app: ForjaApp): Map<String, String> {
        val declared = app.prefs.phoneDeclared.first()
        return if (PhoneNumbers.isValid(declared)) mapOf(HEADER_DECLARED to declared) else emptyMap()
    }

    /**
     * Înregistrează numărul (declarat sau verificat). Serverul cere un token emis în ultimele 5 minute,
     * așa că îl reîmprospătăm înainte. Întoarce `until` (expirarea listării, 30 de zile, reînnoită la fiecare sincronizare).
     */
    suspend fun register(app: ForjaApp): Result<Long> = try {
        val headers = headers(app)
        if (headers.isEmpty() && verifiedPhone() == null) throw InsightsFailure(400, "Scrie întâi numărul tău.")
        try { FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await() } catch (_: Exception) { }
        val resp = InsightsApi.json(PATH, buildJsonObject { put("consent", true) }, headers = headers)
        Result.success(resp["until"]?.jsonPrimitive?.longOrNull ?: 0L)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Șterge listarea și amprentele agendei mele de pe site. Nu aruncă: fără net, serverul le uită singur după 30 de zile. */
    suspend fun unregister(app: ForjaApp): Boolean = try {
        InsightsApi.json(PATH, null, method = "DELETE", headers = headers(app))
        true
    } catch (_: Exception) { false }

    /** Mesaj onest pentru utilizator: al serverului când există, altfel unul general. */
    fun humanError(e: Throwable): String = when (e) {
        is InsightsFailure -> e.message ?: "Serverul FORJA nu a răspuns."
        else -> "Fără conexiune sau serverul nu răspunde. Încearcă din nou."
    }
}
