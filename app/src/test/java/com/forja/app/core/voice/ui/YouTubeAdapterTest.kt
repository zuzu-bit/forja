package com.forja.app.core.voice.ui

import com.forja.app.core.voice.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAdapterTest {
    private val adapter = YouTubeAdapter()

    @Test
    fun `Romanian accessible label selects its actionable parent`() {
        val button = clickable("toolbar/search")
        val label = UiNode("toolbar/search/label", description = "Caută", parentId = button.id)

        assertEquals(button, adapter.searchButton(snapshot(button, label)))
    }

    @Test
    fun `stable resource id can identify search when a label is unavailable`() {
        val button = clickable("toolbar/search", resourceId = "com.google.android.youtube:id/menu_search")

        assertEquals(button, adapter.searchButton(snapshot(button)))
    }

    @Test
    fun `two different search controls are not selected arbitrarily`() {
        assertNull(adapter.searchButton(snapshot(
            clickable("search-1", description = "Search"),
            clickable("search-2", description = "Caută")
        )))
    }

    @Test
    fun `multiple accessible search labels for one button remain one target`() {
        val parent = clickable("search")

        assertEquals(parent, adapter.searchButton(snapshot(
            parent,
            UiNode("label-1", text = "Search", parentId = parent.id),
            UiNode("label-2", description = "Search YouTube", parentId = parent.id)
        )))
    }

    @Test
    fun `hidden disabled and nonactionable search controls are refused`() {
        listOf(
            clickable("search", description = "Search").copy(visible = false),
            clickable("search", description = "Search").copy(enabled = false),
            clickable("search", description = "Search").copy(actions = emptySet()),
            UiNode("search", text = "Search")
        ).forEach { assertNull(adapter.searchButton(snapshot(it))) }
    }

    @Test
    fun `query field must be uniquely identified editable and never a password`() {
        val field = UiNode("query", resourceId = "com.google.android.youtube:id/search_edit_text", editable = true)

        assertEquals(field, adapter.searchField(snapshot(field)))
        assertNull(adapter.searchField(snapshot(field.copy(password = true))))
        assertNull(adapter.searchField(snapshot(field.copy(editable = false))))
        assertNull(adapter.searchField(snapshot(field, field.copy(id = "second-query"))))
        assertNull(adapter.searchField(snapshot(UiNode("unrelated-input", editable = true))))
    }

    @Test
    fun `requested documentary is selected by title despite Romanian accents`() {
        val video = video("matching", "Planeta noastră")

        val target = adapter.result(snapshot(video), VoiceIntent("documentarul Planeta noastra"))

        assertEquals(video, target?.node)
        assertEquals("Planeta noastră", target?.title)
    }

    @Test
    fun `clickable video parent is selected instead of nonactionable title text`() {
        val container = clickable("video-card")
        val title = UiNode("video-card/title", text = TITLE, parentId = container.id,
            resourceId = "com.google.android.youtube:id/video_title")

        val target = adapter.result(snapshot(container, title), VoiceIntent(TITLE))

        assertEquals(container, target?.node)
        assertEquals(TITLE, target?.title)
    }

    @Test
    fun `generic title requires video metadata rather than a matching channel name`() {
        val channel = clickable("channel-card")
        val title = UiNode("channel-card/title", text = TITLE, parentId = channel.id,
            resourceId = "com.google.android.youtube:id/title")

        assertNull(adapter.result(snapshot(channel, title), VoiceIntent(TITLE)))

        val duration = UiNode("channel-card/duration", text = "1:35:00", parentId = channel.id,
            resourceId = "com.google.android.youtube:id/video_duration")
        assertEquals(channel, adapter.result(snapshot(channel, title, duration), VoiceIntent(TITLE))?.node)
    }

    @Test
    fun `duplicate equally good videos cause refusal instead of a first-result fallback`() {
        assertNull(adapter.result(snapshot(video("first", TITLE), video("second", TITLE)), VoiceIntent(TITLE)))
    }

    @Test
    fun `specific exact title outranks a broader title`() {
        val exact = video("exact", TITLE)
        val broader = video("broader", "$TITLE trailer")

        assertEquals(exact, adapter.result(snapshot(broader, exact), VoiceIntent(TITLE))?.node)
    }

    @Test
    fun `short request requires every meaningful word`() {
        assertNull(adapter.result(snapshot(video("partial", "Planeta")), VoiceIntent(TITLE)))
        assertNull(adapter.result(snapshot(video("wrong", "Oceanul nostru")), VoiceIntent(TITLE)))
    }

    @Test
    fun `search suggestions cannot be treated as playable results`() {
        assertNull(adapter.result(snapshot(
            video("matching-title", TITLE),
            UiNode("suggestions", resourceId = "com.google.android.youtube:id/search_suggestions")
        ), VoiceIntent(TITLE)))
    }

    @Test
    fun `sponsored video and shorts targets are ignored`() {
        assertNull(adapter.result(snapshot(video("sponsored", TITLE).copy(description = "Sponsored")), VoiceIntent(TITLE)))
        assertNull(adapter.result(snapshot(video("shorts", TITLE).copy(resourceId = "com.google.android.youtube:id/shorts_title")), VoiceIntent(TITLE)))
    }

    @Test
    fun `sponsored sibling labels disqualify the entire clickable result card`() {
        val card = clickable("results/card")
        val title = video("results/card/title", TITLE).copy(clickable = false, actions = emptySet(), parentId = card.id)

        listOf("Sponsored", "Publicitate", "Ad").forEach { label ->
            val advertisement = UiNode("results/card/badge", text = label, parentId = card.id)

            assertNull("Ad marker $label must not be bypassed by selecting its sibling title",
                adapter.result(snapshot(card, title, advertisement), VoiceIntent(TITLE)))
        }
    }

    @Test
    fun `advertisement marker in another card does not disqualify an ordinary result`() {
        val card = clickable("results/ordinary")
        val title = video("results/ordinary/title", TITLE).copy(clickable = false, actions = emptySet(), parentId = card.id)
        val adCard = clickable("results/ad")
        val advertisement = UiNode("results/ad/badge", text = "Sponsored", parentId = adCard.id)

        assertEquals(card, adapter.result(snapshot(card, title, adCard, advertisement), VoiceIntent(TITLE))?.node)
    }

    @Test
    fun `accessible video description requires metadata as evidence of playable content`() {
        val video = clickable("video", description = "$TITLE • 20K views • 40 minutes")

        assertEquals(video, adapter.result(snapshot(video), VoiceIntent(TITLE))?.node)
        assertNull(adapter.result(snapshot(clickable("suggestion", description = TITLE)), VoiceIntent(TITLE)))
    }

    @Test
    fun `punctuation in accessible title is preserved before metadata separator`() {
        val title = "Blue Planet - Our Home, Part 1"
        val video = clickable("video", description = "$title • 20K views • 40 minutes")

        val result = adapter.result(snapshot(video), VoiceIntent(title))

        assertEquals(video, result?.node)
        assertEquals(title, result?.title)
    }

    @Test
    fun `verification requires title and player from the opened watch screen`() {
        val result = video("results/0", TITLE)
        val target = VideoTarget(result, TITLE)
        val watchTitle = result.copy(id = "watch/title", resourceId = "com.google.android.youtube:id/watch_title")
        val player = UiNode("watch/player", resourceId = "com.google.android.youtube:id/player_view")

        assertFalse(adapter.verified(snapshot(watchTitle), target))
        assertFalse(adapter.verified(snapshot(player), target))
        assertFalse(adapter.verified(snapshot(player, watchTitle.copy(text = "Alt documentar")), target))
        assertFalse(adapter.verified(snapshot(player, result), target))
        assertFalse(adapter.verified(snapshot(player.copy(visible = false), watchTitle), target))
        assertTrue(adapter.verified(snapshot(player, watchTitle), target))
    }

    @Test
    fun `search result child title and an existing mini player cannot verify navigation`() {
        val card = clickable("results/card")
        val resultTitle = video("results/card/title", TITLE).copy(clickable = false, actions = emptySet(), parentId = card.id)
        val miniPlayer = UiNode("mini-player", resourceId = "com.google.android.youtube:id/player_view")
        val target = VideoTarget(card, TITLE)

        assertFalse(adapter.verified(snapshot(card, resultTitle, miniPlayer), target))
    }

    @Test
    fun `nested result title remains result evidence rather than an opened watch screen`() {
        val card = clickable("results/card")
        val section = UiNode("results/card/content", parentId = card.id)
        val resultTitle = video("results/card/content/title", TITLE).copy(clickable = false, actions = emptySet(), parentId = section.id)
        val miniPlayer = UiNode("mini-player", resourceId = "com.google.android.youtube:id/player_view")
        val target = VideoTarget(card, TITLE)

        assertFalse(adapter.verified(snapshot(card, section, resultTitle, miniPlayer), target))
    }

    @Test
    fun `play pause control alone is insufficient evidence of an opened full player`() {
        val target = VideoTarget(video("results/0", TITLE), TITLE)
        val watchTitle = UiNode("watch/title", text = TITLE, resourceId = "com.google.android.youtube:id/watch_title")
        val miniPlayerControl = UiNode("mini/play", resourceId = "com.google.android.youtube:id/player_control_play_pause_button")

        assertFalse(adapter.verified(snapshot(watchTitle, miniPlayerControl), target))
    }

    @Test
    fun `watch title may reuse an old tree path when its identity has changed after navigation`() {
        val originalCard = clickable("0/1", resourceId = "com.google.android.youtube:id/video_card")
        val target = VideoTarget(originalCard, TITLE)
        val watchTitle = UiNode("0/1", text = TITLE, resourceId = "com.google.android.youtube:id/watch_title")
        val player = UiNode("watch/player", resourceId = "com.google.android.youtube:id/player_view")

        assertTrue(adapter.verified(snapshot(watchTitle, player), target))
    }

    @Test
    fun `title inside the original result card cannot verify even with a misleading watch title id`() {
        val card = clickable("results/card")
        val title = UiNode("results/card/title", text = TITLE, parentId = card.id,
            resourceId = "com.google.android.youtube:id/watch_title")
        val player = UiNode("mini-player", resourceId = "com.google.android.youtube:id/player_view")

        assertFalse(adapter.verified(snapshot(card, title, player), VideoTarget(card, TITLE)))
    }

    private fun video(id: String, title: String) = clickable(
        id = id,
        text = title,
        resourceId = "com.google.android.youtube:id/video_title"
    )

    private fun clickable(id: String, text: String = "", description: String = "", resourceId: String = "") = UiNode(
        id = id,
        text = text,
        description = description,
        resourceId = resourceId,
        clickable = true,
        actions = setOf(UiAction.CLICK)
    )

    private fun snapshot(vararg nodes: UiNode) = UiSnapshot(VoiceIntent.YOUTUBE_PACKAGE, 12, nodes.toList())

    private companion object {
        const val TITLE = "Planeta noastră"
    }
}
