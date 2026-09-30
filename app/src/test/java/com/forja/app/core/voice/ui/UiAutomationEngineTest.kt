package com.forja.app.core.voice.ui

import com.forja.app.core.voice.VoiceIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiAutomationEngineTest {
    @Test
    fun `explicit command follows observed search typing submission result and verification`() = runBlocking {
        val driver = ScenarioDriver()
        val events = mutableListOf<Pair<String, String>>()

        engine(driver).execute(VoiceIntent(QUERY)) { stage, detail -> events += stage to detail }

        assertEquals(listOf(PACKAGE), driver.launchedPackages)
        assertEquals(
            listOf(
                Performed("search", UiAction.CLICK, null),
                Performed("query", UiAction.SET_TEXT, QUERY),
                Performed("query", UiAction.IME_ENTER, null),
                Performed("video", UiAction.CLICK, null)
            ),
            driver.performed
        )
        assertEquals(
            listOf("app_opened", "target_found", "action_executed", "target_found", "action_executed",
                "target_found", "action_executed", "target_found", "action_executed", "result_verified"),
            events.map { it.first }
        )
        assertTrue(driver.current.nodes.any { it.resourceId == "player" })
        assertFalse("UI telemetry must not copy the dictated query", events.any { QUERY in it.second })
    }

    @Test
    fun `visible search field is reused without clicking an unrelated button`() = runBlocking {
        val driver = ScenarioDriver(startWithSearchField = true)

        engine(driver).execute(VoiceIntent(QUERY)) { _, _ -> }

        assertEquals(listOf("query", "query", "video"), driver.performed.map { it.id })
        assertEquals(UiAction.SET_TEXT, driver.performed.first().action)
    }

    @Test
    fun `accessible submit button is used when input has no IME action`() = runBlocking {
        val driver = ScenarioDriver(imeAction = false, submitButton = true)

        engine(driver).execute(VoiceIntent(QUERY)) { _, _ -> }

        assertEquals(Performed("submit", UiAction.CLICK, null), driver.performed[2])
    }

    @Test
    fun `missing accessible submission action fails without opening a result`() = runBlocking {
        val driver = ScenarioDriver(imeAction = false, submitButton = false)

        val failure = failure { engine(driver).execute(VoiceIntent(QUERY)) { _, _ -> } }

        assertEquals("action_executed", failure.stage)
        assertTrue(failure.reason.contains("trimitere"))
        assertEquals(listOf("search", "query"), driver.performed.map { it.id })
    }

    @Test
    fun `unsupported application and invalid queries cannot launch an app`() = runBlocking {
        listOf(VoiceIntent(QUERY, "com.example.other"), VoiceIntent("   "), VoiceIntent("x".repeat(251)))
            .forEach { intent ->
                val driver = ScenarioDriver()

                val failure = failure { engine(driver).execute(intent) { _, _ -> } }

                assertEquals("intent_resolved", failure.stage)
                assertTrue(driver.launchedPackages.isEmpty())
                assertTrue(driver.performed.isEmpty())
            }
    }

    @Test
    fun `registered benign application adapter reuses the same automation engine`() = runBlocking {
        val anotherPackage = "com.example.benignvideo"
        val driver = ScenarioDriver(targetPackageName = anotherPackage)
        val adapter = FixtureAdapter(anotherPackage)
        val events = mutableListOf<String>()

        engine(driver, adapter).execute(VoiceIntent(QUERY, anotherPackage)) { stage, _ -> events += stage }

        assertEquals(listOf(anotherPackage), driver.launchedPackages)
        assertEquals(4, driver.performed.size)
        assertEquals("result_verified", events.last())
    }

    @Test
    fun `registered custom benign plan executes without invoking search workflow`() = runBlocking {
        val targetPackage = "com.example.greeting"
        val button = UiNode("button", description = "Afișează mesajul", clickable = true, actions = setOf(UiAction.CLICK))
        val launchedPackages = mutableListOf<String>()
        val actions = mutableListOf<UiAction>()
        var current = UiSnapshot("com.forja.app", 1, emptyList())
        var elapsed = 0L
        val driver = object : UiDriver {
            override suspend fun launch(packageName: String): Boolean {
                launchedPackages += packageName
                current = UiSnapshot(packageName, 2, listOf(button))
                return true
            }

            override suspend fun snapshot() = current

            override suspend fun perform(snapshot: UiSnapshot, node: UiNode, action: UiAction, text: String?): Boolean {
                if (snapshot != current || !button.sameTarget(node) || action != UiAction.CLICK) return false
                actions += action
                current = UiSnapshot(targetPackage, 2, listOf(UiNode("message", text = "Bună ziua")))
                return true
            }

            override suspend fun pause(milliseconds: Long) { elapsed += milliseconds }
            override fun elapsedRealtime() = elapsed
        }
        val adapter = object : AppAdapter {
            override val packageName = targetPackage
            override val displayName = "Demo"
            override fun accepts(intent: VoiceIntent) = intent.query == "arată mesajul"
            override fun plan(intent: VoiceIntent) = object : UiPlan {
                override suspend fun execute(context: UiExecutionContext, intent: VoiceIntent) {
                    val (snapshot, target) = context.target { observed ->
                        observed.nodes.singleOrNull { it.description == "Afișează mesajul" }
                    }
                    context.act(snapshot, target, UiAction.CLICK, "Afișez mesajul")
                    context.observe("result_verified") { observed ->
                        observed.nodes.any { it.id == "message" && it.visible && it.text == "Bună ziua" }
                    }
                    context.verified("Mesajul solicitat este vizibil.")
                }
            }

            override fun searchButton(snapshot: UiSnapshot): UiNode? = error("Custom plan must not use search selectors")
            override fun searchField(snapshot: UiSnapshot): UiNode? = error("Custom plan must not use search selectors")
            override fun submitButton(snapshot: UiSnapshot): UiNode? = error("Custom plan must not submit a search")
            override fun result(snapshot: UiSnapshot, intent: VoiceIntent): VideoTarget? = error("Custom plan must not choose video results")
            override fun verified(snapshot: UiSnapshot, target: VideoTarget): Boolean = error("Custom plan verifies its own benign result")
        }
        val events = mutableListOf<Pair<String, String>>()

        engine(driver, adapter).execute(VoiceIntent("arată mesajul", targetPackage)) { stage, detail -> events += stage to detail }

        assertEquals(listOf(targetPackage), launchedPackages)
        assertEquals(listOf(UiAction.CLICK), actions)
        assertEquals(listOf("app_opened", "target_found", "action_executed", "result_verified"), events.map { it.first })
        assertEquals("Demo este deschis.", events.first().second)
    }

    @Test
    fun `locked phone blocks app launch`() = runBlocking {
        val driver = ScenarioDriver()
        driver.current = driver.current.copy(locked = true)

        val failure = failure { engine(driver).execute(VoiceIntent(QUERY)) { _, _ -> } }

        assertEquals("app_opened", failure.stage)
        assertTrue(driver.launchedPackages.isEmpty())
        assertTrue(driver.performed.isEmpty())
    }

    @Test
    fun `failed launch stops before any UI action`() = runBlocking {
        val driver = ScenarioDriver(launchAllowed = false)

        val failure = failure { engine(driver).execute(VoiceIntent(QUERY)) { _, _ -> } }

        assertEquals("app_opened", failure.stage)
        assertTrue(driver.performed.isEmpty())
    }

    @Test
    fun `foreground app change stops the action chain before text entry`() = runBlocking {
        val driver = ScenarioDriver()
        driver.afterAction = { action ->
            if (action.id == "search") current = current.copy(packageName = "com.android.settings")
        }

        val failure = failure { engine(driver).execute(VoiceIntent(QUERY)) { _, _ -> } }

        assertEquals("target_found", failure.stage)
        assertTrue(failure.reason.contains("activă s-a schimbat"))
        assertEquals(listOf(Performed("search", UiAction.CLICK, null)), driver.performed)
    }

    @Test
    fun `sensitive dialog shown after search is never confirmed`() = runBlocking {
        val driver = ScenarioDriver()
        driver.afterAction = { action ->
            if (action.id == "search") current = current.copy(blockingWindow = true)
        }

        val failure = failure { engine(driver).execute(VoiceIntent(QUERY)) { _, _ -> } }

        assertEquals("target_found", failure.stage)
        assertTrue(failure.reason.contains("dialog"))
        assertEquals(1, driver.performed.size)
    }

    @Test
    fun `driver rejecting stale target aborts without reporting action success`() = runBlocking {
        val driver = ScenarioDriver(rejectAction = true)
        val events = mutableListOf<String>()

        val failure = failure { engine(driver).execute(VoiceIntent(QUERY)) { stage, _ -> events += stage } }

        assertEquals("action_executed", failure.stage)
        assertEquals(listOf("app_opened", "target_found"), events)
        assertEquals(1, driver.performed.size)
    }

    @Test
    fun `sensitive target is refused even if an adapter incorrectly selects it`() = runBlocking {
        val driver = ScenarioDriver(searchText = "Sign in")

        val failure = failure { engine(driver).execute(VoiceIntent(QUERY)) { _, _ -> } }

        assertEquals("action_executed", failure.stage)
        assertTrue(failure.reason.contains("sensibile"))
        assertTrue(driver.performed.isEmpty())
    }

    @Test
    fun `cancellation during an action prevents subsequent actions and success events`() = runBlocking {
        val driver = ScenarioDriver()
        val events = mutableListOf<String>()
        driver.afterAction = { currentCoroutineContext().cancel() }

        val job = launch { engine(driver).execute(VoiceIntent(QUERY)) { stage, _ -> events += stage } }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, driver.performed.size)
        assertEquals(listOf("app_opened", "target_found"), events)
    }

    @Test
    fun `cancellation from observed target callback stops before driver action`() = runBlocking {
        val driver = ScenarioDriver()
        val events = mutableListOf<String>()

        val job = launch {
            val commandJob = currentCoroutineContext()[Job]!!
            engine(driver).execute(VoiceIntent(QUERY)) { stage, _ ->
                events += stage
                if (stage == "target_found") commandJob.cancel()
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(driver.performed.isEmpty())
        assertEquals(listOf("app_opened", "target_found"), events)
    }

    @Test
    fun `missing unique result times out without choosing a video`() = runBlocking {
        val driver = ScenarioDriver(showResult = false)
        val events = mutableListOf<String>()

        val failure = failure { engine(driver).execute(VoiceIntent(QUERY)) { stage, _ -> events += stage } }

        assertEquals("target_found", failure.stage)
        assertTrue(failure.reason.contains("unic"))
        assertFalse(driver.performed.any { it.id == "video" })
        assertFalse("result_verified" in events)
        assertTrue(driver.elapsed >= TIMEOUT)
    }

    @Test
    fun `opened result is not reported verified without player evidence`() = runBlocking {
        val driver = ScenarioDriver(showPlayer = false)
        val events = mutableListOf<String>()

        val failure = failure { engine(driver).execute(VoiceIntent(QUERY)) { stage, _ -> events += stage } }

        assertEquals("result_verified", failure.stage)
        assertTrue(driver.performed.any { it.id == "video" })
        assertFalse("result_verified" in events)
    }

    @Test
    fun `stalled driver clock still has a bounded observation loop`() = runBlocking {
        val driver = ScenarioDriver(showResult = false, advanceClock = false)

        val failure = failure { engine(driver).execute(VoiceIntent(QUERY)) { _, _ -> } }

        assertEquals("target_found", failure.stage)
        assertEquals(3, driver.pauseCount)
        assertEquals(0L, driver.elapsed)
    }

    private fun engine(driver: UiDriver, adapter: AppAdapter = FixtureAdapter()) = UiAutomationEngine(
        driver = driver,
        adapters = listOf(adapter),
        timeoutMillis = TIMEOUT,
        pollMillis = POLL
    )

    private suspend fun failure(block: suspend () -> Unit): UiControlException {
        try {
            block()
        } catch (failure: UiControlException) {
            return failure
        }
        throw AssertionError("Expected the UI command to stop with a reason")
    }

    private class FixtureAdapter(override val packageName: String = PACKAGE) : AppAdapter {
        override fun searchButton(snapshot: UiSnapshot) = snapshot.node("search")
        override fun searchField(snapshot: UiSnapshot) = snapshot.node("query")
        override fun submitButton(snapshot: UiSnapshot) = snapshot.node("submit")
        override fun result(snapshot: UiSnapshot, intent: VoiceIntent) =
            snapshot.node("video")?.takeIf { it.text == intent.query }?.let { VideoTarget(it, it.text) }
        override fun verified(snapshot: UiSnapshot, target: VideoTarget) =
            snapshot.node("player") != null && snapshot.nodes.any { it.text == target.title }
    }

    private data class Performed(val id: String, val action: UiAction, val text: String?)

    private class ScenarioDriver(
        private val startWithSearchField: Boolean = false,
        private val imeAction: Boolean = true,
        private val submitButton: Boolean = false,
        private val launchAllowed: Boolean = true,
        private val rejectAction: Boolean = false,
        private val showResult: Boolean = true,
        private val showPlayer: Boolean = true,
        private val advanceClock: Boolean = true,
        private val searchText: String = "",
        private val targetPackageName: String = PACKAGE
    ) : UiDriver {
        var current = UiSnapshot("com.forja.app", 1, emptyList())
        val launchedPackages = mutableListOf<String>()
        val performed = mutableListOf<Performed>()
        var afterAction: (suspend ScenarioDriver.(Performed) -> Unit)? = null
        var elapsed = 0L
        var pauseCount = 0

        override suspend fun launch(packageName: String): Boolean {
            launchedPackages += packageName
            if (launchAllowed) current = if (startWithSearchField) field("") else home()
            return launchAllowed
        }

        override suspend fun snapshot() = current

        override suspend fun perform(snapshot: UiSnapshot, node: UiNode, action: UiAction, text: String?): Boolean {
            val performedAction = Performed(node.resourceId, action, text)
            performed += performedAction
            if (rejectAction || current != snapshot || !current.nodes.any { it.sameTarget(node) }) return false
            current = when {
                node.resourceId == "search" && action == UiAction.CLICK -> field("")
                node.resourceId == "query" && action == UiAction.SET_TEXT -> field(text.orEmpty())
                (node.resourceId == "query" && action == UiAction.IME_ENTER) || node.resourceId == "submit" ->
                    UiSnapshot(targetPackageName, 2, if (showResult) listOf(video()) else emptyList())
                node.resourceId == "video" && action == UiAction.CLICK -> UiSnapshot(
                    targetPackageName, 2, if (showPlayer) listOf(video(), UiNode("2", resourceId = "player")) else listOf(video())
                )
                else -> return false
            }
            afterAction?.invoke(this, performedAction)
            return true
        }

        override suspend fun pause(milliseconds: Long) {
            pauseCount++
            if (advanceClock) elapsed += milliseconds
        }

        override fun elapsedRealtime() = elapsed

        private fun home() = UiSnapshot(targetPackageName, 2, listOf(
            UiNode("0", text = searchText, description = "Search", resourceId = "search", clickable = true, actions = setOf(UiAction.CLICK))
        ))

        private fun field(value: String) = UiSnapshot(targetPackageName, 2, buildList {
            add(UiNode("0", text = value, resourceId = "query", editable = true,
                actions = if (imeAction) setOf(UiAction.SET_TEXT, UiAction.IME_ENTER) else setOf(UiAction.SET_TEXT)))
            if (submitButton) add(UiNode("1", description = "Submit search", resourceId = "submit", clickable = true))
        })

        private fun video() = UiNode("0", text = QUERY, resourceId = "video", clickable = true)
    }

    private companion object {
        const val PACKAGE = VoiceIntent.YOUTUBE_PACKAGE
        const val QUERY = "Planeta noastră documentar"
        const val TIMEOUT = 10L
        const val POLL = 5L
        fun UiSnapshot.node(resourceId: String) = nodes.firstOrNull { it.resourceId == resourceId }
    }
}
