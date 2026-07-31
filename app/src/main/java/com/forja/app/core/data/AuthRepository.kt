package com.forja.app.core.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlin.math.absoluteValue

data class ForjaUser(
    val uid: String,
    val name: String,
    val email: String,
    val inviteCode: String
)

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    val currentUid: String? get() = auth.currentUser?.uid
    val isLoggedIn: Boolean get() = auth.currentUser != null

    /** Numele tastat la înregistrare — folosit dacă profilul se creează abia mai târziu. */
    private var pendingName: String? = null

    /** Mesaje de eroare oneste, în română: ce s-a întâmplat + ce urmează. */
    fun humanError(e: Throwable): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "Parola e prea scurtă — folosește minim 6 caractere."
        is FirebaseAuthInvalidCredentialsException -> "Email sau parolă greșite. Verifică și încearcă din nou."
        is FirebaseAuthInvalidUserException -> "Nu există cont cu acest email. Creează unul mai jos."
        is FirebaseAuthUserCollisionException -> "Există deja un cont cu acest email. Intră în cont."
        is FirebaseAuthException -> when (e.errorCode) {
            "ERROR_OPERATION_NOT_ALLOWED" -> "Serverul de conturi nu e activat încă. Deschide consola Firebase → Authentication → activează Email/Password."
            "ERROR_TOO_MANY_REQUESTS" -> "Prea multe încercări. Așteaptă un minut și încearcă din nou."
            else -> "Nu s-a putut. Cod: ${e.errorCode}. Verifică internetul și încearcă din nou."
        }
        else -> "Fără conexiune sau serverul nu răspunde. Verifică internetul și încearcă din nou."
    }

    suspend fun register(name: String, email: String, password: String): ForjaUser {
        val res = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val uid = res.user!!.uid
        val code = inviteCodeFor(uid)
        pendingName = name.trim()
        val user = ForjaUser(uid, name.trim(), email.trim(), code)
        // Profilul în Firestore — cu limită de timp: dacă baza de date nu e încă
        // disponibilă, NU blocăm intrarea în aplicație; loadProfile() îl creează
        // automat la prima conexiune reușită.
        try {
            withTimeout(8000) {
                db.collection("users").document(uid).set(
                    mapOf(
                        "name" to user.name,
                        "email" to user.email,
                        "inviteCode" to code,
                        "createdAt" to System.currentTimeMillis(),
                        "ghostUntil" to 0L,
                        "state" to "idle",
                        "weekKm" to 0.0
                    ),
                    SetOptions.merge()
                ).await()
                db.collection("inviteCodes").document(code).set(mapOf("uid" to uid)).await()
            }
        } catch (_: Exception) { /* se sincronizează mai târziu */ }
        return user
    }

    suspend fun login(email: String, password: String): String {
        val res = auth.signInWithEmailAndPassword(email.trim(), password).await()
        return res.user!!.uid
    }

    suspend fun loadProfile(): ForjaUser? {
        val uid = currentUid ?: return null
        return try {
            withTimeout(8000) { loadOrCreateProfile(uid) }
        } catch (_: Exception) {
            // Firestore indisponibil — profil local provizoriu, sincronizat la următoarea șansă.
            ForjaUser(
                uid,
                auth.currentUser?.email?.substringBefore('@') ?: "Sportiv",
                auth.currentUser?.email ?: "",
                inviteCodeFor(uid)
            )
        }
    }

    private suspend fun loadOrCreateProfile(uid: String): ForjaUser {
        val snap = db.collection("users").document(uid).get().await()
        if (!snap.exists()) {
            // Profil lipsă (creat offline sau cont vechi) — îl creăm acum.
            val code = inviteCodeFor(uid)
            val name = pendingName ?: auth.currentUser?.email?.substringBefore('@') ?: "Sportiv"
            db.collection("users").document(uid).set(
                mapOf(
                    "name" to name, "email" to (auth.currentUser?.email ?: ""),
                    "inviteCode" to code, "createdAt" to System.currentTimeMillis(),
                    "ghostUntil" to 0L, "state" to "idle", "weekKm" to 0.0
                ), SetOptions.merge()
            ).await()
            db.collection("inviteCodes").document(code).set(mapOf("uid" to uid)).await()
            return ForjaUser(uid, name, auth.currentUser?.email ?: "", code)
        }
        return ForjaUser(
            uid,
            snap.getString("name") ?: "Sportiv",
            snap.getString("email") ?: "",
            snap.getString("inviteCode") ?: inviteCodeFor(uid)
        )
    }

    suspend fun updateName(name: String) {
        val uid = currentUid ?: return
        db.collection("users").document(uid).set(mapOf("name" to name), SetOptions.merge()).await()
    }

    fun logout() = auth.signOut()

    /** Cod de invitație scurt, stabil, derivat din uid — FORJA-XXXXXX. */
    private fun inviteCodeFor(uid: String): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        var h = uid.hashCode().toLong().absoluteValue
        val sb = StringBuilder()
        repeat(6) {
            sb.append(alphabet[(h % alphabet.length).toInt()])
            h /= alphabet.length
            if (h == 0L) h = uid.length.toLong() + it + 7
        }
        return sb.toString()
    }
}
