package com.forja.app.screenshots

import android.app.Application
import com.forja.app.core.designsystem.components.MascotShowcase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sistemul de design: mascota (toate stările) și ghidajul de la prima folosire (CoachMarks, §8). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE, application = Application::class)
class DesignSystemShots {

    /** Vitrina e mai înaltă decât un telefon: fereastră de 2000 dp, PNG-ul ia exact înălțimea conținutului. */
    @Test
    @Config(qualifiers = "w393dp-h2000dp-xxhdpi")
    fun mascotShowcase() = shot("mascot_showcase", fullScreen = false) { MascotShowcase() }

    /** Același ecran fără ghidaj — cum arată după prima vizită. */
    @Test
    fun coachMarksOff() = shot("coachmarks_0_off") { CoachMarksDemo(startAt = 0, active = false) }

    /** 1/3: spotul pe plăcile Poze / Documente; cardul jos, în spațiul liber de deasupra butonului. */
    @Test
    fun coachMarksKind() = shot("coachmarks_1_kind") { CoachMarksDemo(startAt = 0) }

    /** Pasul „album” n-are țintă pe ecran → se sare; 2/3: rândul „Tot / Ultimele 500 ▾ / Album ▾”. */
    @Test
    fun coachMarksScope() = shot("coachmarks_2_scope") { CoachMarksDemo(startAt = 1) }

    /** 3/3: ținta e butonul de jos → cardul stă deasupra lui; ultimul pas → „Am înțeles”. */
    @Test
    fun coachMarksStart() = shot("coachmarks_3_start") { CoachMarksDemo(startAt = 3) }

    /** Pe S23 (360 × 696 utili) spațiul liber dintre cipuri și buton e cel mai mic: cardul trebuie să încapă tot. */
    @Test
    @Config(qualifiers = PHONE_S23)
    fun coachMarksKindS23() = shot("coachmarks_1_kind_s23") { CoachMarksDemo(startAt = 0) }

    @Test
    @Config(qualifiers = PHONE_S23)
    fun coachMarksStartS23() = shot("coachmarks_3_start_s23") { CoachMarksDemo(startAt = 3) }
}
