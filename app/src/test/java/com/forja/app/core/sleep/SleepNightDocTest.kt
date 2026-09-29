package com.forja.app.core.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Oglinda nopții (pachetul B): ce ajunge în users/{uid}/sleep și sleepEvents, fără Firebase. */
class SleepNightDocTest {
    private val audioStart = 1_790_000_000_000L

    @Test
    fun serverEventsGoOnTheClockFromTheAudioStartAndPhoneEventsKeepTheirTime() {
        val server = SleepTimeline(
            "done", startedAt = audioStart - 5_000L,
            events = listOf(
                SleepTimeline.Event(at = 60_000L, end = 63_000L, type = "talk", intensity = 0.2, transcript = "  Nu acum.  "),
                SleepTimeline.Event(at = 10_000L, end = 20_000L, type = "silence"),
                SleepTimeline.Event(at = 120_000L, end = 180_000L, type = "whistle", intensity = 1.4)
            )
        )
        val phone = listOf(SleepNightDoc.PhoneEvent(7, "snore", audioStart + 30_000L, 12, 3, null), SleepNightDoc.PhoneEvent(8, "move", audioStart, 1, 1, null))
        val rows = SleepNightDoc.events(phone, server, audioStart)
        assertEquals(3, rows.size)
        assertEquals(listOf(audioStart + 30_000L, audioStart + 60_000L, audioStart + 120_000L), rows.map { it["at"] })
        assertEquals(mapOf("kind" to "snore", "at" to audioStart + 30_000L, "dur" to 12_000L, "intensity" to 0.85, "source" to "phone", "pid" to 7L), rows[0])
        assertEquals("Nu acum.", rows[1]["text"])
        assertEquals("server", rows[1]["source"])
        assertEquals("noise", rows[2]["kind"])
        assertEquals(1.0, rows[2]["intensity"])
    }

    @Test
    fun overTheCapTalkWithTextStaysFirstThenTheLoudest() {
        val phone = (1..300).map { SleepNightDoc.PhoneEvent(it.toLong(), "snore", audioStart + it * 1000L, 5, if (it % 2 == 0) 3 else 1, null) } +
            SleepNightDoc.PhoneEvent(999, "talk", audioStart + 999_000L, 5, 1, "x".repeat(400))
        val rows = SleepNightDoc.events(phone, null, audioStart)
        assertEquals(SleepNightDoc.EVENTS_MAX, rows.size)
        assertEquals(SleepNightDoc.TEXT_MAX, (rows.last()["text"] as String).length)
        assertEquals(150, rows.count { it["intensity"] == 0.85 })
        assertTrue(rows.any { it["kind"] == "talk" })
        assertEquals(rows.map { it["at"] as Long }.sorted(), rows.map { it["at"] })
    }

    @Test
    fun stagingGoesToTheJournalAndTheTimelineKeepsTheServerLimits() {
        val staging = SleepStaging.Result("0,14,awake;14,90,light", 90, 250, 100, 12, 14, 3, 78, listOf(SleepStaging.ScoreLine(84, "7 h 10 dormite"), SleepStaging.ScoreLine(-8, "3 treziri")))
        val night = SleepNightDoc.stagingMap(staging)
        assertEquals("0,14,awake;14,90,light", night["phases"])
        assertEquals(14, night["latencyMin"]); assertEquals(3, night["awakenings"]); assertEquals(12, night["awakeMin"])
        assertEquals(listOf(mapOf("delta" to 84, "reason" to "7 h 10 dormite"), mapOf("delta" to -8, "reason" to "3 treziri")), night["scoreLines"])
        assertEquals(emptyMap<String, Any>(), SleepNightDoc.stagingMap(null))
        val t = SleepTimeline("done", startedAt = audioStart, limits = listOf("fără Gemini: sforăitul nu se poate detecta", " "),
            stats = SleepTimeline.Stats(snoreEpisodes = 2, coughCount = 1, coverageMin = 470, totalMin = 480))
        val doc = SleepNightDoc.timelineDoc(audioStart - 60_000L, emptyList(), t, audioStart, 5L)
        assertFalse(doc.containsKey("phases")); assertFalse(doc.containsKey("scoreLines"))
        assertEquals(listOf("fără Gemini: sforăitul nu se poate detecta"), doc["limits"])
        assertEquals(mapOf("snoreEpisodes" to 2, "coughCount" to 1, "coverageMin" to 470, "totalMin" to 480), doc["stats"])
        assertEquals("done", doc["analysis"])
        val bare = SleepNightDoc.timelineDoc(1L, emptyList(), null, 0L, 5L)
        assertFalse(bare.containsKey("limits")); assertFalse(bare.containsKey("audioStartAt"))
        assertEquals(emptyList<Any>(), bare["events"])
    }

    @Test
    fun uploadStateReadsLikeTheSite() {
        fun st(chunks: Int = 16, up: Int = 0, rej: Int = 0, an: Boolean = false, done: Boolean = false, att: Int = 1, err: String = "", cell: Boolean = false, started: Boolean = true) =
            SleepNightDoc.audioState(chunks, up, rej, an, done, att, 24, err, cell, started)
        assertEquals("none", st(chunks = 0))
        assertEquals("waiting_wifi", st(started = false))
        assertEquals("waiting_net", st(started = false, cell = true))
        assertEquals("waiting_battery", st(err = "baterie sub 15 %"))
        assertEquals("uploading", st(up = 3))
        assertEquals("analyzing", st(up = 15, rej = 1))
        assertEquals("analyzing", st(up = 16, an = true))
        assertEquals("done", st(up = 16, done = true))
        assertEquals("done", st(up = 16, done = true, err = "serverul nu a mai avansat"))
        assertEquals("failed", st(up = 0, done = true, err = "nicio bucată nu a putut urca"))
        assertEquals("failed", st(up = 2, att = 24))
    }

    @Test
    fun alarmOffIsNotWrittenAndSoundsKeepOnlyWhatPlayed() {
        assertNull(SleepNightDoc.alarmMap(null))
        assertNull(SleepNightDoc.alarmMap(SleepNightDoc.Alarm(false, 1L, 30)))
        assertEquals(mapOf("target" to 9L, "windowMin" to 30, "firedAt" to 7L, "reason" to "cycle", "snoozes" to 1),
            SleepNightDoc.alarmMap(SleepNightDoc.Alarm(true, 9L, 30, 7L, "cycle", 1)))
        assertEquals(mapOf<String, Any>("target" to 9L, "windowMin" to 30, "snoozes" to 0), SleepNightDoc.alarmMap(SleepNightDoc.Alarm(true, 9L, 30)))
        assertEquals(listOf(mapOf("sound" to "rain", "minutes" to 30), mapOf("sound" to "fire", "minutes" to 2)),
            SleepNightDoc.soundsList(listOf(SleepNightDoc.SoundUse("fire", 2), SleepNightDoc.SoundUse("rain", 30), SleepNightDoc.SoundUse("wind", 0))))
        val a = SleepNightDoc.audioMap(SleepNightDoc.Audio(16, 3, "uploading", "", audioStart, 0L))
        assertEquals(mapOf("chunks" to 16, "uploaded" to 3, "state" to "uploading", "lastError" to "", "audioStartAt" to audioStart), a)
    }
}
