package com.forja.app.feature.cleanup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates the explicit "Activează sincronizarea" action for this account.
 * Android permissions are inputs, not consent. Calling status never activates a grant.
 * Location / usage choices are committed separately by the preserved activity controller.
 */
object SyncSetup {
    enum class State { ACTIVE, QUEUED, NEEDS_SETUP, ERROR }
    data class Scope(val key: String, val title: String, val state: State, val detail: String)
    data class Result(val scopes: List<Scope>, val accountChanged: Boolean = false) {
        val neededSetup: List<Scope> get() = scopes.filter { it.state == State.NEEDS_SETUP || it.state == State.ERROR }
    }

    private val activation = Mutex()
    private const val AUDIO_BRIDGE = "com.forja.app.feature.research.SleepAudioState"

    private fun granted(c: Context, permission: String) = ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED
    private fun photos(c: Context) = if (Build.VERSION.SDK_INT >= 33) {
        granted(c, Manifest.permission.READ_MEDIA_IMAGES) || (Build.VERSION.SDK_INT >= 34 && granted(c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    } else granted(c, Manifest.permission.READ_EXTERNAL_STORAGE)
    private fun partialPhotos(c: Context) = Build.VERSION.SDK_INT >= 34 && !granted(c, Manifest.permission.READ_MEDIA_IMAGES) && granted(c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    private fun notifications(c: Context) = c.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS))
    private fun audioPermission(c: Context) = granted(c, Manifest.permission.RECORD_AUDIO) && notifications(c) &&
        c.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel("timed_recording")?.importance != android.app.NotificationManager.IMPORTANCE_NONE

    /** A nonempty saved string is not proof that Android still grants this folder. */
    fun readableTree(c: Context, uri: Uri?): Boolean = uri != null && uri.scheme == "content" && DocumentsContract.isTreeUri(uri) &&
        runCatching { c.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } }.getOrDefault(false)

    private fun savedTree(c: Context, owner: String): String {
        val p = FileSync.prefs(c)
        if (p.getString("owner", null) != owner) return ""
        val tree = p.getString("tree", "").orEmpty()
        return tree.takeIf { it.isNotBlank() && readableTree(c, Uri.parse(it)) }.orEmpty()
    }

    private fun audio(c: Context, name: String): Boolean = Class.forName(AUDIO_BRIDGE)
        .getMethod(name, Context::class.java).invoke(null, c) as Boolean
    private fun audioReady() = runCatching {
        Class.forName("com.forja.app.feature.research.TimedRecordingService").getMethod("isReady").invoke(null) as Boolean
    }.getOrDefault(false)
    private fun organizerSourcesAvailable(c: Context): Boolean {
        if (!CleanupAuto.enabled(c)) return false
        val p = CleanupAuto.prefs(c)
        val gallery = p.getBoolean("photos", false)
        val folder = p.getBoolean("files", false)
        val tree = p.getString("tree", "").orEmpty()
        return (gallery || folder) && (!gallery || photos(c)) &&
            (!folder || tree.isNotBlank() && readableTree(c, Uri.parse(tree)))
    }

    /** No network I/O or preference writes; safe to use for screen rendering. */
    fun status(context: Context): List<Scope> {
        val c = context.applicationContext
        val owner = FileSync.owner() ?: return listOf(Scope("account", "Contul tău", State.NEEDS_SETUP, "Conectează-te în FORJA."))
        val file = FileSync.prefs(c)
        val fileOn = file.getBoolean("enabled", false) && file.getString("owner", null) == owner
        val galleryOn = fileOn && file.getBoolean("photos", false) && photos(c)
        val tree = savedTree(c, owner)
        val audioAuthorized = runCatching { audio(c, "authorized") }.getOrDefault(false)
        val contactOn = ContactSync.enabled(c)
        val recovery = LostPhoneRecovery.device(c)
        return listOf(
            Scope("journals", "Somn, mișcare și mese", State.ACTIVE, "Jurnalele salvate se trimit în cont."),
            Scope("gallery", "Fotografii", if (galleryOn) State.QUEUED else State.NEEDS_SETUP,
                when { !photos(c) -> "Permite fotografiile în Android."; galleryOn && partialPhotos(c) -> "Selecția ta · copii online 24 h."; galleryOn -> "Galeria autorizată · copii online 24 h."; else -> "Pregătite pentru activare." }),
            Scope("folder", "Fișiere", if (fileOn && tree.isNotBlank()) State.QUEUED else State.NEEDS_SETUP,
                if (fileOn && tree.isNotBlank()) "Dosarul ales · copii online 24 h." else "Alege un dosar."),
            Scope("sleep", "Somn din aplicație și site", when { !audioPermission(c) -> State.NEEDS_SETUP; audioAuthorized && audioReady() -> State.ACTIVE; audioAuthorized -> State.QUEUED; else -> State.NEEDS_SETUP },
                when { !audioPermission(c) -> "Permite microfonul și notificările."; audioAuthorized && audioReady() -> "Pregătit. Înregistrarea pornește la comanda ta."; audioAuthorized -> "Așteaptă reconectarea telefonului."; else -> "Pregătit pentru activare." }),
            Scope("contacts", "Prieteni din contacte", if (contactOn && ContactSync.allowed(c)) State.QUEUED else State.NEEDS_SETUP,
                if (contactOn && ContactSync.allowed(c)) "Sincronizarea autorizată rămâne zilnică." else "Configurează numărul și găsirea prietenilor."),
            Scope("organizer", "Organizare din site", if (organizerSourcesAvailable(c)) State.QUEUED else State.NEEDS_SETUP,
                when { organizerSourcesAvailable(c) -> "Sursele și aprobările tale sunt păstrate."; CleanupAuto.enabled(c) -> "Verifică accesul la sursele autorizate."; else -> "Alege separat sursele și aprobările." }),
            Scope("recovery", "Găsire telefon", when { recovery == null || !LostPhoneRecovery.exactPermission(c) || !LostPhoneRecovery.notices(c) -> State.NEEDS_SETUP; LostPhoneService.running -> State.ACTIVE; else -> State.QUEUED },
                when { recovery == null -> "Activează găsirea propriului telefon."; !LostPhoneRecovery.exactPermission(c) || !LostPhoneRecovery.notices(c) -> "Permite locația precisă și notificările."; LostPhoneService.running -> "Pregătit pentru cererile tale."; else -> "Așteaptă reconectarea telefonului." })
        )
    }

