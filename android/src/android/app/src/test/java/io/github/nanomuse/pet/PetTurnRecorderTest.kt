package io.github.nanomuse.pet

import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Exercises the actual recorder and store with map-backed preferences, not Android disk I/O. */
class PetTurnRecorderTest {
    private lateinit var context: PetPrefsContext
    private lateinit var oldZone: TimeZone
    private val at = 1_704_110_400_000L

    @Before fun setUp() {
        oldZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = PetPrefsContext()
        PetStore.forgetForTests()
    }

    @After fun tearDown() {
        PetStore.forgetForTests()
        TimeZone.setDefault(oldZone)
    }

    private fun finish(id: String, outcome: PetTurnOutcome, time: Long = at): PetState =
        PetTurnRecorder.onTurnFinished(context, "session-1", id, outcome, time)

    @Test fun successFailureAndCancellationHaveDistinctEffects() {
        val success = finish("a", PetTurnOutcome.SUCCESS)
        assertEquals(25L, success.progress.xp)
        val failure = finish("b", PetTurnOutcome.FAILURE)
        assertEquals(28L, failure.progress.xp)
        assertEquals(2, failure.memory.turnsTogether)
        val cancelled = finish("c", PetTurnOutcome.CANCELLED)
        val unknown = finish("d", PetTurnOutcome.UNKNOWN)
        assertEquals(failure, cancelled)
        assertEquals(failure, unknown)
        assertEquals(2, context.petPrefs.getInt("tama.pet.turns_today", -1))
    }

    @Test fun duplicateIsRejectedAfterAnotherRunAndAfterRestart() {
        finish("a", PetTurnOutcome.SUCCESS, at)
        finish("b", PetTurnOutcome.FAILURE, at + 1)
        assertEquals(28L, finish("a", PetTurnOutcome.SUCCESS, at + 2).progress.xp)
        PetStore.forgetForTests()
        assertEquals(28L, finish("a", PetTurnOutcome.SUCCESS, at + 3).progress.xp)
        assertEquals(38L, finish("c", PetTurnOutcome.SUCCESS, at + 4).progress.xp)
    }

    @Test fun distinctRunsEndingInTheSameMillisecondBothCount() {
        finish("run-1", PetTurnOutcome.SUCCESS, at)
        assertEquals(35L, finish("run-2", PetTurnOutcome.SUCCESS, at).progress.xp)
    }

    @Test fun invalidSessionOrRunIdIsNotPaid() {
        PetTurnRecorder.onTurnFinished(context, "", "a", PetTurnOutcome.SUCCESS, at)
        finish("", PetTurnOutcome.SUCCESS)
        finish("bad id", PetTurnOutcome.SUCCESS)
        assertEquals(0L, PetStore.state.value.progress.xp)
    }

    @Test fun cancelDoesNotSpendTheDailyAllowanceAndSeventhCompletedRunCostsEnergy() {
        PetStore.setVisible(context, true, at)
        repeat(6) { finish("ok-$it", PetTurnOutcome.SUCCESS) }
        assertEquals(80, PetStore.state.value.needs.energy)
        repeat(5) { finish("cancel-$it", PetTurnOutcome.CANCELLED) }
        assertEquals(6, context.petPrefs.getInt("tama.pet.turns_today", -1))
        assertEquals(75, finish("seventh", PetTurnOutcome.SUCCESS).needs.energy)
        finish("tomorrow", PetTurnOutcome.SUCCESS, at + PetStreak.DAY_MS)
        assertEquals(75, PetStore.state.value.needs.energy)
        assertEquals(1, context.petPrefs.getInt("tama.pet.turns_today", -1))
    }

    @Test fun paymentAndIdArePartOfTheSamePreferencesEdit() {
        PetStore.load(context, at)
        context.petPrefs.edits.clear()
        finish("atomic", PetTurnOutcome.SUCCESS)
        val finalEdit = context.petPrefs.edits.last()
        assertTrue(finalEdit.containsAll(listOf(
            "tama.pet.xp", "tama.pet.satiety", "tama.pet.turns_together",
            "tama.pet.recent_turns_v1", "tama.pet.day", "tama.pet.turns_today",
        )))
    }

    @Test fun concurrentDuplicateReportsPayOnlyOnce() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 24).map {
                pool.submit {
                    start.await()
                    finish("same-run", PetTurnOutcome.SUCCESS)
                }
            }
            start.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(25L, PetStore.state.value.progress.xp)
            assertEquals(1, PetStore.state.value.memory.turnsTogether)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun onlyActualOutcomesReachLiveReactions() = runBlocking {
        val outcomes = mutableListOf<PetTurnOutcome>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            PetTurnRecorder.reactions.take(3).toList(outcomes)
        }
        finish("a", PetTurnOutcome.SUCCESS)
        finish("a", PetTurnOutcome.SUCCESS) // not emitted again
        finish("b", PetTurnOutcome.FAILURE)
        finish("c", PetTurnOutcome.CANCELLED)
        withTimeout(2_000) { observer.join() }
        assertEquals(listOf(PetTurnOutcome.SUCCESS, PetTurnOutcome.FAILURE, PetTurnOutcome.CANCELLED), outcomes)
    }
}
