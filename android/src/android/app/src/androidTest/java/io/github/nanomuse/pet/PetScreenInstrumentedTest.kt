package io.github.nanomuse.pet

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.R
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Clean emulator only. Uses a separate preferences file and never sends a model request. */
@RunWith(AndroidJUnit4::class)
class PetScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var context: Context

    @Before fun setUp() {
        name = "pet_ui_ci_${UUID.randomUUID().toString().replace('-', '_')}"
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(if (name == "minis_settings") this@PetScreenInstrumentedTest.name else name, mode)
        }
        PetStore.forgetForTests()
    }

    @After fun tearDown() {
        // The Compose host is disposed after @After. Mark it absent first so its onDispose cannot
        // recreate the temporary preferences after they have been deleted.
        compose.runOnIdle { PetStore.setVisible(context, false) }
        PetStore.forgetForTests()
        target.deleteSharedPreferences(name)
    }

    private fun showPet() {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme { PetScreen(onBack = {}) }
            }
        }
    }

    /** Screenshots are optional diagnostics, never an assertion or a substitute for a phone. */
    private fun capture(filename: String) {
        compose.waitForIdle()
        runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            File(target.cacheDir, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun careActivitiesAndBothGamesWorkFromTheScreen() {
        val startXp = PetLadder.levelStart(5)
        context.getSharedPreferences("minis_settings", Context.MODE_PRIVATE).edit()
            .putLong("tama.pet.xp", startXp).commit()
        PetStore.load(context)
        showPet()
        compose.onNodeWithContentDescription(context.getString(R.string.tama_pet_face_description)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tama_pet_action_feed)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(startXp + 5, PetStore.state.value.progress.xp) }
        capture("pet-alive.png")

        val experiment = context.getString(R.string.nm_pet_activity_cost,
            context.getString(R.string.nm_pet_activity_experiment), PetActivity.EXPERIMENT.energyCost)
        compose.onNodeWithText(experiment).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(startXp + 13, PetStore.state.value.progress.xp)
            assertTrue(PetStore.state.value.daily.completed(PetMission.CREATE))
        }

        compose.onNodeWithText(context.getString(R.string.nm_pet_game_colors)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.nm_pet_game_start)).performClick()
        for (label in listOf(R.string.nm_pet_color_blue, R.string.nm_pet_color_yellow, R.string.nm_pet_color_green)) {
            compose.onNodeWithText(context.getString(label)).performClick()
        }
        compose.runOnIdle { assertEquals(startXp + 18, PetStore.state.value.progress.xp) }

        compose.onNodeWithText(context.getString(R.string.nm_pet_game_numbers)).performScrollTo().performClick()
        compose.onNodeWithText("8").performClick()
        compose.runOnIdle {
            assertEquals(startXp + 18, PetStore.state.value.progress.xp)
            assertTrue(PetStore.state.value.daily.completed(PetMission.MINIGAME))
        }
    }

    @Test fun memorialHasNoCareControlsAndTheNextGenerationStartsFresh() {
        val now = System.currentTimeMillis()
        context.getSharedPreferences("minis_settings", Context.MODE_PRIVATE).edit()
            .putLong("tama.pet.xp", 450L)
            .putLong("tama.pet.born_at_v1", now - 100 * PetDecay.HOUR_MS)
            .putLong("tama.pet.last_fed_v1", now - PetLifeRules.DEATH_AFTER_MS - PetDecay.HOUR_MS)
            .commit()
        PetStore.load(context, now)
        assertTrue(PetStore.state.value.life.grieving)
        showPet()
        compose.onNodeWithContentDescription(context.getString(R.string.nm_pet_ghost)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tama_pet_action_feed)).assertDoesNotExist()
        capture("pet-memorial.png")

        compose.runOnIdle { PetStore.load(context, now + PetLifeRules.GRIEF_MS) }
        compose.onNodeWithText(context.getString(R.string.tama_pet_action_feed)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2L, PetStore.state.value.life.generation)
            assertEquals(0L, PetStore.state.value.progress.xp)
            assertEquals(450L, PetStore.state.value.archives.single().progress.xp)
        }
    }
}
