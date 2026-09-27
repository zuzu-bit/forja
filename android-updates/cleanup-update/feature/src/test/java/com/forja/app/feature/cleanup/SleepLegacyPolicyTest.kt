package com.forja.app.feature.cleanup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SleepLegacyPolicyTest {
    private val id="c6ff3a61-00af-4591-aee2-26320cf65e00"
    private fun recording()=JSONObject().put("owner","alice").put("id",id).put("started_at",100000L)
    @Test fun activeRecorderExposesTheExistingStopCard(){
        assertEquals(LegacyActiveSleep("alice",id,100000L),SleepLegacyPolicy.active("alice",recording(),200000L))
    }
    @Test fun stopAndAccountChangesRemoveTheActiveCard(){
        assertNull(SleepLegacyPolicy.active("alice",recording().put("ended_at",190000L),200000L))
        assertNull(SleepLegacyPolicy.active("bob",recording(),200000L))
        assertNull(SleepLegacyPolicy.active(null,recording(),200000L))
        assertNull(SleepLegacyPolicy.active("alice",null,200000L))
    }
    @Test fun malformedOrFutureRecorderMetadataCannotCreateAnActiveCard(){
        assertNull(SleepLegacyPolicy.active("alice",recording().put("id","invalid"),200000L))
        assertNull(SleepLegacyPolicy.active("alice",recording().put("started_at",0L),200000L))
        assertNull(SleepLegacyPolicy.active("alice",recording().put("started_at",400000L),200000L))
    }
}
