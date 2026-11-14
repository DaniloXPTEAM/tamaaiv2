package io.github.nanomuse.isolated

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run in a separate process after the script force-stopped the app, without clearing any data. */
@RunWith(AndroidJUnit4::class)
class AfterProcessStopInstrumentedTest {
    companion object {
        // Runs before the Compose rule creates the Activity, even if invoked outside CI.
        @BeforeClass @JvmStatic fun checkIsolation() = requireIsolatedEmulator()
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun chatPetAndAppearanceSurviveAnActualProcessStop() {
        requireIsolatedEmulator()
        val marker = context.getSharedPreferences("tama_ci_journey", Context.MODE_PRIVATE)
        assertTrue("The previous journey must have completed", marker.getBoolean("written", false))
        val previousPid = marker.getInt("writer_pid", -1)
        assertTrue(previousPid > 0)
        assertNotEquals("This must be a new app process", previousPid, Process.myPid())
        val session = checkNotNull(marker.getString("session", null))
        val app = context.applicationContext as MinisApp
        compose.waitUntil(45_000) { app.subsystemsReady() && app.providerRepository.configLoaded.value }
        compose.waitUntil(45_000) {
            compose.onAllNodes(hasContentDescription(context.getString(R.string.nm_tab_chat))).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(session, MainChat.persisted(context))
        val rows = runBlocking { app.chatRepository.dao.loadMessages(session) }
        assertTrue(rows.any { it.role == "user" && it.partsJson.contains(LocalOpenAiFixture.PROMPT) })
        assertTrue(rows.any { it.role == "assistant" && it.partsJson.contains(LocalOpenAiFixture.REPLY) && it.errorInfo == null })
        // The stored answer must also be rendered by the relaunched Activity, not just in SQL.
        compose.waitUntil(45_000) {
            compose.onAllNodes(hasText(LocalOpenAiFixture.REPLY)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(LocalOpenAiFixture.REPLY).assertIsDisplayed()
        PetStore.load(context)
        assertEquals(marker.getLong("xp", -1L), PetStore.state.value.progress.xp)
        assertEquals(PetCareAction.FEED, PetStore.state.value.memory.lastCare)
        assertEquals(1, PetStore.state.value.daily.count(PetMission.CONVERSATION))
        assertTrue(PetStore.state.value.daily.completed(PetMission.TASK))
        val appearance = context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)
        assertEquals(2, appearance.getInt(KEY_THEME_MODE, 0))
        assertEquals("blue", appearance.getString(ChatBackground.KEY, null))
        assertTrue(app.providerRepository.config.value.instances.any { it.customBaseURL?.startsWith("http://127.0.0.1:") == true })
        File(context.cacheDir, "journey-process.txt").writeText("writer_pid=$previousPid\nrestored_pid=${Process.myPid()}\nchat_rows=${rows.size}\npet_xp=${PetStore.state.value.progress.xp}\ntheme=dark\nchat_background=blue\n")
        compose.waitForIdle()
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(context.cacheDir, "journey-restored.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }
}
