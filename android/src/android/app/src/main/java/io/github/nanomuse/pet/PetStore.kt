package io.github.nanomuse.pet

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Pet's state as one value: what it has earned and what it currently needs.
 *
 * [stillnessHours] is how long the person has been away, which the mood reads; it is derived from
 * the stored visit stamp on every load, never stored as a timer that could drift.
 */
data class PetState(
    val progress: PetProgress,
    val needs: PetNeeds,
    val aloneHours: Long,
) {
    val mood: PetMood
        get() = PetMood.of(needs, aloneHours = aloneHours)
}

/**
 * Where the Pet's numbers live.
 *
 * `SharedPreferences` under the app's existing settings file — **not** a second database,
 * scheduler or memory: the app already persists small counters this way, and a Pet that grew its
 * own store would be a second source of truth for state that belongs to the app.
 *
 * Decay is computed on read, from the last visit stamp, rather than by a background job. That is
 * the important choice: nothing has to run while the app is closed, so there is no wakeup, no
 * battery cost and no scheduler to keep alive in a sandbox-hostile OS. The Pet still "lives"
 * between visits because the passage of time is recovered from two timestamps.
 */
object PetStore {
    private const val PREFS = "minis_settings"
    private const val KEY_XP = "tama.pet.xp"
    private const val KEY_LEVEL = "tama.pet.level"
    private const val KEY_LAST_SEEN = "tama.pet.last_seen"
    private const val KEY_SATIETY = "tama.pet.satiety"
    private const val KEY_ENERGY = "tama.pet.energy"
    private const val KEY_MOOD = "tama.pet.mood"
    private const val KEY_BOND = "tama.pet.bond"

    private val _state = MutableStateFlow(
        PetState(
            progress = PetLadder.progressFor(0),
            needs = PetNeeds(),
            aloneHours = 0,
        ),
    )
    val state: StateFlow<PetState> = _state

    private val _pet = MutableStateFlow(PetLadder.progressFor(0))

    /** Just the progress, for callers that only want the level. */
    val pet: StateFlow<PetProgress> = _pet

    @Volatile private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Reads the stored state once and applies the decay that happened while the app was closed.
     *
     * Safe to call from any thread and more than once: the second call is a no-op, so an
     * accidental double call cannot decay the Pet twice.
     */
    @Synchronized
    fun load(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        if (loaded) return
        val p = prefs(context)
        val progress = PetLadder.progressFor(p.getLong(KEY_XP, 0L))
        val stored = PetNeeds(
            satiety = p.getInt(KEY_SATIETY, PetNeeds.HIGH),
            energy = p.getInt(KEY_ENERGY, PetNeeds.HIGH),
            mood = p.getInt(KEY_MOOD, PetNeeds.HIGH),
            bond = p.getInt(KEY_BOND, PetNeeds.HIGH),
        )
        val lastSeen = p.getLong(KEY_LAST_SEEN, 0L)
        // A stamp in the future (a clock that moved back, a restored backup) must not "gain"
        // time: treat it as now rather than as a negative absence.
        val elapsed = if (lastSeen <= 0L || lastSeen > nowMillis) 0L else nowMillis - lastSeen
        val needs = PetDecay.after(stored, elapsed)
        _state.value = PetState(progress, needs, aloneHours = elapsed / PetDecay.HOUR_MS)
        _pet.value = progress
        loaded = true
    }

    fun currentXp(): Long = _state.value.progress.xp

    /** Awards XP for one finished turn and applies what the work did to the needs. */
    @Synchronized
    fun award(
        context: Context,
        outcome: PetTurnOutcome,
        turnsToday: Int = 0,
        nowMillis: Long = System.currentTimeMillis(),
    ): PetState {
        load(context, nowMillis)
        val before = _state.value
        val xp = PetXpRules.apply(before.progress.xp, PetXpRules.forOutcome(outcome))
        val progress = PetLadder.progressFor(xp)
        val needs = PetCare.afterTurn(before.needs, outcome, turnsToday)
        val after = before.copy(progress = progress, needs = needs, aloneHours = 0)
        if (after != before) {
            write(context, after, nowMillis)
            _state.value = after
            _pet.value = progress
            if (progress.level > before.progress.level) {
                AppLogger.info(TAG, "pet levelled up: ${before.progress.level} -> ${progress.level}")
            }
        }
        return after
    }

    /** Feeding, resting and playing: the three things the person can do directly. */
    @Synchronized
    fun care(
        context: Context,
        need: PetCareAction,
        nowMillis: Long = System.currentTimeMillis(),
    ): PetState {
        load(context, nowMillis)
        val before = _state.value
        val needs = when (need) {
            PetCareAction.FEED -> PetCare.feed(before.needs)
            PetCareAction.REST -> PetCare.rest(before.needs)
            PetCareAction.PLAY -> PetCare.play(before.needs)
        }
        val after = before.copy(needs = needs, aloneHours = 0)
        write(context, after, nowMillis)
        _state.value = after
        return after
    }

    /** Records that the person was looking at the Pet, so the next load decays from now. */
    @Synchronized
    fun touch(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val current = _state.value
        write(context, current, nowMillis)
    }

    private fun write(context: Context, state: PetState, nowMillis: Long) {
        prefs(context).edit()
            .putLong(KEY_XP, state.progress.xp)
            .putInt(KEY_LEVEL, state.progress.level)
            .putInt(KEY_SATIETY, state.needs.satiety)
            .putInt(KEY_ENERGY, state.needs.energy)
            .putInt(KEY_MOOD, state.needs.mood)
            .putInt(KEY_BOND, state.needs.bond)
            .putLong(KEY_LAST_SEEN, nowMillis)
            .apply()
    }

    /** Restores a backed-up total (the app's own backup/restore path calls this). */
    @Synchronized
    fun restore(context: Context, xp: Long, needs: PetNeeds? = null) {
        prefs(context).edit()
            .putLong(KEY_XP, xp.coerceAtLeast(0L))
            .apply()
        needs?.let { n ->
            prefs(context).edit()
                .putInt(KEY_SATIETY, n.satiety)
                .putInt(KEY_ENERGY, n.energy)
                .putInt(KEY_MOOD, n.mood)
                .putInt(KEY_BOND, n.bond)
                .apply()
        }
        loaded = false
        load(context)
    }

    /** Test seam: forget the stored value the way a fresh process would. */
    @Synchronized
    fun forgetForTests() {
        loaded = false
        _state.value = PetState(PetLadder.progressFor(0), PetNeeds(), 0)
        _pet.value = PetLadder.progressFor(0)
    }

    private const val TAG = "PetStore"
}

/** The three direct actions on the Pet. */
enum class PetCareAction { FEED, REST, PLAY }
