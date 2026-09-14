package com.forja.app.feature.research

import org.junit.Assert.*
import org.junit.Test

class ResearchMathTest {
    @Test fun restartingCollectionDoesNotCountPausedTime() {
        val visits = ResearchMath.visits(listOf(ResearchFix(0, 44.4, 26.1, 8f, 1), ResearchFix(10000, 44.4, 26.1, 8f, 2)))
        assertEquals(2, visits.size); assertEquals(0L, visits.sumOf { it.observedMs })
    }
    @Test fun overlappingActivitiesCountOnce() {
        assertEquals(25L, ResearchMath.duration(listOf(UsageInterval(0, 10), UsageInterval(5, 20), UsageInterval(25, 30)), 0, 100))
    }
    @Test fun usageIsClippedToWindow() {
        assertEquals(10L, ResearchMath.duration(listOf(UsageInterval(0, 100)), 20, 30))
        assertEquals(0L, ResearchMath.duration(emptyList(), 20, 30))
    }
    @Test fun singleLocationDoesNotInventDwellTime() {
        assertEquals(0L, ResearchMath.visits(listOf(ResearchFix(100, 44.4, 26.1, 8f))).single().observedMs)
    }
    @Test fun missingLocationGapSplitsVisits() {
        val result = ResearchMath.visits(listOf(ResearchFix(0, 44.4, 26.1, 8f), ResearchFix(10000, 44.4, 26.1, 8f), ResearchFix(300000, 44.4, 26.1, 8f)))
        assertEquals(2, result.size); assertEquals(10000L, result[0].observedMs); assertEquals(0L, result[1].observedMs)
    }
    @Test fun movementAndPoorAccuracyDoNotCountAsStaying() {
        assertEquals(2, ResearchMath.visits(listOf(ResearchFix(0, 44.4, 26.1, 8f), ResearchFix(10000, 45.4, 26.1, 8f))).size)
        assertEquals(2, ResearchMath.visits(listOf(ResearchFix(0, 44.4, 26.1, 8f), ResearchFix(5000, 44.4, 26.1, 500f), ResearchFix(10000, 44.4, 26.1, 8f))).size)
    }
}
