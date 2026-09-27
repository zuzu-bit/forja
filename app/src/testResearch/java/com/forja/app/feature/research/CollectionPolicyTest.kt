package com.forja.app.feature.research

import org.junit.Assert.*
import org.junit.Test

class CollectionPolicyTest {
    @Test fun androidGrantsDoNotEnableCollection() {
        assertTrue(CollectionPolicy.allowed("a", "a", emptySet(), setOf("location", "audio"), 1, 1).isEmpty())
    }
    @Test fun logoutAccountSwitchAndDisableInvalidateCapturedWork() {
        for (current in listOf(null, "b")) assertTrue(CollectionPolicy.allowed("a", current, setOf("audio"), setOf("audio"), 1, 1).isEmpty())
        assertTrue(CollectionPolicy.allowed("a", "a", setOf("audio"), setOf("audio"), 1, 2).isEmpty())
    }
    @Test fun revokedPermissionIsExcluded() {
        assertEquals(setOf("app_usage"), CollectionPolicy.allowed("a", "a", setOf("location", "app_usage"), setOf("app_usage"), 1, 1))
    }
    @Test fun historyStartsAtConsentOrCurrentSessionAndNeverBeforeLastDay() {
        assertEquals(200L, CollectionPolicy.usageFrom(200, 100, 1000))
        assertEquals(300L, CollectionPolicy.usageFrom(200, 300, 1000))
        assertEquals(13600000L, CollectionPolicy.usageFrom(0, 0, 100000000))
        assertEquals(1000L, CollectionPolicy.usageFrom(2000, 0, 1000))
    }
}
