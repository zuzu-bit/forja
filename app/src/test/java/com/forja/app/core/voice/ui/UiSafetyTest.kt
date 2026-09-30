package com.forja.app.core.voice.ui

import com.forja.app.core.voice.VoiceIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSafetyTest {
    @Test
    fun `ordinary visible application screen is allowed`() {
        UiSafety.requireSafe(snapshot(UiNode("search", description = "Caută", clickable = true)), PACKAGE, STAGE)
    }

    @Test
    fun `lock foreign package blocking window and dialog class are rejected`() {
        listOf(
            snapshot().copy(locked = true),
            snapshot().copy(packageName = "com.android.permissioncontroller"),
            snapshot().copy(blockingWindow = true),
            snapshot().copy(windowClass = "android.app.AlertDialog")
        ).forEach(::assertRejected)
    }

    @Test
    fun `password field is rejected even if there is no login button`() {
        assertRejected(snapshot(UiNode("password", editable = true, password = true)))
    }

    @Test
    fun `selected sensitive confirmation and authentication actions are rejected`() {
        listOf("Sign in", "Conectare", "Permite", "Allow", "Plătește", "Cumpără", "Confirm payment", "Get Premium", "Buy or rent")
            .forEach { label -> assertActionRejected(UiNode("button", text = label, clickable = true)) }
    }

    @Test
    fun `optional sign in and Premium buttons do not block an ordinary search screen`() {
        UiSafety.requireSafe(snapshot(
            UiNode("search", description = "Search", clickable = true),
            UiNode("login", text = "Sign in", clickable = true),
            UiNode("premium", text = "Get Premium", clickable = true)
        ), PACKAGE, STAGE)
        UiSafety.requireActionSafe(UiNode("search", description = "Search", clickable = true), STAGE)
    }

    @Test
    fun `authentication and payment editable fields block the entire command`() {
        listOf("Email or phone", "Parolă", "Card number", "Număr card", "CVV", "CVC")
            .forEach { label -> assertRejected(snapshot(UiNode("field", description = label, editable = true))) }
    }

    @Test
    fun `sensitive field descriptions remain detectable after text entry`() {
        assertRejected(snapshot(UiNode("field", text = "already filled", description = "Card number", editable = true)))
    }

    @Test
    fun `stable authentication and payment field ids are rejected without label text`() {
        listOf("email", "identifierId", "password", "card_number", "cvv", "cvc").forEach { fieldId ->
            assertRejected(snapshot(UiNode("field", resourceId = "com.google.android.youtube:id/$fieldId", editable = true)))
        }
    }

    @Test
    fun `ordinary search terms email and password are not mistaken for authentication fields`() {
        listOf("email", "password", "cvv").forEach { query ->
            UiSafety.requireSafe(snapshot(UiNode("query", text = query, description = "Search YouTube",
                resourceId = "com.google.android.youtube:id/search_edit_text", editable = true)), PACKAGE, STAGE)
        }
    }

    @Test
    fun `invisible or disabled sensitive controls cannot block a benign screen`() {
        UiSafety.requireSafe(snapshot(
            UiNode("hidden-login", text = "Sign in", clickable = true, visible = false),
            UiNode("disabled-payment", text = "Pay", clickable = true, enabled = false),
            UiNode("hidden-password", password = true, visible = false)
        ), PACKAGE, STAGE)
    }

    @Test
    fun `ordinary titles containing payment words are not sensitive buttons`() {
        UiSafety.requireSafe(snapshot(
            UiNode("video", text = "How to pay attention", clickable = true),
            UiNode("title", text = "Sign in", clickable = false)
        ), PACKAGE, STAGE)
    }

    @Test
    fun `node identity changes invalidate previously selected target`() {
        val observed = UiNode("0/1", text = "Search", resourceId = "id/search", className = "android.widget.Button",
            clickable = true, actions = setOf(UiAction.CLICK))

        assertTrue(observed.sameTarget(observed.copy()))
        assertFalse(observed.sameTarget(observed.copy(text = "Delete")))
        assertFalse(observed.sameTarget(observed.copy(resourceId = "id/buy")))
        assertFalse(observed.sameTarget(observed.copy(enabled = false)))
        assertFalse(observed.sameTarget(observed.copy(visible = false)))
        assertFalse(observed.sameTarget(observed.copy(password = true)))
        assertFalse(observed.sameTarget(observed.copy(id = "0/2")))
    }

    private fun assertRejected(snapshot: UiSnapshot) {
        try {
            UiSafety.requireSafe(snapshot, PACKAGE, STAGE)
        } catch (failure: UiControlException) {
            assertEquals(STAGE, failure.stage)
            assertTrue(failure.reason.isNotBlank())
            return
        }
        throw AssertionError("Expected the sensitive observation to stop the command")
    }

    private fun assertActionRejected(node: UiNode) {
        try {
            UiSafety.requireActionSafe(node, STAGE)
        } catch (failure: UiControlException) {
            assertEquals(STAGE, failure.stage)
            assertTrue(failure.reason.isNotBlank())
            return
        }
        throw AssertionError("Expected the selected sensitive action to be refused")
    }

    private fun snapshot(vararg nodes: UiNode) = UiSnapshot(PACKAGE, 42, nodes.toList())

    private companion object {
        const val PACKAGE = VoiceIntent.YOUTUBE_PACKAGE
        const val STAGE = "action_executed"
    }
}
