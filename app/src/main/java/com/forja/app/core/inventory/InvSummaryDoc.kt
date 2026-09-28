package com.forja.app.core.inventory

/**
 * Rezumatul unei rulări Inventar pentru site: documentul `users/{uid}/inventory/{runId}` (DESIGN-4.4 §3.3).
 * Doar nume de dosare, numere, octeți și locul ales — niciun URI, nicio miniatură, niciun nume de fișier.
 * Fără Android aici: [toMap] e testat pe JVM.
 */
internal data class InvSummaryDoc(
    val id: String,
    val kind: String,                 // "photos" | "docs"
    val startedAt: Long,
    val finishedAt: Long,
    val appVersion: String,
    val scopeMode: String,            // "all" | "last" | "album" | "folder"
    val scopeN: Int?,
    val scopeLabel: String,
    val destLabel: String,            // „Galerie · FORJA”, „Organizate”
    val destPath: String,             // „PICTURES/FORJA”, „DOCUMENTS/ORGANIZATE”
    val folders: List<Folder>,
    val trashCount: Int,
    val trashBytes: Long,
    val moved: Int,
    val failed: Int,
    val freedBytes: Long?
) {
    data class Folder(val name: String, val count: Int, val bytes: Long)

    /** Harta scrisă în Firestore (camelCase, ms epoch); dosarele: cele mai mari primele, cel mult [MAX_FOLDERS]. */
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "kind" to kind,
        "startedAt" to startedAt,
        "finishedAt" to finishedAt,
        "appVersion" to appVersion,
        "scope" to mapOf("mode" to scopeMode, "n" to scopeN, "label" to scopeLabel.take(MAX_TEXT)),
        "dest" to mapOf("label" to destLabel.take(MAX_TEXT), "path" to destPath.take(MAX_PATH)),
        "folders" to folders
            .filter { it.count > 0 }
            .sortedWith(compareByDescending<Folder> { it.count }.thenByDescending { it.bytes }.thenBy { it.name })
            .take(MAX_FOLDERS)
            .map { mapOf("name" to it.name.take(MAX_TEXT), "count" to it.count, "bytes" to it.bytes) },
        "trash" to mapOf("count" to trashCount, "bytes" to trashBytes),
        "moved" to moved,
        "failed" to failed,
        "freedBytes" to freedBytes
    )

    companion object {
        const val MAX_FOLDERS = 60
        const val MAX_TEXT = 80
        const val MAX_PATH = 160

        /** „1 000” (spațiu neîntrerupt între mii), ca pe ecran. */
        fun count(n: Int): String {
            val s = n.coerceAtLeast(0).toString()
            val sb = StringBuilder()
            s.forEachIndexed { i, c ->
                if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
                sb.append(c)
            }
            return sb.toString()
        }
    }
}
