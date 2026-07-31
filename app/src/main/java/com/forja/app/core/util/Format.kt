package com.forja.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Formatare RO — virgulă zecimală, copy onest.
object Fmt {
    fun km(m: Double, decimals: Int = 1): String =
        String.format(Locale.ROOT, "%.${decimals}f", m / 1000.0).replace('.', ',')

    fun pace(secPerKm: Long?): String {
        if (secPerKm == null || secPerKm <= 0) return "—"
        val min = secPerKm / 60
        val sec = secPerKm % 60
        return String.format(Locale.ROOT, "%d:%02d", min, sec)
    }

    fun clock(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        )

    fun durationHm(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "$h h ${String.format(Locale.ROOT, "%02d", m)} min" else "$m min"
    }

    fun durationMs(totalSec: Long): String {
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    /** Freshness onest: „chiar acum" · „acum N min" · „acum N h" · „ieri" · „—" */
    fun freshness(updatedAtMillis: Long?): String {
        if (updatedAtMillis == null || updatedAtMillis <= 0) return "—"
        val diffMin = (System.currentTimeMillis() - updatedAtMillis) / 60000
        return when {
            diffMin < 1 -> "chiar acum"
            diffMin < 60 -> "acum $diffMin min"
            diffMin < 60 * 24 -> "acum ${diffMin / 60} h"
            diffMin < 60 * 48 -> "ieri"
            else -> "demult"
        }
    }

    fun today(): LocalDate = LocalDate.now()
    fun epochDay(): Long = LocalDate.now().toEpochDay()

    fun startOfWeekMillis(): Long =
        LocalDate.now().with(java.time.DayOfWeek.MONDAY).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun startOfDayMillis(daysAgo: Long = 0): Long =
        LocalDate.now().minusDays(daysAgo).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun kcalRun(distanceM: Double): Int = (distanceM / 1000.0 * 62).roundToInt()

    fun greeting(): String {
        val h = LocalDateTime.now().hour
        return when {
            h < 5 -> "Noapte bună"
            h < 12 -> "Salut"
            h < 18 -> "Salut"
            else -> "Bună seara"
        }
    }

    fun isEvening(): Boolean = LocalDateTime.now().hour >= 18 || LocalDateTime.now().hour < 5

    val dayLetters = listOf("Lu", "Ma", "Mi", "Jo", "Vi", "Sâ", "Du")
}
