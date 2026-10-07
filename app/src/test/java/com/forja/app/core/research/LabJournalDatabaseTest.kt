package com.forja.app.core.research

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** Exercises real Room/SQLite persistence; no Firebase account or physical phone is needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class LabJournalDatabaseTest {
    private lateinit var application: Application
    private lateinit var database: LabDatabase
    private lateinit var databaseName: String
    private val association = LabAssociation("LAB-TEST", "owner-a", "session-a", "Lab test", "2026-10-07")

    @Before fun openDatabase() {
        application = RuntimeEnvironment.getApplication()
        databaseName = "lab-journal-test-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(application, LabDatabase::class.java, databaseName).build()
    }

    @After fun cleanDatabase() {
        database.close()
        application.deleteDatabase(databaseName)
    }

    @Test fun concurrentCallbacksAllocateUniqueOrderedSequencesAndIds() = runBlocking {
        val journal = LabJournal(database)
        val observed = coroutineScope {
            (1..24).map { number ->
                async(Dispatchers.IO) {
                    journal.append(association, "DEVICE", "test_observation", "{\"number\":$number}", number.toLong())
                }
            }.awaitAll()
        }
        assertEquals(24, observed.map { it.eventId }.toSet().size)
        val pending = database.events().pending("owner-a", "session-a", "LAB-TEST", 100)
        assertEquals((1L..24L).toList(), pending.map { it.sequenceNumber })
        assertEquals(observed.map { it.eventId }.toSet(), pending.map { it.eventId }.toSet())
        assertTrue(pending.all { it.serverReceivedTimestamp == null && it.syncState == "pending" })
    }

    @Test fun retryAcknowledgementsAndNextSequenceSurviveClosingAndReopening() = runBlocking {
        val journal = LabJournal(database)
        val first = journal.append(association, "APP", "foreground", "{\"package\":\"lab.example\"}", 101L)
        val second = journal.append(association, "NETWORK", "changed", "{}", 99L)
        database.events().acknowledge(listOf(first.eventId), "owner-a", "session-a", "LAB-TEST", 999L)
        database.close()
        database = Room.databaseBuilder(application, LabDatabase::class.java, databaseName).build()
        val replay = database.events().pending("owner-a", "session-a", "LAB-TEST")
        assertEquals(listOf(second.eventId), replay.map { it.eventId })
        // Device time can go backwards without changing replay order or event identity.
        assertEquals(99L, replay.single().sourceTimestamp)
        assertEquals(2L, replay.single().sequenceNumber)
        val third = LabJournal(database).append(association, "DEVICE", "heartbeat", "{}", 1L)
        assertEquals(3L, third.sequenceNumber)
        database.events().acknowledge(listOf(second.eventId), "owner-a", "session-a", "LAB-TEST", 1000L)
        database.events().acknowledge(listOf(second.eventId), "owner-a", "session-a", "LAB-TEST", 2000L)
        assertEquals(listOf(third.eventId), database.events().pending("owner-a", "session-a", "LAB-TEST").map { it.eventId })
    }

    @Test fun accountAndSessionChangesCannotReadOrAcknowledgeAnOlderQueue() = runBlocking {
        val oldEvent = LabJournal(database).append(association, "NOTIFICATION", "posted", "{}", 10L)
        val dao = database.events()
        assertTrue(dao.pending("owner-b", "session-a", "LAB-TEST").isEmpty())
        assertTrue(dao.pending("owner-a", "session-b", "LAB-TEST").isEmpty())
        assertTrue(dao.pending("owner-a", "session-a", "OTHER-DEVICE").isEmpty())
        dao.acknowledge(listOf(oldEvent.eventId), "owner-b", "session-a", "LAB-TEST", 11L)
        dao.acknowledge(listOf(oldEvent.eventId), "owner-a", "session-b", "LAB-TEST", 11L)
        assertEquals(listOf(oldEvent.eventId), dao.pending("owner-a", "session-a", "LAB-TEST").map { it.eventId })
    }
}
