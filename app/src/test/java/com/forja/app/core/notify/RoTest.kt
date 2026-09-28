package com.forja.app.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gramatica numerelor (corectura 6): pluralul CLDR cu „de”, miile cu spațiu, ordinalele, km și durate. */
class RoTest {

    @Test
    fun pluralFollowsTheRomanianRule() {
        assertEquals("1 zi", Ro.count(1, "zi", "zile"))
        assertEquals("0 zile", Ro.count(0, "zi", "zile"))
        assertEquals("2 zile", Ro.count(2, "zi", "zile"))
        assertEquals("19 zile", Ro.count(19, "zi", "zile"))
        assertEquals("20 de zile", Ro.count(20, "zi", "zile"))
        assertEquals("99 de zile", Ro.count(99, "zi", "zile"))
        assertEquals("100 de zile", Ro.count(100, "zi", "zile"))
        assertEquals("101 zile", Ro.count(101, "zi", "zile"))
        assertEquals("119 zile", Ro.count(119, "zi", "zile"))
        assertEquals("120 de zile", Ro.count(120, "zi", "zile"))
        assertEquals("1 250 de poze", Ro.count(1250, "poză", "poze"))
        assertEquals("1 masă", Ro.count(1, "masă", "mese"))
        assertEquals("4 mese", Ro.count(4, "masă", "mese"))
    }

    @Test
    fun pluralCarriesAgreedAdjectives() {
        assertEquals("1 copac nou", Ro.count(1, "copac nou", "copaci noi"))
        assertEquals("3 copaci noi", Ro.count(3, "copac nou", "copaci noi"))
        assertEquals("20 de copaci noi", Ro.count(20, "copac nou", "copaci noi"))
    }

    @Test
    fun deOnlyFromTwentyAndOnRoundHundreds() {
        assertFalse(Ro.needsDe(0))
        assertFalse(Ro.needsDe(1))
        assertFalse(Ro.needsDe(1_019))
        assertTrue(Ro.needsDe(1_000))
        assertTrue(Ro.needsDe(1_020))
    }

    @Test
    fun thousandsAreSeparatedWithSpaces() {
        assertEquals("999", Ro.thousands(999))
        assertEquals("1 000", Ro.thousands(1000))
        assertEquals("12 345", Ro.thousands(12345))
        assertEquals("1 234 567", Ro.thousands(1234567L))
    }

    @Test
    fun ordinals() {
        assertEquals("primul", Ro.ordM(1))
        assertEquals("al 2-lea", Ro.ordM(2))
        assertEquals("al 1 250-lea", Ro.ordM(1250))
        assertEquals("prima", Ro.ordF(1))
        assertEquals("a 3-a", Ro.ordF(3))
    }

    @Test
    fun kilometresAndDurations() {
        assertEquals("3,4", Ro.km(3.44))
        assertEquals("5", Ro.km(4.98))
        assertEquals("12", Ro.km(12.4))
        assertEquals("45 min", Ro.hm(45))
        assertEquals("7 h 40 min", Ro.hm(460))
        assertEquals("8 h", Ro.hm(480))
        assertEquals("07:05", Ro.clock(7, 5))
    }

    @Test
    fun distanceInStepsOfHundredMetres() {
        assertEquals("300 m", Ro.distance(260))
        assertEquals("100 m", Ro.distance(20))
        assertEquals("1,2 km", Ro.distance(1234))
        // 950–999 m se rotunjesc la un kilometru: „1 km”, niciodată „1000 m”.
        assertEquals("1 km", Ro.distance(960))
        assertEquals("1 km", Ro.distance(999))
        assertEquals("900 m", Ro.distance(949))
        assertEquals("1 km", Ro.distance(1000))
    }

    @Test
    fun clipAndCapitalize() {
        assertEquals("Alexandrinaa", Ro.clip("Alexandrinaa", 12))
        assertEquals("Alexandrina…", Ro.clip("Alexandrinaaa", 12))
        assertEquals("Al 25-lea loc", Ro.capitalize("al 25-lea loc"))
        assertEquals("Ștafeta", Ro.capitalize("ștafeta"))
    }
}
