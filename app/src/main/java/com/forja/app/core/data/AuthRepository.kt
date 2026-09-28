package com.forja.app.core.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import kotlin.math.absoluteValue

private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

data class ForjaUser(
    val uid: String,
    val name: String,
    /** Din contul Firebase (sursa adevărului), nu din users/{uid}: documentul acela îl citesc și prietenii. */
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
        val code = randomInviteCode()
        pendingName = name.trim()
        val user = ForjaUser(uid, name.trim(), email.trim(), code)
        // Profilul în Firestore — cu limită de timp: dacă baza de date nu e încă
        // disponibilă, NU blocăm intrarea în aplicație; loadProfile() îl creează
        // automat la prima conexiune reușită. Emailul NU intră în profil (îl văd prietenii), ci în settings/account.
        try {
            withTimeout(8000) {
                // Întâi codul: dacă e deja al altcuiva (refuzat), profilul nu primește un cod care duce la altul.
                db.collection("inviteCodes").document(code).set(mapOf("uid" to uid)).await()
                db.collection("users").document(uid).set(
                    mapOf(
                        "name" to user.name,
                        "inviteCode" to code,
                        "createdAt" to System.currentTimeMillis(),
                        "ghostUntil" to 0L,
                        "state" to "idle",
                        "weekKm" to 0.0
                    ),
                    SetOptions.merge()
                ).await()
                writeAccountAsync(uid, user.email)
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
                // Codul se afișează doar după ce îl citim din profil (cel vechi, calculat din uid, s-ar putea să nu mai fie valabil).
                synchronized(rotated) { rotated[uid] } ?: ""
            )
        }
    }

    private suspend fun loadOrCreateProfile(uid: String): ForjaUser {
        val email = auth.currentUser?.email ?: ""
        val snap = db.collection("users").document(uid).get().await()
        if (!snap.exists()) {
            // Profil lipsă (creat offline sau cont vechi) — îl creăm acum, fără email (acela stă în settings/account).
            val code = randomInviteCode()
            val name = pendingName ?: auth.currentUser?.email?.substringBefore('@') ?: "Sportiv"
            db.collection("inviteCodes").document(code).set(mapOf("uid" to uid)).await()
            db.collection("users").document(uid).set(
                mapOf(
                    "name" to name,
                    "inviteCode" to code, "createdAt" to System.currentTimeMillis(),
                    "ghostUntil" to 0L, "state" to "idle", "weekKm" to 0.0
                ), SetOptions.merge()
            ).await()
            writeAccountAsync(uid, email)
            return ForjaUser(uid, name, email, code)
        }
        // Cont de dinainte de 4.4: emailul mai stă în profil — îl mutăm acum (scrieri în cache, fără așteptare).
        if (snap.contains("email")) {
            try {
                writeAccountAsync(uid, email.ifBlank { snap.getString("email") ?: "" })
                db.collection("users").document(uid).update("email", FieldValue.delete())
            } catch (_: Exception) { }
        }
        val stored = snap.getString("inviteCode")
        return ForjaUser(
            uid,
            snap.getString("name") ?: "Sportiv",
            email,
            if (stored.isNullOrBlank() || stored == inviteCodeFor(uid)) rotateInviteCode(uid, stored) else stored
        )
    }

    /** uid → codul nou dat în acest proces (o singură înlocuire, chiar dacă profilul se citește din mai multe ecrane). */
    private val rotated = HashMap<String, String>()

    /**
     * Codurile de dinainte de 4.4 se calculau din uid, deci oricine afla uid-ul îți știa și codul — iar regulile din 4.4
     * deschid profilul prietenilor făcuți cu codul din profil. Îl înlocuim o dată cu unul aleatoriu: documentul nou și
     * profilul într-un singur lot (un cod ocupat de altcineva e refuzat de reguli și reîncercăm data viitoare), apoi
     * ștergem codul vechi (regulile vechi refuză ștergerea; nu contează, prietenia se verifică după codul din profil).
     * Fără așteptare: cu net pleacă imediat, fără net rămâne în coada Firestore.
     */
    private fun rotateInviteCode(uid: String, old: String?): String {
        synchronized(rotated) {
            rotated[uid]?.let { return it }
            val code = randomInviteCode()
            rotated[uid] = code
            try {
                db.batch()
                    .set(db.collection("inviteCodes").document(code), mapOf("uid" to uid))
                    .set(db.collection("users").document(uid), mapOf("inviteCode" to code), SetOptions.merge())
                    .commit()
                    .addOnSuccessListener {
                        if (!old.isNullOrBlank() && old != code) db.collection("inviteCodes").document(old).delete()
                    }
                    .addOnFailureListener { synchronized(rotated) { if (rotated[uid] == code) rotated.remove(uid) } }
            } catch (_: Exception) {
                rotated.remove(uid)
            }
            return code
        }
    }

    /**
     * Mută emailul din users/{uid} (citit de prieteni) în users/{uid}/settings/account (doar tu), o singură dată pe cont.
     * Fără citire: scrie contul și șterge câmpul (ștergerea unui câmp lipsă nu face nimic). Întoarce true când serverul
     * a confirmat — sau când profilul nu există încă (atunci se creează direct fără email).
     */
    suspend fun moveEmailToAccount(uid: String): Boolean {
        val email = auth.currentUser?.takeIf { it.uid == uid }?.email.orEmpty()
        return try {
            withTimeout(15_000) {
                writeAccount(uid, email)
                try {
                    db.collection("users").document(uid).update("email", FieldValue.delete()).await()
                } catch (e: FirebaseFirestoreException) {
                    if (e.code != FirebaseFirestoreException.Code.NOT_FOUND) throw e
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** users/{uid}/settings/account { email } — doar proprietarul îl poate citi (regula subcolecțiilor). */
    private suspend fun writeAccount(uid: String, email: String) {
        if (email.isBlank()) return
        db.collection("users").document(uid).collection("settings").document("account")
            .set(mapOf("email" to email.trim()), SetOptions.merge()).await()
    }

    private fun writeAccountAsync(uid: String, email: String) {
        if (email.isBlank()) return
        db.collection("users").document(uid).collection("settings").document("account")
            .set(mapOf("email" to email.trim()), SetOptions.merge())
    }

    suspend fun updateName(name: String) {
        val uid = currentUid ?: return
        db.collection("users").document(uid).set(mapOf("name" to name), SetOptions.merge()).await()
    }

    /** „Am uitat parola”: Firebase trimite emailul de resetare la adresa dată. */
    suspend fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    fun logout() = auth.signOut()

    /** Cod de invitație nou (din 4.4): 6 caractere aleatorii, FORJA-XXXXXX. Nu se poate calcula din uid. */
    private fun randomInviteCode(): String {
        val rnd = SecureRandom()
        return String(CharArray(6) { CODE_ALPHABET[rnd.nextInt(CODE_ALPHABET.length)] })
    }

    /**
     * Codul de dinainte de 4.4, derivat din uid. Rămâne doar ca să recunoaștem codurile vechi și să le înlocuim
     * ([rotateInviteCode]); nu se mai dă nimănui.
     */
    private fun inviteCodeFor(uid: String): String {
        val alphabet = CODE_ALPHABET
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
