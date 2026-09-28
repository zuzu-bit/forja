package com.forja.app.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portul lui check_bank.py (notifications-design.md §H, testul 6) peste toată banca și textele fixe:
 * 0 „!”, 0 emoji, 0 cedile (ş ţ), fără cuvinte interzise, titlu ≤ 40 (permanenta ≤ 28), text ≤ 90 cu valori lungi,
 * ≤ 1 „?”, placeholdere cunoscute, id-uri unice — plus regulile de voce din „Verificare adversarială”.
 */
class NudgeBankLintTest {

    private data class Line(
        val id: String, val context: NudgeContext?, val title: String, val body: String,
        /** Forma extinsă (BigText) are voie la mai mult decât cele două rânduri ale formei restrânse. */
        val bodyMax: Int = Nudge.BODY_MAX
    )

    /** Toate textele: fiecare șablon (și fiecare voce a lui) + textele fixe ale serviciilor. */
    private val lines: List<Line> = buildList {
        for (t in NudgeBank.all) {
            add(Line(t.id, t.context, t.title, t.body))
            t.voices.forEach { (v, body) -> add(Line("${t.id}/${v.name}", t.context, t.title, body)) }
        }
        val cats = "locație, aplicații, fotografii, fișiere alese, microfon"
        add(Line("sync.collapsed", null, SyncCopy.PUBLIC_TITLE, SyncCopy.collapsed(cats)))
        add(Line("sync.honest", null, SyncCopy.MIC_TITLE, SyncCopy.honest(cats), bodyMax = 140))
        add(Line("focus.big", null, ServiceCopy.FOCUS_TITLE, ServiceCopy.FOCUS_BIG, bodyMax = 140))
        add(Line("go", null, ServiceCopy.goTitle("walk"), ServiceCopy.GO_TEXT))
        add(Line("sleep", null, ServiceCopy.SLEEP_TITLE, ServiceCopy.SLEEP_TEXT))
        add(Line("focus", null, ServiceCopy.FOCUS_TITLE, ServiceCopy.FOCUS_TEXT))
        add(Line("laptop", null, ServiceCopy.laptopTitle(1_250), ServiceCopy.LAPTOP_TEXT))
        for (stage in listOf("scanning", "grouping", "naming", "applying")) for (photos in listOf(true, false)) {
            add(Line("inv.$stage.$photos", null, InventoryCopy.progressTitle(100), InventoryCopy.stageLine(stage, photos)))
        }
        add(Line("inv.ready", null, InventoryCopy.readyTitle(1_250), InventoryCopy.readyText(true, 12_345, 1_234)))
    }

    private fun filled(text: String): String {
        val out = Nudge.fill(text, NudgeFixtures.longFill)
        return out ?: error("placeholder necunoscut în: $text")
    }

    @Test
    fun noExclamationEmojiOrCedilla() {
        for (l in lines) for (text in listOf(l.title, l.body)) {
            assertTrue("${l.id}: „!” în „$text”", !text.contains('!'))
            assertTrue("${l.id}: cedilă în „$text”", text.none { it in "şţŞŢ" })
            val emoji = text.codePoints().filter { it >= 0x1F000 || it in 0x2600..0x27BF }.toArray()
            assertTrue("${l.id}: emoji în „$text”", emoji.isEmpty())
        }
    }

    @Test
    fun noForbiddenPhrases() {
        val forbidden = listOf(
            "bravo", "super", "wow", "felicit", "ups", "hai să", "descoperă", "călătoria", "transformă", "îmbrățișează",
            // glume de supraveghere: aplicația chiar colectează, ar suna a amenințare
            "te-am văzut", "știu unde", "te urmăresc", "am auzit tot"
        )
        for (l in lines) {
            val text = (l.title + " " + l.body).lowercase()
            for (w in forbidden) {
                assertTrue("${l.id}: „$w”", !Regex("""(^|[^\p{L}])${Regex.escape(w)}""").containsMatchIn(text))
            }
        }
    }

    @Test
    fun allPlaceholdersAreKnownAndLengthsFitWithLongValues() {
        for (l in lines) {
            val title = filled(l.title)
            val body = filled(l.body)
            val max = if (l.context == NudgeContext.SyncOngoing) Nudge.SYNC_TITLE_MAX else Nudge.TITLE_MAX
            assertTrue("${l.id}: titlu ${title.length} > $max: „$title”", title.length <= max)
            assertTrue("${l.id}: text ${body.length} > ${l.bodyMax}: „$body”", body.length <= l.bodyMax)
        }
    }

    @Test
    fun atMostOneQuestionMark() {
        for (l in lines) assertTrue(l.id, (l.title + l.body).count { it == '?' } <= 1)
    }

    @Test
    fun idsAreUnique() {
        val ids = NudgeBank.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyContextHasABank() {
        for (ctx in NudgeContext.entries) assertTrue(ctx.name, NudgeBank.of(ctx).isNotEmpty())
        for (t in NudgeBank.all) assertEquals(t.id, t.context, NudgeBank.all.first { it.id == t.id }.context)
    }

    @Test
    fun noAgreementWithTheReadersOrTheFriendsGender() {
        // Lana e femeie; textele nu acordă adjective cu cine citește (corectura 5) și nu presupun genul prietenului.
        val reader = listOf(
            "senin", "ești singur", "ești pregătit", "ești gata", "odihnit", "sunt mândru", "dormi liniștit",
            "ai fost prezent", "obosit", "treaz"
        )
        // Despre prieten: „prinde-l”, „harta lui”, „el/ea” presupun genul (în restul textelor „la locul lui” e despre lucruri).
        val friend = Regex("""-l\b|\blui\b|\bel\b|\bea\b""")
        val aboutFriends = NudgeBank.all.filter {
            it.context in setOf(NudgeContext.FriendNear, NudgeContext.NewFriend) || "prieten" in it.placeholders
        }.map { it.id }.toSet()
        for (l in lines) {
            val text = (l.title + " " + l.body).lowercase()
            for (w in reader) assertTrue("${l.id}: „$w”", !Regex("""(^|[^\p{L}])${Regex.escape(w)}""").containsMatchIn(text))
            if (l.id.substringBefore('/') in aboutFriends) assertTrue("${l.id}: gen în „$text”", !friend.containsMatchIn(text))
        }
        assertTrue(aboutFriends.size >= 4)
    }

    @Test
    fun onlyTheSergeantWorkoutStreakIsAngry() {
        assertEquals(listOf("5.2"), NudgeBank.all.filter { it.pose == NudgePose.Angry }.map { it.id })
    }
}