    fun neededSetup(context: Context): List<Scope> = status(context).filter { it.state == State.NEEDS_SETUP || it.state == State.ERROR }

    /**
     * Call only from the visible, explicitly confirmed setup action. Never records audio,
     * creates social/location sessions or makes a number discoverable. The action explicitly
     * authorizes sleep upload and its AI report through the shared sleep adapter.
     * WorkManager activation is reported as queued, not as a completed upload.
     */
    suspend fun activate(context: Context, selectedTreeUri: Uri? = null): Result = activation.withLock {
        val c = context.applicationContext
        val owner = FileSync.owner() ?: return@withLock Result(status(c))
        fun sameAccount() { check(FileSync.owner() == owner) { "Contul s-a schimbat." } }
        val errors = linkedMapOf<String, String>()
        val file = FileSync.prefs(c)
        val chosenTree = when {
            selectedTreeUri != null && readableTree(c, selectedTreeUri) -> selectedTreeUri.toString()
            selectedTreeUri != null -> { errors["folder"] = "Dosarul nu mai este accesibil. Alege-l din nou."; savedTree(c, owner) }
            else -> savedTree(c, owner)
        }
        val wantedPhotos = photos(c)
        val sameFileOwner = file.getString("owner", null) == owner
        val previous = FilePlan(file.getBoolean("enabled", false) && sameFileOwner,
            sameFileOwner && file.getBoolean("photos", false), savedTree(c, owner),
            if (sameFileOwner) file.getInt("limit", 50) else 0, if (sameFileOwner) file.getBoolean("wifi", true) else true)
        val steps = mutableListOf<SyncSetupPolicy.Step>()
        if (wantedPhotos || chosenTree.isNotBlank()) steps += SyncSetupPolicy.Step("files") {
            sameAccount()
            val unchanged = previous.enabled && previous.photos == wantedPhotos && previous.tree == chosenTree
            try {
                if (unchanged) FileSync.schedule(c, true)
                else FileSync.activate(c, wantedPhotos, chosenTree, previous.limit, previous.wifi)
                sameAccount()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Restore only this account's previously active source selection; never touch
                // a new account's state and never roll back unrelated successfully enabled scopes.
                if (FileSync.owner() == owner && file.getString("owner", null) == owner) {
                    if (previous.enabled && (previous.photos && photos(c) || previous.tree.isNotBlank())) {
                        runCatching { FileSync.activate(c, previous.photos && photos(c), previous.tree, previous.limit, previous.wifi) }
                    } else FileSync.stop(c)
                }
                throw e
            }
        }
        if (audioPermission(c)) steps += SyncSetupPolicy.Step("sleep") {
            withContext(Dispatchers.Main.immediate) {
                sameAccount()
                check(audio(context, "authorizeRemoteSleep")) { "Deschide FORJA pentru a pregăti somnul." }
                sameAccount()
            }
        }
        if (ContactSync.enabled(c) && ContactSync.allowed(c)) steps += SyncSetupPolicy.Step("contacts") {
            sameAccount(); ContactSync.schedule(c); sameAccount()
        }
        if (organizerSourcesAvailable(c)) steps += SyncSetupPolicy.Step("organizer") {
            sameAccount(); CleanupAuto.schedule(c, true); sameAccount()
        }
        if (LostPhoneRecovery.device(c) != null) steps += SyncSetupPolicy.Step("recovery") {
            withContext(Dispatchers.Main.immediate) { sameAccount(); LostPhoneRecovery.resume(c); sameAccount() }
        }
        val outcome = SyncSetupPolicy.run(owner, FileSync::owner, steps)
        if (outcome.accountChanged) return@withLock Result(status(c), accountChanged = true)
        errors.putAll(outcome.failed.associateWith { "Activarea nu a fost confirmată. Încearcă din nou." })
        val scopes = status(c).map { scope ->
            val error = errors[scope.key] ?: if (scope.key in setOf("gallery", "folder")) errors["files"] else null
            if (error == null) scope else scope.copy(state = State.ERROR, detail = error)
        }
        Result(scopes)
    }

    private data class FilePlan(val enabled: Boolean, val photos: Boolean, val tree: String, val limit: Int, val wifi: Boolean)
}

/** Small policy boundary exercised without Android: partial failures and account changes. */
internal object SyncSetupPolicy {
    data class Step(val key: String, val activate: suspend () -> Unit)
    data class Outcome(val failed: List<String>, val accountChanged: Boolean)
    suspend fun run(owner: String, currentOwner: () -> String?, steps: List<Step>): Outcome {
        val failed = mutableListOf<String>()
        for (step in steps) {
            currentCoroutineContext().ensureActive()
            if (currentOwner() != owner) return Outcome(failed, true)
            try { step.activate() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed += step.key }
            if (currentOwner() != owner) return Outcome(failed, true)
        }
        return Outcome(failed, currentOwner() != owner)
    }
}
