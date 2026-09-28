package com.forja.app.core.designsystem.components

import android.app.Application
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Logica ghidajului, fără randare: ce pași se sar, în ce coordonate, și memoria „văzut” din DataStore. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class CoachMarksLogicTest {

    private val steps = listOf(CoachStep("a", "A"), CoachStep("lipsa", "L"), CoachStep("b", "B"))
    private val ownerA = Any()
    private val ownerB = Any()

    private fun registry() = CoachRegistry().apply {
        host = Rect(0f, 100f, 400f, 900f)
        report("a", ownerA, Rect(10f, 150f, 110f, 250f))
        report("b", ownerB, Rect(10f, 800f, 110f, 850f))
    }

    @Test
    fun stepsWithoutTargetOnScreenAreSkipped() {
        val reg = registry()
        assertEquals(0, reg.nextVisible(steps, 0))
        assertEquals(2, reg.nextVisible(steps, 1))
        assertEquals(-1, reg.nextVisible(steps, 3))
    }

    @Test
    fun targetsAreReportedInHostCoordinates() {
        assertEquals(Rect(10f, 50f, 110f, 150f), registry().rectOf("a"))
    }

    @Test
    fun targetsOutsideTheHostOrWithoutSizeDoNotCount() {
        val reg = registry()
        reg.report("a", ownerA, Rect(10f, 950f, 110f, 1000f))   // sub gazdă (derulat în afara ecranului)
        assertNull(reg.rectOf("a"))
        reg.report("a", ownerA, Rect(10f, 150f, 10f, 250f))     // lățime 0 (tăiat de un părinte)
        assertNull(reg.rectOf("a"))
        assertEquals(2, reg.nextVisible(steps, 0))
    }

    @Test
    fun aTargetIsForgottenOnlyByItsOwnNode() {
        val reg = registry()
        reg.forget("a", ownerB)
        assertEquals(0, reg.nextVisible(steps, 0))
        reg.forget("a", ownerA)
        assertEquals(2, reg.nextVisible(steps, 0))
    }

    @Test
    fun seenIsPerScreenAndResettable() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        Tutorial.reset(ctx)
        assertFalse(Tutorial.seen(ctx, "inventory.start").first())
        Tutorial.markSeen(ctx, "inventory.start")
        assertTrue(Tutorial.seen(ctx, "inventory.start").first())
        assertFalse(Tutorial.seen(ctx, "profile").first())
        Tutorial.reset(ctx)
        assertFalse(Tutorial.seen(ctx, "inventory.start").first())
    }
}
