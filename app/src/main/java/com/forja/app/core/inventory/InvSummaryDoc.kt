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
    val freedBytes: Long?,
    // Mirror (pachetul C): starea rulării și motivele. Cheile apar în document doar când au o valoare.
    /** scanning · grouping · naming · ready · applying · done · failed */
    val state: String? = null,
    val progressDone: Int? = null,
    val progressTotal: Int? = null,
    /** Cine a dat numele dosarelor (modelul), cum îl ține planul. */
    val provider: String? = null,
    /** „De aruncat” pe motiv (duplicate, similar, blurry, tiny, old_screenshot, accidental, ai, temp, manual). */
    val trashByReason: Map<String, Int> = emptyMap(),
    /** Până când se recuperează pozele din coș (30 de zile de la aplicare); null la documente. */
    val trashExpiresAt: Long? = null,
    /** Mutările care n-au mers, pe motiv (MoveReason.code). */
    val failures: Map<String, Int> = emptyMap(),
    val error: String? = null,
    val updatedAt: Long? = null
) {
    /** `theme` = descrierea dosarului din analiză; `covers` = cheile coperților urcate (contract v4), cel mult 4. */
    data class Folder(val name: String, val count: Int, val bytes: Long, val theme: String? = null, val covers: List<String> = emptyList())

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
            .map { f ->
                buildMap<String, Any?> {
                    put("name", f.name.take(MAX_TEXT)); put("count", f.count); put("bytes", f.bytes)
                    f.theme?.takeIf { it.isNotBlank() }?.let { put("theme", it.take(MAX_THEME)) }
                    if (f.covers.isNotEmpty()) put("covers", f.covers.take(4))
                }
            },
        "trash" to buildMap<String, Any?> {
            put("count", trashCount); put("bytes", trashBytes)
            val reasons = trashByReason.filterValues { it > 0 }
            if (reasons.isNotEmpty()) put("byReason", reasons)
            trashExpiresAt?.let { put("expiresAt", it) }
        },
        "moved" to moved,
        "failed" to failed,
        "freedBytes" to freedBytes
    ) + buildMap {
        state?.let { put("state", it) }
        if (progressTotal != null && progressTotal > 0) put("progress", mapOf("done" to (progressDone ?: 0).coerceIn(0, progressTotal), "total" to progressTotal))
        provider?.takeIf { it.isNotBlank() }?.let { put("provider", it.take(40)) }
        failures.filterValues { it > 0 }.takeIf { it.isNotEmpty() }?.let { put("failures", it) }
        error?.takeIf { it.isNotBlank() }?.let { put("error", it.take(MAX_THEME)) }
        updatedAt?.let { put("updatedAt", it) }
    }

    companion object {
        const val MAX_FOLDERS = 60
        const val MAX_TEXT = 80
        const val MAX_PATH = 160
        const val MAX_THEME = 120

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
