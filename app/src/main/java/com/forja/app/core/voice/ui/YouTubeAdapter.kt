package com.forja.app.core.voice.ui

import com.forja.app.core.voice.VoiceIntent
import com.forja.app.core.voice.normalizeVoiceText

internal fun semantic(value: String): String = normalizeVoiceText(value)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

/** Semantic UI selectors: resource IDs supplement accessible labels rather than screen coordinates. */
class YouTubeAdapter : AppAdapter {
    override val packageName = VoiceIntent.YOUTUBE_PACKAGE
    override val displayName = "YouTube"
    private val searchLabels = setOf("search", "cauta", "cautare", "search youtube", "cauta pe youtube")
    private val searchIds = setOf("menu_search", "search_button", "search_icon")
    private val fieldIds = setOf("search_edit_text", "search_query", "search_field")
    private val titleIds = setOf("video_title", "video_title_text", "video_title_view", "video_title_text_view")
    private val watchContextIds = setOf("watch_title", "watch_metadata", "watch_metadata_container", "watch_fragment",
        "watch_next_recycler_view", "slim_video_metadata", "slim_video_metadata_renderer", "video_details")
    private val fullPlayerIds = setOf("player_view", "watch_player", "player_container")
    private val ignoredQueryWords = setOf("documentarul", "documentar", "documentary", "videoul", "video", "clipul", "filmul", "the", "un", "o")

    override fun searchButton(snapshot: UiSnapshot): UiNode? = unique(snapshot.nodes.mapNotNull { node ->
        if (node.visible && node.enabled && !node.editable &&
            (semantic(node.label) in searchLabels || shortId(node) in searchIds)) clickable(snapshot, node) else null
    })

    override fun searchField(snapshot: UiSnapshot): UiNode? = unique(snapshot.nodes.filter {
        it.visible && it.enabled && it.editable && !it.password &&
            (shortId(it) in fieldIds || semantic(it.description) in searchLabels || semantic(it.text) in searchLabels)
    })

    override fun submitButton(snapshot: UiSnapshot): UiNode? = unique(snapshot.nodes.mapNotNull { node ->
        if (node.visible && node.enabled && !node.editable &&
            (shortId(node) in setOf("search_submit", "search_go_btn") || semantic(node.label) in searchLabels))
            clickable(snapshot, node) else null
    })

    override fun result(snapshot: UiSnapshot, intent: VoiceIntent): VideoTarget? {
        // Suggestions share the query wording but are not playable search results.
        if (snapshot.nodes.any { it.visible && shortId(it) in setOf("suggestions_list", "search_suggestions") }) return null
        val candidates = snapshot.nodes.mapNotNull { node ->
            if (!node.visible || !node.enabled || node.editable) return@mapNotNull null
            val id = shortId(node)
            val title = title(node)
            val container = clickable(snapshot, node) ?: return@mapNotNull null
            val labels = semantic(container.description + " " + node.description)
            val videoEvidence = id in titleIds ||
                (id in setOf("title", "title_text") && hasVideoMetadata(snapshot, container)) ||
                (node.text.isBlank() && hasVideoDescription(node.description))
            if (!videoEvidence || title.isBlank() || sponsored(snapshot, container) || labels.contains("sponsored") || labels.contains("sponsorizat") ||
                labels.startsWith("channel ") || labels.startsWith("canal ") || id.contains("shorts")) return@mapNotNull null
            val score = score(title, intent.query)
            if (score < 0.85) return@mapNotNull null
            Triple(container, title, score)
        }.groupBy { it.first.id }.values.map { group -> group.maxBy { it.third } }.sortedByDescending { it.third }
        val best = candidates.firstOrNull() ?: return null
        if (candidates.size > 1 && best.third - candidates[1].third < 0.12) return null
        return VideoTarget(best.first, best.second)
    }

