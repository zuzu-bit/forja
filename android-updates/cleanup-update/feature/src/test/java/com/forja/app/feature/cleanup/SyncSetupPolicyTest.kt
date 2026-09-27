package com.forja.app.feature.cleanup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SyncSetupPolicyTest {
    @Test fun accountChangedBeforeActivationNeverStartsAnyScope() = runBlocking {
        var started = false
        val result = SyncSetupPolicy.run("first", { "second" }, listOf(SyncSetupPolicy.Step("files") { started = true }))
        assertTrue(result.accountChanged)
        assertFalse(started)
    }

    @Test fun accountChangeWhileAwaitingOneScopeDoesNotAuthorizeTheNext() = runBlocking {
        var owner: String? = "first"
        val completed = mutableListOf<String>()
        val result = SyncSetupPolicy.run("first", { owner }, listOf(
            SyncSetupPolicy.Step("files") { completed += "files"; owner = "second" },
            SyncSetupPolicy.Step("sleep") { completed += "sleep" }
        ))
        assertEquals(listOf("files"), completed)
        assertTrue(result.accountChanged)
    }

    @Test fun oneServiceFailureIsReportedWithoutUndoingAnIndependentSuccess() = runBlocking {
        val started = mutableListOf<String>()
        val result = SyncSetupPolicy.run("owner", { "owner" }, listOf(
            SyncSetupPolicy.Step("files") { started += "files" },
            SyncSetupPolicy.Step("sleep") { error("Service not ready") },
            SyncSetupPolicy.Step("contacts") { started += "contacts" }
        ))
        assertEquals(listOf("files", "contacts"), started)
        assertEquals(listOf("sleep"), result.failed)
        assertFalse(result.accountChanged)
    }

    @Test fun cancellationNeverBecomesASuccessOrStartsLaterScopes() = runBlocking {
        var later = false
        try {
            SyncSetupPolicy.run("owner", { "owner" }, listOf(
                SyncSetupPolicy.Step("files") { throw CancellationException("Screen closed") },
                SyncSetupPolicy.Step("sleep") { later = true }
            ))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(later)
    }

    @Test fun signingOutDuringAFailureDoesNotContinueWithMissingIdentity() = runBlocking {
        var owner: String? = "owner"
        var later = false
        val result = SyncSetupPolicy.run("owner", { owner }, listOf(
            SyncSetupPolicy.Step("files") { owner = null; error("Account changed") },
            SyncSetupPolicy.Step("recovery") { later = true }
        ))
        assertTrue(result.accountChanged)
        assertEquals(listOf("files"), result.failed)
        assertFalse(later)
    }
}
