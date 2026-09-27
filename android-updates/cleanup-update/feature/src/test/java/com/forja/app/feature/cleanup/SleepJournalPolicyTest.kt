package com.forja.app.feature.cleanup

import org.junit.Assert.*
import org.junit.Test

class SleepJournalPolicyTest {
    private val id="cec575d7-ec33-4d27-8dfe-de623fd48640"
    @Test fun retriesUseSameIdAndAccountsCannotShareOne() {
        val a=SleepJournalPolicy.localId("alice",id)
        assertTrue(a<0)
        assertEquals(a,SleepJournalPolicy.localId("alice",id))
        assertNotEquals(a,SleepJournalPolicy.localId("bob",id))
        assertNotEquals(a,SleepJournalPolicy.localId("alice","bec575d7-ec33-4d27-8dfe-de623fd48640"))
    }
    @Test fun invalidIntervalsNeverBecomeJournalEntries() {
        assertFalse(SleepJournalPolicy.valid(id,1000,1000,1000))
        assertFalse(SleepJournalPolicy.valid(id,1000,999,1000))
        assertFalse(SleepJournalPolicy.valid(id,1000,1000+14*3600000L,1000+14*3600000L))
        assertFalse(SleepJournalPolicy.valid(id,1000,100000,1000))
        assertFalse(SleepJournalPolicy.valid("bad/id",1000,2000,2000))
        assertTrue(SleepJournalPolicy.valid(id,1000,2000,2000))
    }
    @Test fun durationAndUnknownMeasurementsRemainDistinct() {
        val row=SleepJournalPolicy.cloud(1000,3601000,id)
        assertEquals(1000L,row["startAt"])
        assertEquals(3601000L,row["endAt"])
        assertEquals("recording_interval",row["measurement"])
        listOf("score","deepMin","lightMin","remMin","movements","snoreEvents","talkEvents","soundEvents").forEach{assertTrue(row.containsKey(it));assertNull(row[it])}
    }
}
