package io.github.nanomuse.pet

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionBadgeStore
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * For a clean emulator only: uses a uniquely named preferences file and no real foreground service.
 * It exercises Android SharedPreferences and tracker wiring. `forgetForTests` simulates an
 * in-process store reload; it does not prove recovery after an actual process/device kill.
 */
@RunWith(AndroidJUnit4::class)
class PetPersistenceInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var context: Context
    private lateinit var sessionId: String
    private var trackerInitialized = false

    @Before fun setUp() {
        name = "pet_ci_${UUID.randomUUID().toString().replace('-', '_')}"
        sessionId = "pet-ci-${UUID.randomUUID()}"
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(if (name == "minis_settings") this@PetPersistenceInstrumentedTest.name else name, mode)
            override fun startForegroundService(service: Intent): ComponentName? = null
            override fun startService(service: Intent): ComponentName? = null
            override fun stopService(service: Intent): Boolean = true
        }
        PetStore.forgetForTests()
    }

    @After fun tearDown() {
        if (trackerInitialized) {
            SessionActivityTracker.setInactive(sessionId)
            SessionBadgeStore.replies.opened(sessionId)
            SessionActivityTracker.init(target)
        }
        PetStore.forgetForTests()
        target.deleteSharedPreferences(name)
    }

    @Test fun realPreferencesKeepTheExistingPetAndLastCare() {
        val now = System.currentTimeMillis()
        val p = context.getSharedPreferences("minis_settings", Context.MODE_PRIVATE)
        p.edit()
            .putLong("tama.pet.xp", 450L)
            .putLong("tama.pet.last_seen", now - 2 * PetDecay.HOUR_MS)
            .putInt("tama.pet.energy", 80)
            .putString("tama.pet.outfit", "moth")
            .putString("other.setting", "untouched")
            .commit()
        PetStore.load(context, now)
        assertEquals(450L, PetStore.state.value.progress.xp)
        assertEquals(68, PetStore.state.value.needs.energy)
        assertEquals("moth", PetStore.state.value.outfit.id)
        PetStore.care(context, PetCareAction.PLAY, now)
        // commit waits for prior apply writes before writing this marker.
        assertTrue(p.edit().putBoolean("test.flush", true).commit())
        val xml = File(target.applicationInfo.dataDir, "shared_prefs/$name.xml").readText()
        assertTrue(xml.contains("tama.pet.last_care"))
        assertTrue(xml.contains("PLAY"))
        PetStore.forgetForTests()
        PetStore.load(context, now)
        assertEquals(PetCareAction.PLAY, PetStore.state.value.memory.lastCare)
        assertEquals(455L, PetStore.state.value.progress.xp)
        assertEquals("untouched", p.getString("other.setting", null))
    }

    @Test fun outcomesAndReorderedDuplicatesUseActualAndroidPreferences() {
        val now = System.currentTimeMillis()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val c = UUID.randomUUID().toString()
        PetTurnRecorder.onTurnFinished(context, sessionId, a, PetTurnOutcome.SUCCESS, now)
        PetTurnRecorder.onTurnFinished(context, sessionId, b, PetTurnOutcome.FAILURE, now)
        PetTurnRecorder.onTurnFinished(context, sessionId, c, PetTurnOutcome.CANCELLED, now)
        PetStore.forgetForTests()
        val after = PetTurnRecorder.onTurnFinished(context, sessionId, a, PetTurnOutcome.SUCCESS, now + 1)
        assertEquals(28L, after.progress.xp)
        assertEquals(2, after.memory.turnsTogether)
    }

    @Test fun overlappingStartsAndStopAfterErrorDoNotPayTheWrongRun() {
        trackerInitialized = true
        SessionActivityTracker.init(context)
        SessionActivityTracker.setCompletionListener(null)
        val old = SessionActivityTracker.setActive(sessionId)
        SessionActivityTracker.markStreamError(sessionId, old)
        val current = SessionActivityTracker.setActive(sessionId)
        assertFalse(old == current)
        SessionActivityTracker.setInactive(sessionId, old)
        assertTrue(SessionActivityTracker.isCurrentRun(sessionId, current))
        assertEquals(-1L, SessionActivityTracker.petGenerationForRun(sessionId, old))
        assertEquals(1L, SessionActivityTracker.petGenerationForRun(sessionId, current))
        SessionActivityTracker.setInactive(sessionId, current)
        assertEquals(25L, PetStore.state.value.progress.xp)

        val stopped = SessionActivityTracker.setActive(sessionId)
        SessionActivityTracker.markStreamError(sessionId, stopped)
        SessionActivityTracker.markReplyIncomplete(sessionId, stopped)
        SessionActivityTracker.setInactive(sessionId, stopped)
        assertEquals(25L, PetStore.state.value.progress.xp)
    }

    @Test fun lateTrackerCompletionDoesNotEndANewerRun() {
        trackerInitialized = true
        SessionActivityTracker.init(context)
        // The emulator-only test should not post a completion notification for its fake session.
        SessionActivityTracker.setCompletionListener(null)
        val old = SessionActivityTracker.setActive(sessionId)
        SessionActivityTracker.markReplyIncomplete(sessionId, old)
        SessionActivityTracker.setInactive(sessionId, old)
        assertEquals(0L, PetStore.state.value.progress.xp)

        val current = SessionActivityTracker.setActive(sessionId)
        SessionActivityTracker.markStreamError(sessionId, old) // stale failure must be ignored
        SessionActivityTracker.setInactive(sessionId, old)
        assertTrue(SessionActivityTracker.isCurrentRun(sessionId, current))
        SessionActivityTracker.setInactive(sessionId, current)
        assertEquals(25L, PetStore.state.value.progress.xp)
        assertFalse(SessionActivityTracker.isActive(sessionId))

        val failed = SessionActivityTracker.setActive(sessionId)
        SessionActivityTracker.markStreamError(sessionId, failed)
        SessionActivityTracker.setInactive(sessionId, failed)
        assertEquals(28L, PetStore.state.value.progress.xp)
    }

    @Test fun realPreferencesKeepLegacyXpThroughGriefAndArchiveTheGeneration() {
        val now = System.currentTimeMillis()
        val p = context.getSharedPreferences("minis_settings", Context.MODE_PRIVATE)
        p.edit().putLong("tama.pet.xp", 450L)
            .putLong("tama.pet.last_seen", now - 30 * PetStreak.DAY_MS).commit()
        PetStore.load(context, now)
        assertEquals(450L, PetStore.state.value.progress.xp)
        assertFalse(PetStore.state.value.life.grieving)
        val death = now + PetLifeRules.DEATH_AFTER_MS
        val grieving = PetStore.care(context, PetCareAction.FEED, death)
        assertTrue(grieving.life.grieving)
        assertEquals(450L, grieving.progress.xp)
        PetStore.forgetForTests()
        PetStore.load(context, death + PetLifeRules.GRIEF_MS - 1)
        assertTrue(PetStore.state.value.life.grieving)
        PetStore.load(context, death + PetLifeRules.GRIEF_MS)
        val next = PetStore.state.value
        assertFalse(next.life.grieving)
        assertEquals(2L, next.life.generation)
        assertEquals(0L, next.progress.xp)
        assertEquals(450L, next.archives.single().progress.xp)
        PetStore.forgetForTests()
        PetStore.load(context, death + PetLifeRules.GRIEF_MS + 1)
        assertEquals(450L, PetStore.state.value.archives.single().progress.xp)
        assertEquals(0L, PetStore.state.value.progress.xp)
    }

    @Test fun trackerRunAndQueuedSignalsCannotCreditTheirSuccessor() {
        val now = System.currentTimeMillis()
        trackerInitialized = true
        SessionActivityTracker.init(context)
        SessionActivityTracker.setCompletionListener(null)
        PetStore.load(context, now)
        val run = SessionActivityTracker.setActive(sessionId)
        val oldGeneration = SessionActivityTracker.petGenerationForRun(sessionId, run)
        val prompts = listOf(PetQueuedOrigin("person", oldGeneration), PetQueuedOrigin("routine", null))
        PetEvents.queuedChats(context, "saved-row", prompts)
        assertEquals(5L, PetStore.state.value.progress.xp)
        PetEvents.queuedChats(context, "saved-row", prompts)
        assertEquals(5L, PetStore.state.value.progress.xp)

        PetStore.load(context, now + PetLifeRules.DEATH_AFTER_MS + PetLifeRules.GRIEF_MS)
        assertEquals(2L, PetStore.state.value.life.generation)
        SessionActivityTracker.setInactive(sessionId, run)
        PetEvents.queuedChats(context, "another-row", prompts)
        PetEvents.goal(context, "old-goal", oldGeneration)
        PetEvents.skill(context, "old-skill", oldGeneration)
        PetEvents.tool(context, sessionId, "old-tool", "file_write", oldGeneration)
        assertEquals(0L, PetStore.state.value.progress.xp)
        assertEquals(5L, PetStore.state.value.archives.single().progress.xp)
    }

    @Test fun realPreferencesKeepTheSharedCapAndEventDeduplication() {
        val now = System.currentTimeMillis()
        PetStore.load(context, now)
        repeat(9) { i ->
            PetTurnRecorder.onTurnFinished(context, sessionId, "run-$i", PetTurnOutcome.SUCCESS, now)
        }
        assertEquals(100L, PetStore.state.value.progress.xp)
        PetStore.recordEvent(context, PetEvent.GOAL, "goal-id", now)
        assertEquals(100L, PetStore.state.value.progress.xp)
        assertTrue(PetStore.state.value.daily.completed(PetMission.GOAL))
        PetStore.forgetForTests()
        PetStore.recordEvent(context, PetEvent.GOAL, "goal-id", now)
        assertEquals(100, PetStore.state.value.daily.earned)
        assertEquals(100L, PetStore.state.value.progress.xp)
    }
}
