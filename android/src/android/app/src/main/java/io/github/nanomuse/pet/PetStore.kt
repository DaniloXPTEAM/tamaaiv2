package io.github.nanomuse.pet

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the Pet's numbers live.
 *
 * `android.content.SharedPreferences` under the app's existing settings file — **not** a second
 * database, scheduler or memory: the app already persists small counters this way, and a Pet that
 * grew its own store would be a second source of truth for state that belongs to the app.
 *
 * Writes are synchronous and small (two longs), so the app can be killed right after a turn
 * without losing the XP it just earned. [pet] is the only thing the UI observes.
 */
object PetStore {
    private const val PREFS = "minis_settings"
    private const val KEY_XP = "tama.pet.xp"
    private const val KEY_LEVEL = "tama.pet.level"

    private val _pet = MutableStateFlow(PetLadder.progressFor(0))
    val pet: StateFlow<PetProgress> = _pet

    @Volatile private var loaded = false

    /** Reads the stored total once. Safe to call from any thread and more than once. */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val xp = prefs.getLong(KEY_XP, 0L)
        _pet.value = PetLadder.progressFor(xp)
        loaded = true
    }

    /** XP as stored, without needing a Context. Zero before [load] has run. */
    fun currentXp(): Long = _pet.value.xp

    /**
     * Awards XP for one finished turn.
     *
     * Idempotence is the caller's job (see `PetTurnLedger`): calling this twice for the same turn
     * pays twice, which is why the only caller is the ledger and not the UI.
     */
    @Synchronized
    fun award(context: Context, outcome: PetTurnOutcome): PetProgress {
        load(context)
        val before = _pet.value
        val xp = PetXpRules.apply(before.xp, PetXpRules.forOutcome(outcome))
        val after = PetLadder.progressFor(xp)
        if (after.xp != before.xp) {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_XP, after.xp)
                .putInt(KEY_LEVEL, after.level)
                .apply()
            _pet.value = after
            if (after.level > before.level) {
                AppLogger.info(TAG, "pet levelled up: ${before.level} -> ${after.level} (xp ${after.xp})")
            }
        }
        return after
    }

    /** Restores a backed-up total (the app's own backup/restore path calls this). */
    @Synchronized
    fun restore(context: Context, xp: Long) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_XP, xp.coerceAtLeast(0L))
            .apply()
        loaded = false
        load(context)
    }

    /** Test seam: forget the stored value the way a fresh process would. */
    @Synchronized
    fun forgetForTests() {
        loaded = false
        _pet.value = PetLadder.progressFor(0)
    }

    private const val TAG = "PetStore"
}
