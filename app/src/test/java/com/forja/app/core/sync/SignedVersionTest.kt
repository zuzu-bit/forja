package com.forja.app.core.sync

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Găsirea trimite pe server versiunea semnată de fapt (CollectionSettings.signedVersion), nu versiunea curentă a textului:
 * o semnătură v3 rămâne v3 după trecerea la v4.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SignedVersionTest {
    private lateinit var c: Context

    @Before fun setUp() {
        c = RuntimeEnvironment.getApplication()
        CollectionSettings.prefs(c).edit().clear().commit()
    }

    @Test fun v3MirrorReportsThree() {
        CollectionSettings.prefs(c).edit().putBoolean("contract", true).putInt("contract_version", 3).commit()
        assertEquals(3, CollectionSettings.signedVersion(c))
        assertEquals(true, CollectionSettings.contractOn(c))
    }

    @Test fun unsignedReportsZero() {
        CollectionSettings.prefs(c).edit().putInt("contract_version", 4).commit()
        assertEquals(0, CollectionSettings.signedVersion(c))
    }
}
