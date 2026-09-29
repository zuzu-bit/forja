package com.forja.app.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Linia turei în desfășurare pentru site (users/{uid}/live/go): subțiată, cu capetele păstrate. */
class GoLiveTest {
    @Test fun emptyRunHasNoLine() = assertEquals("", GoTrackService.livePolyline(emptyList()))

    @Test fun shortRunKeepsEveryPointWithFiveDecimals() {
        val line = GoTrackService.livePolyline(listOf(44.4 to 26.1, 44.400012 to 26.100019))
        assertEquals("44.40000,26.10000;44.40001,26.10002", line)
    }

    @Test fun longRunIsThinnedTo500PointsWithBothEnds() {
        val pts = List(3600) { i -> (44.4 + i * 0.00001) to 26.1 }
        val parts = GoTrackService.livePolyline(pts).split(';')
        assertEquals(GoTrackService.LIVE_MAX_POINTS, parts.size)
        assertEquals("44.40000,26.10000", parts.first())
        assertEquals("%.5f,26.10000".format(java.util.Locale.US, 44.4 + 3599 * 0.00001), parts.last())
        assertTrue("a comma never becomes the decimal separator", parts.all { it.count { c -> c == ',' } == 1 })
    }
}
