package com.forja.app.core.social

import android.app.Activity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Verificarea prin SMS (opțională): leagă numărul de contul Firebase curent (`linkWithCredential`), ca tokenul să poarte
 * `phone_number` — de atunci serverul îl tratează ca verificat. Dacă Phone Auth nu e activat în consolă, spunem asta sec.
 */
object PhoneVerify {
    sealed class Step {
        /** Codul a plecat; păstrăm id-ul ca să confirmăm. */
        data class CodeSent(val verificationId: String) : Step()
        /** Android a citit SMS-ul singur (sau numărul era deja verificat pe telefon). */
        data class Completed(val credential: PhoneAuthCredential) : Step()
        data class Failed(val message: String) : Step()
    }

    /** Trimite SMS-ul. [onStep] vine pe firul principal. */
    fun start(activity: Activity, phone: String, onStep: (Step) -> Unit) {
        val auth = FirebaseAuth.getInstance()
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) { onStep(Step.Completed(credential)) }
            override fun onVerificationFailed(e: FirebaseException) { onStep(Step.Failed(humanError(e))) }
            override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) { onStep(Step.CodeSent(verificationId)) }
        }
        try {
            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(phone)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
        } catch (e: Exception) {
            onStep(Step.Failed(humanError(e)))
        }
    }

    /** Confirmă codul primit și leagă numărul de contul curent; apoi reîmprospătează tokenul (poartă `phone_number`). */
    suspend fun confirm(verificationId: String, code: String): Result<String> =
        link(PhoneAuthProvider.getCredential(verificationId, code.trim()))

    suspend fun link(credential: PhoneAuthCredential): Result<String> {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return Result.failure(IllegalStateException("Conectează-te în FORJA."))
        return try {
            val res = user.linkWithCredential(credential).await()
            try { res.user?.getIdToken(true)?.await() } catch (_: Exception) { }
            val phone = res.user?.phoneNumber ?: user.phoneNumber ?: ""
            Result.success(phone)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(humanError(e)))
        }
    }

    fun humanError(e: Throwable): String = when (e) {
        is FirebaseAuthUserCollisionException -> "Numărul e legat deja de alt cont FORJA."
        is FirebaseAuthInvalidCredentialsException -> "Cod sau număr greșit. Verifică și încearcă din nou."
        is FirebaseAuthException -> when (e.errorCode) {
            "ERROR_OPERATION_NOT_ALLOWED", "ERROR_MISSING_CLIENT_IDENTIFIER", "ERROR_APP_NOT_AUTHORIZED" ->
                "Verificarea prin SMS nu e activată încă în consolă; numărul declarat merge."
            "ERROR_TOO_MANY_REQUESTS" -> "Prea multe încercări. Așteaptă un minut."
            "ERROR_QUOTA_EXCEEDED" -> "Cota de SMS-uri e epuizată azi. Numărul declarat merge."
            else -> "Nu a mers. Cod: ${e.errorCode}. Numărul declarat merge."
        }
        else -> {
            val text = e.message.orEmpty()
            if (text.contains("OPERATION_NOT_ALLOWED", true) || text.contains("MISSING_CLIENT_IDENTIFIER", true) || text.contains("not authorized", true))
                "Verificarea prin SMS nu e activată încă în consolă; numărul declarat merge."
            else "Nu a mers. Verifică internetul. Numărul declarat merge."
        }
    }
}
