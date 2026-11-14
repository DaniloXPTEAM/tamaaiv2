package io.github.nanomuse.isolated

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MainActivity
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.tools.AgentTools
import io.github.nanomuse.chat.PromptDiet
import io.github.nanomuse.chat.PromptSize
import io.github.nanomuse.chat.PromptSizeTrace
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.PREF_APPEARANCE
import io.github.nanomuse.home.MainChat
import io.github.nanomuse.pet.PetCareAction
import io.github.nanomuse.pet.PetMission
import io.github.nanomuse.pet.PetStore
import io.github.nanomuse.ui.chat.ChatBackground
import io.github.nanomuse.ui.chat.PromptSizeDetailTrace
import io.github.nanomuse.ui.onboarding.FirstRunSetup
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the actual MainActivity on the freshly installed APK. The only model is a local fixture.
 * It writes the normal stores so a second instrumentation run can verify them after force-stop.
 */
@RunWith(AndroidJUnit4::class)
class RealAppJourneyInstrumentedTest {
    companion object {
        // Runs before the Compose rule creates the Activity, even if invoked outside CI.
        @BeforeClass @JvmStatic fun checkIsolation() = requireIsolatedEmulator()
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as MinisApp
    private val promptEvents = Collections.synchronizedList(mutableListOf<PromptSizeTrace.Event>())
    private val promptDetailEvents = Collections.synchronizedList(mutableListOf<PromptSizeDetailTrace.Event>())

    private fun text(id: Int) = context.getString(id)
    private fun awaitText(value: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(value)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitDescription(value: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasContentDescription(value)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun tab(id: Int) = compose.onNode(hasContentDescription(text(id)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
    private fun radio(id: Int) = compose.onNode(hasText(text(id)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    // Title generation also quotes the prompt, so match the exact user message.
    private fun isFixtureChat(request: org.json.JSONObject, prompt: String = LocalOpenAiFixture.PROMPT): Boolean {
        val messages = request.optJSONArray("messages") ?: return false
        return (0 until messages.length()).any { index ->
            val message = messages.optJSONObject(index)
            message?.optString("role") == "user" && message.optString("content") == prompt
        }
    }

    private fun rectEvidence(label: String, rect: androidx.compose.ui.geometry.Rect?): String =
        "v1 rect $label " + if (rect == null) "unknown" else
            listOf(rect.left, rect.top, rect.right, rect.bottom).joinToString(" ") { it.toInt().toString() }

    private fun checkLeanMetrics(chat: org.json.JSONObject) {
        // The greeting really uses LEAN: no tools are serialized, and the count-only
        // observer must describe precisely the system string that reached the fixture.
        val messages = chat.getJSONArray("messages")
        val system = (0 until messages.length()).map { messages.getJSONObject(it) }
            .single { it.optString("role") == "system" }.getString("content")
        assertTrue("The greeting did not use the light prompt", system.contains("No tools are available in this request"))
        assertTrue("The light request advertised tools", (chat.optJSONArray("tools")?.length() ?: 0) == 0)
        val events = synchronized(promptEvents) { promptEvents.toList() }
        assertEquals(system.length, events.filterIsInstance<PromptSizeTrace.Event.System>().last().rows
            .single { it.label == PromptSize.Section.TOTAL }.afterChars)
        assertEquals(0, events.filterIsInstance<PromptSizeTrace.Event.Tools>().last().rows
            .single { it.label == PromptSize.Tool.TOTAL }.afterChars)
        assertEquals(system.length, events.filterIsInstance<PromptSizeTrace.Event.Request>().last().systemChars)
    }

    private fun capturePromptMetrics(chat: org.json.JSONObject) {
        // Check the first FULL request received by the local fixture, not just a source-level
        // call site. Publish lengths only, never prompt text, model messages or keys.
        val messages = chat.getJSONArray("messages")
        val system = (0 until messages.length()).map { messages.getJSONObject(it) }
            .single { it.optString("role") == "system" }.getString("content")
        assertTrue(system.contains("\nAndroid-only tools (android-* CLIs):\n"))
        assertEquals("The actual system prompt was not dieted", system, PromptDiet.prompt(system))
        val tools = chat.getJSONArray("tools")
        val browser = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == "browser_use" }
        val expectedBrowser = PromptDiet.tools(AgentTools.makeAgentTools()).single { it.name == "browser_use" }
        assertEquals(expectedBrowser.description, browser.getString("description"))
        val events = synchronized(promptEvents) { promptEvents.toList() }
        val systemEvent = events.filterIsInstance<PromptSizeTrace.Event.System>().first()
        val toolEvent = events.filterIsInstance<PromptSizeTrace.Event.Tools>().first()
        val requestEvent = events.filterIsInstance<PromptSizeTrace.Event.Request>().first()
        val systemTotal = systemEvent.rows.single { it.label == PromptSize.Section.TOTAL }
        val toolTotal = toolEvent.rows.single { it.label == PromptSize.Tool.TOTAL }
        assertEquals("System metric differs from the fixture's actual request", system.length, systemTotal.afterChars)
        assertEquals("Tool metric differs from the fixture's actual request", tools.toString().length, toolTotal.afterChars)
        assertEquals("Request prompt differs from the fixture", system.length, requestEvent.systemChars)
        assertTrue("The base diet did not reduce this fixture", systemTotal.beforeChars > systemTotal.afterChars)
        val browserMetric = toolEvent.rows.single { it.label == PromptSize.Tool.BROWSER_USE }
        assertTrue("The browser diet did not reduce this fixture", browserMetric.beforeChars > browserMetric.afterChars)
        // Publish the FULL request only, not a mixture of FULL/BASIC/LEAN labels.
        val metricLines = listOf(systemEvent, toolEvent, requestEvent).flatMap { event ->
            when (event) {
                is PromptSizeTrace.Event.System -> PromptSize.logLines("system", event.rows) +
                    "v1 image READY ${when (event.imageReady) { true -> 1; false -> 0; null -> -1 }}"
                is PromptSizeTrace.Event.Tools -> PromptSize.logLines("tool", event.rows)
                is PromptSizeTrace.Event.Request -> listOf("v1 request SYSTEM ${event.systemChars}")
            }
        }
        File(context.cacheDir, "tama-prompt-size.log").writeText(
            (metricLines + "v1 wire SYSTEM ${system.length}" + "v1 wire TOOLS ${tools.toString().length}")
                .joinToString("\n", postfix = "\n"),
        )
    }

    private fun capture(name: String) {
        // Compose screenshots omit system windows, so don't let a passing journey conceal
        // an ANR dialog or another window that prevented the Activity from receiving focus.
        try {
            compose.waitUntil(15_000) { compose.activity.hasWindowFocus() }
        } catch (failure: ComposeTimeoutException) {
            // Only the named Pixel Launcher or Messages ANR can be closed in the disposable emulator.
            // The smoke still fails on any recovery; this only lets later checks gather evidence.
            val recovered = runCatching {
                IsolatedSystemAnrRecovery.closeIfRecognized(context, IsolatedSystemAnrRecovery.Phase.JOURNEY)
            }.getOrDefault(false)
            if (!recovered) throw AssertionError("The app window never gained focus; inspect the full-screen diagnostic", failure)
            try {
                compose.waitUntil(15_000) { compose.activity.hasWindowFocus() }
            } catch (stillBlocked: ComposeTimeoutException) {
                throw AssertionError("The app window did not regain focus after closing the system dialog", stillBlocked)
            }
        }
        compose.waitForIdle()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, name).outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }

    @After fun captureLastScreenForDiagnosis() {
        // Remove the in-process, count-only and fixed-event observers even on failure.
        PromptSizeTrace.observer = null
        PromptSizeDetailTrace.observer = null
        // The isolated-emulator guard prevents capturing a person's screen. Unlike the journey
        // previews, this full-display diagnostic deliberately includes Android system dialogs.
        if (InstrumentationRegistry.getArguments().getString("tama_isolated") == "true") {
            runCatching {
                requireIsolatedEmulator()
                IsolatedSystemAnrRecovery.recordNoAttempt(context, IsolatedSystemAnrRecovery.Phase.JOURNEY)
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@runCatching
                File(context.cacheDir, "journey-last-screen.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun onboardingNavigationCareAndRealChatWorkWithALocalFixture() {
        requireIsolatedEmulator()
        // Clear our own scratch evidence before any journey assertion, even on a test re-run.
        listOf("tama-prompt-touch.log", "tama-prompt-size.log").forEach { name ->
            val old = File(context.cacheDir, name)
            check(!old.exists() || old.delete()) { "Could not remove stale isolated test evidence" }
        }
        compose.waitUntil(45_000) { app.subsystemsReady() && app.providerRepository.configLoaded.value }
        assertTrue("The journey requires a fresh provider store", app.providerRepository.config.value.instances.isEmpty())
        assertTrue("The journey requires a fresh chat store", runBlocking { app.chatRepository.dao.listSessions() }.isEmpty())
        assertFalse("The journey requires first-run setup", FirstRunSetup.isDone(context))
        assertFalse(context.getSharedPreferences("tama_ci_journey", Context.MODE_PRIVATE).getBoolean("written", false))

        LocalOpenAiFixture().use { fixture ->
            awaitText(text(R.string.nm_welcome_own_key))
            compose.onNodeWithText(text(R.string.nm_setup_title)).assertIsDisplayed()
            capture("journey-welcome.png")
            compose.onNodeWithText(text(R.string.nm_welcome_own_key)).performClick()

            awaitText("OpenAI / Compatible API")
            compose.onNodeWithText("OpenAI / Compatible API").performClick()
            awaitText(text(R.string.provider_list_api_key))
            compose.onNodeWithText(text(R.string.provider_list_api_key)).performClick()
            compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 3 }
            // The configuration form's fields are label, key and endpoint. The key stays blank:
            // OpenAI-compatible local servers may be keyless, and no actual secret is created.
            compose.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput(fixture.baseUrl)
            compose.onNode(hasText(text(R.string.provider_list_add_provider)) and hasClickAction())
                .performScrollTo().performClick()

            awaitText(text(R.string.nm_useit_not_now))
            compose.onNodeWithText(text(R.string.nm_useit_not_now)).performScrollTo().performClick()
            awaitText(text(R.string.nm_setup_step_models))
            compose.onNodeWithText(text(R.string.nm_setup_continue)).performClick()
            try {
                awaitText(LocalOpenAiFixture.MODEL_NAME, 45_000)
            } catch (failure: Throwable) {
                val config = app.providerRepository.config.value
                val instances = config.instances.map {
                    "type=${it.providerType}, enabled=${it.isEnabled}, fixture=${it.customBaseURL == fixture.baseUrl}"
                }
                val entries = config.modelEntries.map { it.model.id }
                val requests = synchronized(fixture.requests) { fixture.requests.toList() }
                val errors = synchronized(fixture.errors) { fixture.errors.toList() }
                throw AssertionError("Model not visible; instances=$instances; entries=$entries; requests=$requests; errors=$errors", failure)
            }
            compose.onNodeWithText(LocalOpenAiFixture.MODEL_NAME).performClick()
            compose.onNodeWithText(context.getString(R.string.onboarding_done_count, 1)).performClick()
            awaitText(text(R.string.nm_welcome_hands_title))

            // A temporary outage of a local server must not replace its selected model with
            // another local product's catalog merely because both use 127.0.0.1.
            val configured = app.providerRepository.config.value
            val instance = configured.instances.single { it.customBaseURL == fixture.baseUrl }
            val selected = configured.modelEntries.single {
                it.providerInstanceId == instance.id && it.model.id == LocalOpenAiFixture.MODEL_ID
            }
            val groupId = checkNotNull(configured.defaultPrimaryGroupId)
            assertTrue(configured.modelGroups.single { it.id == groupId }.memberEntryIds.contains(selected.id))
            val beforeGets = synchronized(fixture.requests) { fixture.requests.count { it == "GET /v1/models" } }
            fixture.failModelList = true
            try {
                runBlocking { app.providerRepository.refreshModels(instance) }
                val afterGets = synchronized(fixture.requests) { fixture.requests.count { it == "GET /v1/models" } }
                assertTrue("The unavailable endpoint was not queried", afterGets > beforeGets)
                val after = app.providerRepository.config.value
                assertTrue("The selected local model was replaced", after.modelEntries.any { it.id == selected.id })
                assertTrue("The selected group was changed", after.modelGroups.single { it.id == groupId }.memberEntryIds.contains(selected.id))
            } finally {
                fixture.failModelList = false
            }

            compose.onNodeWithText(text(R.string.nm_welcome_skip)).performClick()
            awaitText(text(R.string.nm_setup_start))
            compose.onNodeWithText(text(R.string.nm_setup_start)).performClick()
            awaitDescription(text(R.string.nm_tab_chat), 45_000)
            tab(R.string.nm_tab_chat).assertIsSelected()
            capture("journey-chat-light.png")

            tab(R.string.nm_tab_feed).performClick()
            awaitText(text(R.string.nm_feed_intro_title))
            tab(R.string.nm_tab_feed).assertIsSelected()
            capture("journey-feed.png")
            tab(R.string.nm_tab_ideas).performClick()
            awaitText(text(R.string.nm_ideas_title))
            tab(R.string.nm_tab_ideas).assertIsSelected()
            capture("journey-ideas.png")
            tab(R.string.nm_tab_goals).performClick()
            awaitText(text(R.string.nm_goals_title))
            tab(R.string.nm_tab_goals).assertIsSelected()
            capture("journey-goals.png")
            tab(R.string.nm_tab_library).performClick()
            awaitText(text(R.string.nm_library_artifacts))
            tab(R.string.nm_tab_library).assertIsSelected()
            capture("journey-library.png")
            tab(R.string.nm_tab_chat).performClick()
            tab(R.string.nm_tab_chat).assertIsSelected()

            awaitDescription(text(R.string.tama_pet_title))
            compose.onNodeWithContentDescription(text(R.string.tama_pet_title)).performClick()
            awaitDescription(text(R.string.tama_pet_face_description))
            compose.onNodeWithContentDescription(text(R.string.tama_pet_face_description)).assertIsDisplayed()
            capture("journey-pet.png")
            compose.onNodeWithText(text(R.string.tama_pet_action_feed)).performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(PetCareAction.FEED, PetStore.state.value.memory.lastCare)
                assertTrue(PetStore.state.value.daily.completed(PetMission.FIRST_INTERACTION))
            }
            back()
            awaitDescription(text(R.string.nm_tab_chat))

            // The diagnostic command is local: it must not send anything to the model fixture.
            val postsBeforeMeasure = synchronized(fixture.posts) { fixture.posts.size }
            compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("/prompt-size")
            compose.onNodeWithContentDescription("Send").performClick()
            awaitDescription(text(R.string.nm_prompt_size_details), 45_000)
            // Only fixed event names leave this synthetic test; no report or session data is logged.
            PromptSizeDetailTrace.observer = { event ->
                synchronized(promptDetailEvents) {
                    if (promptDetailEvents.size < 24) promptDetailEvents.add(event)
                }
            }
            val promptTarget = compose.onNodeWithContentDescription(text(R.string.nm_prompt_size_details))
            val boundsBeforeTap = promptTarget.fetchSemanticsNode().boundsInRoot
            promptTarget.assertIsDisplayed().performClick()
            val boundsImmediatelyAfterTap = runCatching { promptTarget.fetchSemanticsNode().boundsInRoot }.getOrNull()
            var promptTouchFailure: AssertionError? = null
            val reportVisible = try {
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasText(text(R.string.nm_prompt_size_tools), substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                true
            } catch (failure: ComposeTimeoutException) {
                // nanoMuse: count fixed UI labels only, never dump the prompt or conversation.
                val close = compose.onAllNodes(hasContentDescription(text(R.string.standard_sheet_close))).fetchSemanticsNodes().size
                val titles = compose.onAllNodes(hasText(text(R.string.nm_prompt_size_title))).fetchSemanticsNodes().size
                val firstStages = synchronized(promptDetailEvents) { promptDetailEvents.joinToString(",") { it.name } }
                val boundsAfterWait = runCatching { promptTarget.fetchSemanticsNode().boundsInRoot }.getOrNull()
                val windowFocused = compose.activity.hasWindowFocus()
                // The previous run showed a displaced target. A second physical touch after
                // the wait is diagnostic only: it can never turn the first failure green.
                val retryAllowed = close == 0 && windowFocused
                val retried = retryAllowed && runCatching {
                    promptTarget.assertIsDisplayed().performClick()
                    compose.waitUntil(15_000) {
                        compose.onAllNodes(hasText(text(R.string.nm_prompt_size_tools), substring = true)).fetchSemanticsNodes().isNotEmpty()
                    }
                }.isSuccess
                val allStages = synchronized(promptDetailEvents) { promptDetailEvents.joinToString(",") { it.name } }
                val retryResult = if (!retryAllowed) "skipped" else if (retried) "opened" else "absent"
                // The shell reads this separately even if a later assertion fails. Only the
                // allowlisted event names and rounded UI bounds leave the isolated emulator.
                File(context.cacheDir, "tama-prompt-touch.log").writeText(listOf(
                    "v1 first ${firstStages.ifEmpty { "none" }}",
                    "v1 second $retryResult",
                    "v1 after ${allStages.ifEmpty { "none" }}",
                    rectEvidence("before", boundsBeforeTap),
                    rectEvidence("immediate", boundsImmediatelyAfterTap),
                    rectEvidence("wait", boundsAfterWait),
                    "v1 focus ${if (windowFocused) "focused" else "unfocused"}",
                ).joinToString("\n", postfix = "\n"))
                promptTouchFailure = AssertionError(
                    "Prompt report did not appear on first touch; close=$close, titles=$titles, " +
                        "firstStages=$firstStages, afterRetryStages=$allStages, secondTouchOpened=$retried, " +
                        "target before=$boundsBeforeTap immediatelyAfter=$boundsImmediatelyAfterTap " +
                        "afterWait=$boundsAfterWait, windowFocus=$windowFocused",
                    failure,
                )
                // An opened modal can take focus from the Activity. If it did not open,
                // stop rather than interact with an unknown sheet or system window.
                if (!retried && (!compose.activity.hasWindowFocus() ||
                        compose.onAllNodes(hasContentDescription(text(R.string.standard_sheet_close))).fetchSemanticsNodes().isNotEmpty())) {
                    throw promptTouchFailure!!
                }
                retried
            }
            if (reportVisible) {
                val detailStages = synchronized(promptDetailEvents) { promptDetailEvents.toList() }
                assertTrue("Prompt detail did not use the expected host", detailStages.containsAll(listOf(
                    PromptSizeDetailTrace.Event.ROW_CLICK,
                    PromptSizeDetailTrace.Event.CHAT_CALLBACK,
                    PromptSizeDetailTrace.Event.OPEN_REQUEST,
                    PromptSizeDetailTrace.Event.SHEET_MOUNT,
                )))
                // Cleanup only: invoke the known app sheet's semantic close, not a touch or
                // Back on a possibly obscuring system window. The first physical click remains
                // the gate; diagnostic recovery can collect data but cannot make it pass.
                compose.onNodeWithContentDescription(text(R.string.standard_sheet_close))
                    .performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasText(text(R.string.nm_prompt_size_tools), substring = true)).fetchSemanticsNodes().isEmpty()
                }
            }
            // Do not type into the chat or send the fixture request behind another window.
            compose.waitUntil(15_000) { compose.activity.hasWindowFocus() }
            assertEquals(postsBeforeMeasure, synchronized(fixture.posts) { fixture.posts.size })

            // nanoMuse: first show a terminal 413 without blindly resending it. A manual retry
            // then encounters a smaller output cap and one bounded 429 before the local answer.
            // Only this disposable loopback service receives the requests; it never charges.
            fixture.failNextPrompt(413, 400, 429)
            // Subscribe only in this disposable, loopback-only run. General logging stays off.
            PromptSizeTrace.observer = { event -> promptEvents.add(event) }
            compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasSetTextAction())[0].performTextInput(LocalOpenAiFixture.PROMPT)
            compose.onNodeWithContentDescription("Send").performClick()
            // nanoMuse: the unchanged oversized request must not be silently resent.
            awaitText(text(R.string.nm_reach_request_large_body), 90_000)
            compose.waitUntil(30_000) {
                compose.onAllNodes(hasContentDescription("Stop")).fetchSemanticsNodes().isEmpty()
            }
            assertEquals("A 413 was sent again without shrinking it", 1,
                synchronized(fixture.promptRequestNanos) { fixture.promptRequestNanos.size })
            // The greeting is LEAN; validate its actual wire shape before the error panel.
            val firstChats = synchronized(fixture.posts) { fixture.posts.filter { isFixtureChat(it) } }
            assertEquals("Expected one fixture chat before the manual retry", 1, firstChats.size)
            assertEquals(LocalOpenAiFixture.MODEL_ID, firstChats.single().optString("model"))
            checkLeanMetrics(firstChats.single())
            compose.onNodeWithText(text(R.string.nm_reach_request_large_body)).performScrollTo().assertIsDisplayed()
            // nanoMuse: the detail sheet must report the model on the failed wire request,
            // not the current picker, and its retry must reach the same guarded action.
            compose.onNodeWithText(text(R.string.nm_reach_details)).performScrollTo().performClick()
            awaitText(text(R.string.nm_reach_sheet_title))
            compose.onNodeWithText("413").assertIsDisplayed()
            compose.onNodeWithText(LocalOpenAiFixture.MODEL_ID).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Request too large").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("provider-error-retry").performScrollTo().performClick()
            // nanoMuse: at least one visible countdown value must accompany the bounded wait.
            compose.waitUntil(90_000) {
                (1..3).any { seconds ->
                    compose.onAllNodes(hasText(context.getString(R.string.nm_reach_rate_wait, seconds)))
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            awaitText(LocalOpenAiFixture.REPLY, 90_000)
            compose.onNodeWithText(LocalOpenAiFixture.REPLY).assertIsDisplayed()
            compose.waitUntil(90_000) {
                val pet = PetStore.state.value
                pet.daily.count(PetMission.CONVERSATION) == 1 && pet.daily.completed(PetMission.TASK)
            }
            val requests = synchronized(fixture.posts) { fixture.posts.toList() }
            // Match the actual user message, not auxiliary title generation.
            val chats = requests.filter { isFixtureChat(it) }
            val summary = requests.map { "${it.optString("model")}:stream=${it.optBoolean("stream")}" }
            assertTrue("The fixture did not receive the person's message; requests=$summary", chats.isNotEmpty())
            assertTrue("Expected streaming chat; requests=$summary", chats.all { it.optBoolean("stream") })
            assertTrue("Unexpected chat model(s); requests=$summary", chats.all {
                it.optString("model") == LocalOpenAiFixture.MODEL_ID
            })
            // nanoMuse: the 413 stays terminal; the manual retry gets one reduced cap,
            // one 429 wait and a successful answer, without resending the first turn blindly.
            assertEquals("Unexpected number of retries", 4, chats.size)
            assertTrue(chats[0].getInt("max_completion_tokens") > 128)
            assertTrue(chats[1].getInt("max_completion_tokens") > 128)
            assertEquals(128, chats[2].getInt("max_completion_tokens"))
            assertEquals(128, chats[3].getInt("max_completion_tokens"))
            val attempts = synchronized(fixture.promptRequestNanos) { fixture.promptRequestNanos.toList() }
            assertEquals(4, attempts.size)
            assertTrue("Retry-After was not respected", attempts[3] - attempts[2] >= 2_900_000_000L)
            awaitText(context.getString(R.string.nm_reach_output_adjusted, 128), 30_000)
            checkLeanMetrics(chats.last()) // recheck the recovered wire request too
            assertTrue("Fixture errors: ${fixture.errors}", fixture.errors.isEmpty())

            // A separate real chat exercises the 413 ladder. Keep the earlier terminal-LEAN
            // and error-panel gates above; this adds rather than replaces wire validation.
            synchronized(promptEvents) { promptEvents.clear() }
            fixture.failPrompt(LocalOpenAiFixture.FULL_PROMPT, 413, 413)
            compose.onAllNodes(hasSetTextAction())[0].performTextInput(LocalOpenAiFixture.FULL_PROMPT)
            compose.onNodeWithContentDescription("Send").performClick()
            compose.waitUntil(90_000) {
                synchronized(fixture.promptRequestNanos) { fixture.promptRequestNanos.size >= 3 } &&
                    compose.onAllNodes(hasContentDescription("Stop")).fetchSemanticsNodes().isEmpty()
            }
            val downsized = synchronized(fixture.posts) {
                fixture.posts.filter { isFixtureChat(it, LocalOpenAiFixture.FULL_PROMPT) }
            }
            assertEquals("Expected FULL, BASIC and LEAN exactly once", 3, downsized.size)
            fun fixedChars(request: org.json.JSONObject): Int {
                val msgs = request.getJSONArray("messages")
                val system = (0 until msgs.length()).map { msgs.getJSONObject(it) }
                    .single { it.optString("role") == "system" }.getString("content")
                return system.length + (request.optJSONArray("tools")?.toString()?.length ?: 0)
            }
            assertTrue("First 413 did not reduce the request", fixedChars(downsized[1]) < fixedChars(downsized[0]))
            assertTrue("Second 413 did not reduce the request", fixedChars(downsized[2]) < fixedChars(downsized[1]))
            val basicTools = downsized[1].getJSONArray("tools")
            assertTrue("BASIC lost the requested browser", (0 until basicTools.length()).any {
                basicTools.getJSONObject(it).getJSONObject("function").optString("name") == "browser_use"
            })
            assertTrue("LEAN retry still sent tools", (downsized[2].optJSONArray("tools")?.length() ?: 0) == 0)
            assertTrue("Retries lost the user's request", downsized.all { isFixtureChat(it, LocalOpenAiFixture.FULL_PROMPT) })
            capturePromptMetrics(downsized.first()) // FULL baseline and the diet must still match the wire
            assertTrue("Fixture errors: ${fixture.errors}", fixture.errors.isEmpty())
            // A later recovery and validated metrics are useful evidence, not a passing journey.
            promptTouchFailure?.let { throw AssertionError("${it.message}; recovery sequence and wire metrics passed", it) }
            val session = checkNotNull(MainChat.persisted(context))
            val rows = runBlocking { app.chatRepository.dao.loadMessages(session) }
            assertTrue("User message was not persisted", rows.any { it.role == "user" && it.partsJson.contains(LocalOpenAiFixture.PROMPT) })
            assertTrue("Assistant reply was not persisted", rows.any { it.role == "assistant" && it.partsJson.contains(LocalOpenAiFixture.REPLY) && it.errorInfo == null })
            assertTrue("Care plus a successful reply should have rewarded the Pet", PetStore.state.value.progress.xp >= 30L)
            capture("journey-chat-reply.png")

            compose.onNodeWithContentDescription(text(R.string.nm_open_drawer)).performClick()
            awaitDescription(text(R.string.nm_drawer_settings))
            compose.onNodeWithContentDescription(text(R.string.nm_drawer_settings)).performClick()
            awaitText(text(R.string.settings_title))
            capture("journey-settings-light.png")
            compose.onNodeWithText(text(R.string.settings_section_appearance)).performScrollTo().performClick()
            awaitText(text(R.string.appearance_title))
            // Both the theme and app-icon sections have a "Dark" label. The theme is a radio row.
            radio(R.string.appearance_theme_dark).performScrollTo().performClick()
            assertEquals(2, context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE).getInt(KEY_THEME_MODE, 0))
            capture("journey-appearance-dark.png")
            radio(R.string.nm_chat_background_blue).performScrollTo().performClick()
            assertEquals("blue", context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE).getString(ChatBackground.KEY, null))
            back()
            awaitText(text(R.string.settings_title))
            back()
            awaitDescription(text(R.string.nm_tab_chat))
            capture("journey-chat-dark-custom.png")

            // Flush prior apply() calls in these same preference files before the external force-stop.
            assertTrue(context.getSharedPreferences("minis_settings", Context.MODE_PRIVATE).edit().putBoolean("tama_ci.flush", true).commit())
            assertTrue(context.getSharedPreferences("nanomuse", Context.MODE_PRIVATE).edit().putBoolean("tama_ci.flush", true).commit())
            assertTrue(context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE).edit().putBoolean("tama_ci.flush", true).commit())
            assertTrue(context.getSharedPreferences("tama_ci_journey", Context.MODE_PRIVATE).edit()
                .putBoolean("written", true)
                .putString("session", session)
                .putLong("xp", PetStore.state.value.progress.xp)
                .putInt("writer_pid", Process.myPid())
                .commit())
        }
    }
}
