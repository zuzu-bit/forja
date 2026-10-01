package com.forja.app.core.voice.ui

import com.forja.app.core.voice.VoiceIntent

/** An immutable, bounded observation. Paths are resolved again immediately before every action. */
data class UiNode(
    val id: String,
    val text: String = "",
    val description: String = "",
    val resourceId: String = "",
    val className: String = "",
    val parentId: String? = null,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val actions: Set<UiAction> = emptySet()
) {
    val label: String get() = text.ifBlank { description }
    fun sameTarget(other: UiNode): Boolean = id == other.id && text == other.text &&
        description == other.description && resourceId == other.resourceId && className == other.className &&
        clickable == other.clickable && editable == other.editable && enabled == other.enabled &&
        visible == other.visible && password == other.password
}

data class UiSnapshot(
    val packageName: String,
    val windowId: Int,
    val nodes: List<UiNode>,
    val locked: Boolean = false,
    val blockingWindow: Boolean = false,
    val windowClass: String = ""
)

enum class UiAction { CLICK, SET_TEXT, IME_ENTER }

interface UiDriver {
    suspend fun launch(packageName: String): Boolean
    suspend fun snapshot(): UiSnapshot?
    /** Must reject stale snapshots and reapply UiSafety immediately before acting. */
    suspend fun perform(snapshot: UiSnapshot, node: UiNode, action: UiAction, text: String? = null): Boolean
    suspend fun pause(milliseconds: Long)
    fun elapsedRealtime(): Long
}

class UiControlException(val reason: String, val stage: String) : Exception(reason)

object UiSafety {
    private val sensitiveButtons = setOf(
        "sign in", "log in", "login", "connecteaza te", "conecteaza te", "conectare", "autentificare",
        "allow", "deny", "permite", "refuza", "purchase", "buy", "pay", "plateste", "cumpara",
        "confirm purchase", "confirm payment", "confirma plata", "subscribe to premium", "get premium",
        "try premium", "buy or rent", "cumpara sau inchiriaza"
    )
    private val sensitiveFields = setOf("email or phone", "email", "password", "parola", "card number", "numar card", "cvv", "cvc")
    private val sensitiveFieldIds = setOf("email", "identifierid", "password", "card_number", "cvv", "cvc")

    fun requireSafe(snapshot: UiSnapshot, packageName: String, stage: String) {
        when {
            snapshot.locked -> throw UiControlException("Telefonul este blocat. Deblochează-l folosind Android.", stage)
            snapshot.packageName != packageName -> throw UiControlException("Aplicația activă s-a schimbat; comanda a fost oprită.", stage)
            snapshot.blockingWindow || snapshot.windowClass.contains("dialog", true) ->
                throw UiControlException("A apărut un dialog. FORJA nu confirmă permisiuni sau dialoguri sensibile.", stage)
            snapshot.nodes.any { it.visible && it.password } ->
                throw UiControlException("A apărut un câmp de parolă; autentificarea rămâne în controlul utilizatorului.", stage)
            snapshot.nodes.any { it.visible && it.editable &&
                (semantic(it.description) in sensitiveFields || it.resourceId.substringAfterLast('/').lowercase() in sensitiveFieldIds) } ->
                throw UiControlException("A apărut un formular de autentificare sau plată; comanda a fost oprită.", stage)
        }
    }

    fun requireActionSafe(node: UiNode, stage: String) {
        if (node.password || semantic(node.label) in sensitiveButtons)
            throw UiControlException("FORJA nu execută autentificări, permisiuni sau confirmări sensibile.", stage)
    }
}

data class VideoTarget(val node: UiNode, val title: String)

interface AppAdapter {
    val packageName: String
    val displayName: String get() = packageName
    fun accepts(intent: VoiceIntent): Boolean = intent.query.isNotBlank() && intent.query.length <= 250 && intent.query.none { it.isISOControl() }
    /** New benign actions provide a new plan; the engine's guards, polling and driver stay unchanged. */
    fun plan(intent: VoiceIntent): UiPlan = SearchAndOpenPlan(this)
    fun searchButton(snapshot: UiSnapshot): UiNode?
    fun searchField(snapshot: UiSnapshot): UiNode?
    fun submitButton(snapshot: UiSnapshot): UiNode?
    fun result(snapshot: UiSnapshot, intent: VoiceIntent): VideoTarget?
    fun verified(snapshot: UiSnapshot, target: VideoTarget): Boolean
}

interface UiPlan {
    suspend fun execute(context: UiExecutionContext, intent: VoiceIntent)
}
