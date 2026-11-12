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
    val wardrobe: PetWardrobe = PetWardrobe(),
    val streak: PetStreak = PetStreak(),
    val memory: PetMemory = PetMemory(),
) {
    val mood: PetMood
        get() = PetMood.of(needs, aloneHours = aloneHours)

    /** The outfit actually worn: the choice, when the level allows it. */
    val outfit: PetOutfit get() = wardrobe.wornAt(progress.level)
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
    private const val KEY_OUTFIT = "tama.pet.outfit"
    private const val KEY_STREAK_LAST = "tama.pet.streak_last_day"
    private const val KEY_STREAK_DAYS = "tama.pet.streak_days"
    private const val KEY_STREAK_BEST = "tama.pet.streak_best"
    private const val KEY_FIRST_DAY = "tama.pet.first_day"
    private const val KEY_CARE_COUNT = "tama.pet.care_count"
    private const val KEY_TURNS_TOGETHER = "tama.pet.turns_together"

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
        val day = PetStreak.dayOf(nowMillis)
        val streak = PetStreak.afterVisit(
            PetStreak(
                lastDay = p.getLong(KEY_STREAK_LAST, -1L).takeIf { it >= 0L },
                days = p.getInt(KEY_STREAK_DAYS, 0),
                best = p.getInt(KEY_STREAK_BEST, 0),
            ),
            day,
        )
        val memory = PetMemory(
            careCount = p.getInt(KEY_CARE_COUNT, 0),
            turnsTogether = p.getInt(KEY_TURNS_TOGETHER, 0),
            firstDay = p.getLong(KEY_FIRST_DAY, -1L).takeIf { it >= 0L },
        ).firstSeen(day)
        val state = PetState(
            progress = progress,
            needs = needs,
            aloneHours = elapsed / PetDecay.HOUR_MS,
            wardrobe = PetWardrobe.fromStorage(p.getString(KEY_OUTFIT, null)),
            streak = streak,
            memory = memory,
        )
        _state.value = state
        _pet.value = progress
        // The visit itself is a fact worth keeping: a streak that is not written is not a streak.
        write(context, state, nowMillis)
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
        val after = before.copy(
            progress = progress,
            needs = needs,
            aloneHours = 0,
            memory = before.memory.afterTurn(),
        )
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
        val after = before.copy(
            needs = needs,
            aloneHours = 0,
            memory = before.memory.afterCare(need),
        )
        write(context, after, nowMillis)
        _state.value = after
        return after
    }

    /**
     * Dresses the Pet.
     *
     * The choice is stored even when it is still locked, so a person who picks "legend" at level
     * three finds it still chosen once they get there. Losing a choice is worse than showing one
     * as not yet available.
     */
    @Synchronized
    fun wear(context: Context, outfit: PetOutfit, nowMillis: Long = System.currentTimeMillis()): PetState {
        load(context, nowMillis)
        val before = _state.value
        val after = before.copy(wardrobe = before.wardrobe.choose(outfit))
        write(context, after, nowMillis)
        _state.value = after
        return after
    }

    /**
     * Records that the person is looking at the Pet, so the next load decays from now.
     *
     * It loads first, and that guard is the whole point: without it this wrote the in-memory
     * default (level 1, full needs, no streak) over whatever was on disk whenever the process had
     * not loaded yet, which is every time this is called from a place other than the Pet screen.
     * Decay is derived from the stored stamp, so refreshing the stamp costs nothing and must never
     * cost the person their Pet.
     */
    @Synchronized
    fun touch(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        load(context, nowMillis)
        write(context, _state.value, nowMillis)
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
            .putString(KEY_OUTFIT, state.wardrobe.chosenId)
            .putLong(KEY_STREAK_LAST, state.streak.lastDay ?: -1L)
            .putInt(KEY_STREAK_DAYS, state.streak.days)
            .putInt(KEY_STREAK_BEST, state.streak.best)
            .putLong(KEY_FIRST_DAY, state.memory.firstDay ?: -1L)
            .putInt(KEY_CARE_COUNT, state.memory.careCount)
            .putInt(KEY_TURNS_TOGETHER, state.memory.turnsTogether)
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
