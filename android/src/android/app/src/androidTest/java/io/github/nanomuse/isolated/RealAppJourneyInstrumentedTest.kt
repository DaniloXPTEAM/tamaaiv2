package io.github.nanomuse.isolated

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MainActivity
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.PREF_APPEARANCE
import io.github.nanomuse.home.MainChat
import io.github.nanomuse.pet.PetCareAction
import io.github.nanomuse.pet.PetMission
import io.github.nanomuse.pet.PetStore
import io.github.nanomuse.ui.chat.ChatBackground
import io.github.nanomuse.ui.onboarding.FirstRunSetup
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.cacheDir, name).outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }

    @After fun captureLastScreenForDiagnosis() {
        // The script verifies a fresh emulator before enabling this test. Never capture a phone.
        if (InstrumentationRegistry.getArguments().getString("tama_isolated") == "true") {
            runCatching { requireIsolatedEmulator(); capture("journey-last-screen.png") }
        }
    }

    @Test fun onboardingNavigationCareAndRealChatWorkWithALocalFixture() {
        requireIsolatedEmulator()
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
            awaitText(LocalOpenAiFixture.MODEL_NAME, 45_000)
            compose.onNodeWithText(LocalOpenAiFixture.MODEL_NAME).performClick()
            compose.onNodeWithText(context.getString(R.string.onboarding_done_count, 1)).performClick()
            awaitText(text(R.string.nm_welcome_hands_title))
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

            compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasSetTextAction())[0].performTextInput(LocalOpenAiFixture.PROMPT)
            compose.onNodeWithContentDescription("Send").performClick()
            awaitText(LocalOpenAiFixture.REPLY, 90_000)
            compose.onNodeWithText(LocalOpenAiFixture.REPLY).assertIsDisplayed()
            compose.waitUntil(90_000) {
                val pet = PetStore.state.value
                pet.daily.count(PetMission.CONVERSATION) == 1 && pet.daily.completed(PetMission.TASK)
            }
            val requests = synchronized(fixture.posts) { fixture.posts.toList() }
            val request = requests.firstOrNull { it.optJSONArray("messages")?.toString()?.contains(LocalOpenAiFixture.PROMPT) == true }
            assertNotNull("The fixture did not receive the person's message", request)
            assertEquals(LocalOpenAiFixture.MODEL_ID, request!!.getString("model"))
            assertTrue("Expected Chat Completions streaming", request.optBoolean("stream"))
            assertTrue("Fixture errors: ${fixture.errors}", fixture.errors.isEmpty())
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
