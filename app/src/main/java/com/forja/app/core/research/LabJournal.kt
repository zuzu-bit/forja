package com.forja.app.core.research

import androidx.room.withTransaction
import java.util.UUID

/** Allocate sequence and write event in the same SQLite transaction, including after process death. */
internal class LabJournal(private val database: LabDatabase) {
    suspend fun append(session: LabAssociation, source: String, type: String, payload: String, timestamp: Long, receivedTimestamp: Long = System.currentTimeMillis()): LabEvent = database.withTransaction {
        val dao = database.events()
        val next = dao.sequence(session.deviceId)?.nextSequence ?: 1L
        check(next > 0 && next < Long.MAX_VALUE) { "Event sequence exhausted" }
        val event = LabEvent(UUID.randomUUID().toString(), session.deviceId, session.ownerUid, session.labSessionId,
            source, type, timestamp, receivedTimestamp, sequenceNumber = next, payload = payload)
        dao.insert(event)
        dao.setSequence(LabSequence(session.deviceId, next + 1))
        event
    }
}
