package com.forja.app.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Zilele încheiate ale timpului pe ecran: orele fără dublă numărare, fără FORJA, ieri … acum 7 zile. */
class UsageDayTest {
    private val zone = ZoneId.of("Europe/Bucharest")
    private val day = LocalDate.of(2026, 9, 27)
    private val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    private val h = 3_600_000L; private val m = 60_000L

    @Test fun overlappingActivitiesCountOnceAndSplitByHour() {
        val spans = mapOf(
            "com.instagram.android" to listOf(UsageInterval(from + 7 * h + 50 * m, from + 8 * h + 10 * m), UsageInterval(from + 8 * h, from + 8 * h + 5 * m)),
            "com.forja.app.research" to listOf(UsageInterval(from + 12 * h, from + 13 * h)),
        )
        val (hours, first, last) = UsageDay.hours(spans, "com.forja.app.research", from, to, zone)
        assertEquals(10 * m, hours[7]); assertEquals(10 * m, hours[8]); assertEquals(0L, hours[12])
        assertEquals(from + 7 * h + 50 * m, first); assertEquals(from + 8 * h + 10 * m, last)
    }

    @Test fun daysToSendAreYesterdayBackSevenDays() {
        val d = UsageDay.daysToSend(LocalDate.of(2026, 9, 28))
        assertEquals(7, d.size); assertEquals(LocalDate.of(2026, 9, 21), d.first()); assertEquals(LocalDate.of(2026, 9, 27), d.last())
    }

    @Test fun fallBackNightGivesHourThreeTwoHours() {
        val night = LocalDate.of(2026, 10, 25)
        val a = night.atStartOfDay(zone).toInstant().toEpochMilli()
        val (hours, _, _) = UsageDay.hours(mapOf("a.b" to listOf(UsageInterval(a, a + 25 * h))), "x", a, a + 25 * h, zone)
        assertEquals(2 * h, hours[3]); assertEquals(25 * h, hours.sum())
    }
}
