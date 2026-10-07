package com.forja.app.core.voice

import java.text.Normalizer
import java.util.Locale

/** The first supported, explicitly requested action. Adapters can add other benign intents later. */
data class VoiceIntent(val query: String, val packageName: String = YOUTUBE_PACKAGE) {
    companion object { const val YOUTUBE_PACKAGE = "com.google.android.youtube" }
}

object VoiceIntentResolver {
    private val prefix = Regex("^(?:forja[\\s,.!:;-]*)?(?:te rog[\\s,.!:;-]*)?", RegexOption.IGNORE_CASE)
    private val commands = listOf(
        Regex("^(?:deschide|pornește|porneste|open)\\s+(?:(?:aplicația|aplicatia)\\s+)?youtube\\s*(?:(?:și|si|and)\\s+|[,;]\\s*)(?:caută|cauta|search(?: for)?)\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:în|in|pe|on)\\s+youtube\\s+(?:caută|cauta|search(?: for)?)\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:caută|cauta|search(?: for)?)\\s+(.+?)\\s+(?:pe|în|in|on)\\s+youtube[.!]?$", RegexOption.IGNORE_CASE)
    )
    private val trailingAction = Regex("\\s+(?:și|si|and)\\s+(?:deschide|open)\\s+(?:rezultatul|the result|result)(?:\\s+(.+?))?[.!]?$", RegexOption.IGNORE_CASE)

    fun resolve(transcript: String): VoiceIntent? {
        val command = transcript.trim().replace(prefix, "")
        val raw = commands.firstNotNullOfOrNull { it.matchEntire(command)?.groupValues?.get(1) } ?: return null
        val downstream = trailingAction.find(raw)
        val query = cleanQuery(if (downstream == null) raw else raw.substring(0, downstream.range.first))
        val namedResult = downstream?.groupValues?.getOrNull(1)?.let(::cleanQuery).orEmpty()
        if (namedResult.isNotBlank() && normalizeVoiceText(namedResult) !in setOf("corespunzator", "respectiv") &&
            comparableTitle(namedResult) != comparableTitle(query)) return null
        if (query.length !in 2..250 || query.any { it.isISOControl() }) return null
        return VoiceIntent(query)
    }

    private fun cleanQuery(value: String): String = value.trim().trim('"', '„', '”', '“').trim().trimEnd('.', '!')
    private fun comparableTitle(value: String): String = normalizeVoiceText(value)
        .replace(Regex("^(?:documentarul|documentary|videoul|video|clipul|filmul)\\s+"), "")
        .replace(Regex("\\s+"), " ")

    fun isCancel(transcript: String): Boolean {
        val command = normalizeVoiceText(transcript).replace(Regex("^forja[\\s,.!:;-]*"), "").trim(' ', '.', '!', ',')
        return command in setOf("anuleaza", "opreste", "stop", "cancel", "anuleaza comanda", "opreste comanda", "stop command")
    }
}

internal fun normalizeVoiceText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).trim()