    override fun verified(snapshot: UiSnapshot, target: VideoTarget): Boolean {
        // A mini-player can already be present on search results. It is not evidence of navigation.
        if (snapshot.nodes.any { it.visible && shortId(it) in setOf("miniplayer", "mini_player", "miniplayer_view", "search_results", "search_results_list", "search_results_recycler_view") }) return false
        if (snapshot.nodes.none { it.visible && shortId(it) in fullPlayerIds } ||
            snapshot.nodes.none { it.visible && shortId(it) in watchContextIds }) return false
        val targetTitle = semantic(target.title)
        return snapshot.nodes.any { node ->
            node.visible && !node.editable && (shortId(node) in titleIds || shortId(node) in setOf("title", "title_text", "watch_title")) &&
                semantic(title(node)) == targetTitle && !insideOriginalCard(snapshot, node, target.node) &&
                (shortId(node) == "watch_title" || snapshot.nodes.any { it.visible && shortId(it) in watchContextIds && descendantOf(snapshot, node, it) })
        }
    }

    private fun insideOriginalCard(snapshot: UiSnapshot, node: UiNode, original: UiNode): Boolean {
        val card = snapshot.nodes.firstOrNull { it.id == original.id && original.sameTarget(it) } ?: return false
        return node.id == card.id || descendantOf(snapshot, node, card)
    }

    private fun sponsored(snapshot: UiSnapshot, card: UiNode): Boolean = snapshot.nodes.any { node ->
        node.visible && (node.id == card.id || descendantOf(snapshot, node, card)) &&
            (semantic(node.label) in setOf("ad", "advertisement", "sponsored", "sponsorizat", "publicitate") ||
                shortId(node) in setOf("ad_badge", "ad_label", "sponsored_label", "sponsored_badge"))
    }

    private fun hasVideoMetadata(snapshot: UiSnapshot, container: UiNode): Boolean = hasVideoDescription(container.description) ||
        snapshot.nodes.any { descendantOf(snapshot, it, container) && shortId(it) in setOf("duration", "video_duration", "thumbnail", "video_thumbnail") }

    private fun hasVideoDescription(description: String): Boolean = semantic(description).let {
        (it.contains("views") || it.contains("vizionari") || it.contains("vizualizari")) &&
            (it.contains("minute") || it.contains("second") || it.contains("secunde") || Regex("\\d+:\\d+").containsMatchIn(description))
    }

    private fun descendantOf(snapshot: UiSnapshot, node: UiNode, container: UiNode): Boolean {
        var id = node.parentId
        repeat(6) {
            if (id == container.id) return true
            id = snapshot.nodes.firstOrNull { it.id == id }?.parentId ?: return false
        }
        return false
    }

    private fun clickable(snapshot: UiSnapshot, node: UiNode): UiNode? {
        var current: UiNode? = node
        repeat(4) {
            val candidate = current ?: return null
            if (candidate.visible && candidate.enabled && candidate.clickable && !candidate.password && UiAction.CLICK in candidate.actions) return candidate
            current = snapshot.nodes.firstOrNull { it.id == candidate.parentId }
        }
        return null
    }

    private fun unique(nodes: List<UiNode>): UiNode? = nodes.distinctBy { it.id }.singleOrNull()
    private fun shortId(node: UiNode): String = node.resourceId.substringAfterLast('/').lowercase()
    private fun title(node: UiNode): String = node.text.ifBlank {
        val boundary = Regex("\\s*(?:•| - |, )\\s*(?=(?:\\d[\\d.,]*\\s*(?:[kmb]|million|milioane|thousand|mii)?\\s+(?:views|vizionări|vizionari|vizualizări|vizualizari|minutes?|minute|seconds?|secunde)|\\d+:\\d+|go to channel|mergi la canal))", RegexOption.IGNORE_CASE)
            .find(node.description)
        if (boundary != null) node.description.substring(0, boundary.range.first) else node.description
    }.trim()
    private fun score(title: String, query: String): Double {
        val requested = semantic(query).split(' ').filter { it.isNotBlank() && it !in ignoredQueryWords }
        if (requested.isEmpty()) return 0.0
        val actual = semantic(title)
        val words = actual.split(' ').toSet()
        val coverage = requested.count { it in words }.toDouble() / requested.size
        // Short requests require every meaningful word; no fuzzy first-result fallback.
        if (coverage < (if (requested.size <= 3) 1.0 else 0.85)) return 0.0
        val phrase = requested.joinToString(" ")
        return when {
            actual == phrase -> 1.2
            actual.contains(phrase) -> 1.0
            else -> coverage * 0.9
        }
    }
}
