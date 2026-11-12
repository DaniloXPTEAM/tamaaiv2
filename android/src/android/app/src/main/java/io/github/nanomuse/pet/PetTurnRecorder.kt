package io.github.nanomuse.pet

import android.content.Context
import com.openminis.app.logging.AppLogger

/**
 * The one place a finished turn becomes XP.
 *
 * Split from [PetStore] so the two hazards stay separable and each is testable on its own:
 * [PetTurnLedger] decides *whether* a turn is owed a payment, [PetStore] writes the number. This
 * object only wires them and remembers the last paid turn on disk.
 *
 * The turn id is synthetic — `sessionId` plus the moment the turn ended — because the app reports
 * completion by session, not by assistant message, at this seam. The clock makes two turns in the
 * same session distinct; the ledger is what stops a re-entered report of the same turn from paying
 * twice. `System.currentTimeMillis()` is only used as an identity string, never for a duration, so
 * a clock adjustment cannot produce a negative or a skipped award.
 */
object PetTurnRecorder {
    private const val PREFS = "minis_settings"
    private const val KEY_LAST_TURN = "tama.pet.last_turn"

    @Volatile private var lastKey: PetTurnLedger.TurnKey? = null
    @Volatile private var loaded = false

    @Synchronized
    private fun loadLastTurn(context: Context) {
        if (loaded) return
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_TURN, null)
        lastKey = PetTurnLedger.TurnKey.fromStorage(raw)
        loaded = true
    }

    /**
     * Records a finished turn and awards what it is worth.
     *
     * Safe to call more than once for the same turn: the second call finds the same key and
     * awards nothing. Returns the resulting progress so a caller can react to a level-up.
     */
    @Synchronized
    fun onTurnFinished(
        context: Context,
        sessionId: String,
        outcome: PetTurnOutcome,
        nowMillis: Long = System.currentTimeMillis(),
    ): PetProgress {
        if (sessionId.isBlank()) return PetStore.pet.value
        loadLastTurn(context)
        PetStore.load(context)

        val turn = PetTurnLedger.TurnKey(sessionId, nowMillis.toString())
        if (!PetTurnLedger.shouldPay(turn, lastKey)) {
            AppLogger.debug(TAG, "turn already paid, skipping: $sessionId")
            return PetStore.pet.value
        }

        val before = PetStore.pet.value
        val after = PetStore.award(context, outcome)
        PetTurnLedger.nextStored(turn)?.let { stored ->
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_TURN, stored)
                .apply()
            lastKey = turn
        }
        if (after.xp != before.xp) {
            AppLogger.debug(TAG, "turn paid: $sessionId $outcome +${after.xp - before.xp} xp")
        }
        return after
    }

    /** Test seam: forget both the in-memory and the stored last turn. */
    @Synchronized
    fun forgetForTests() {
        lastKey = null
        loaded = false
    }

    private const val TAG = "PetTurnRecorder"
}
