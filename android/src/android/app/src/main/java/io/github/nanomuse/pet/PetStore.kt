package io.github.nanomuse.pet

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The Pet's earned progress, needs and small local memories. */
data class PetState(
    val progress: PetProgress,
    val needs: PetNeeds,
    val aloneHours: Long,
    val wardrobe: PetWardrobe = PetWardrobe(),
    val streak: PetStreak = PetStreak(),
    val memory: PetMemory = PetMemory(),
) {
    val mood: PetMood get() = PetMood.of(needs, aloneHours = aloneHours)
    val outfit: PetOutfit get() = wardrobe.wornAt(progress.level)
}

/**
 * Pet state in the existing `minis_settings` preferences. No scheduler or second database is
 * required: elapsed time is reconciled on each visit or action, including a return while the
 * process remains alive. Partial hours are retained across visits and process restarts.
 *
 * All mutations use the same lock. A completed run's identifier, XP, needs and daily count are
 * written in one preferences edit; Android applies that edit atomically in memory and schedules
 * one disk write. A process killed before that asynchronous write can still lose the whole edit.
 */
object PetStore {
    private const val PREFS = "minis_settings"
    private const val KEY_XP = "tama.pet.xp"
    private const val KEY_LEVEL = "tama.pet.level"
    private const val KEY_LAST_SEEN = "tama.pet.last_seen"
    private const val KEY_DECAY_REMAINDER = "tama.pet.decay_remainder_ms"
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
    private const val KEY_LAST_CARE = "tama.pet.last_care"
    private const val KEY_TURNS_TOGETHER = "tama.pet.turns_together"
    private const val KEY_LOCAL_DAYS = "tama.pet.local_calendar_v1"
    private const val KEY_DAY = "tama.pet.day"
    private const val KEY_TURNS_TODAY = "tama.pet.turns_today"
    private const val KEY_RECENT_TURNS = "tama.pet.recent_turns_v1"
    private const val TAG = "PetStore"

    private val _state = MutableStateFlow(PetState(PetLadder.progressFor(0), PetNeeds(), 0))
    val state: StateFlow<PetState> = _state

    private val _pet = MutableStateFlow(PetLadder.progressFor(0))
    val pet: StateFlow<PetProgress> = _pet

    private var loaded = false
    private var lastSeenMillis = 0L
    private var decayRemainderMillis = 0L
    // Set only while the Pet screen's lifecycle is resumed, not merely while it is composed.
    private var screenVisible = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Reconcile elapsed time and the visit on every call. An older UTC day is approximated using
     * the last-seen timestamp only when both share that UTC date. It is not proof of the local
     * date of every older event; first-day history is retained because its instant is unavailable.
     */
    @Synchronized
    fun load(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val p = prefs(context)
        var migratedTurnDay: Long? = null
        if (!loaded) {
            lastSeenMillis = p.getLong(KEY_LAST_SEEN, 0L)
            decayRemainderMillis = p.getLong(KEY_DECAY_REMAINDER, 0L)
                .coerceIn(0L, PetDecay.HOUR_MS - 1L)
            var streakLast = p.getLong(KEY_STREAK_LAST, -1L).takeIf { it >= 0L }
            if (!p.getBoolean(KEY_LOCAL_DAYS, false) && lastSeenMillis > 0L) {
                val oldUtcDay = PetStreak.legacyUtcDayOf(lastSeenMillis)
                val localDay = PetStreak.dayOf(lastSeenMillis)
                if (streakLast == oldUtcDay) streakLast = localDay
                if (p.getLong(KEY_DAY, -1L) == oldUtcDay) migratedTurnDay = localDay
            }
            val progress = PetLadder.progressFor(p.getLong(KEY_XP, 0L))
            val lastCare = p.getString(KEY_LAST_CARE, null)?.let { saved ->
                PetCareAction.entries.firstOrNull { it.name == saved }
            }
            _state.value = PetState(
                progress = progress,
                needs = PetNeeds(
                    satiety = p.getInt(KEY_SATIETY, PetNeeds.HIGH),
                    energy = p.getInt(KEY_ENERGY, PetNeeds.HIGH),
                    mood = p.getInt(KEY_MOOD, PetNeeds.HIGH),
                    bond = p.getInt(KEY_BOND, PetNeeds.HIGH),
                ),
                aloneHours = 0,
                wardrobe = PetWardrobe.fromStorage(p.getString(KEY_OUTFIT, null)),
                streak = PetStreak(
                    lastDay = streakLast,
                    days = p.getInt(KEY_STREAK_DAYS, 0),
                    best = p.getInt(KEY_STREAK_BEST, 0),
                ),
                memory = PetMemory(
                    careCount = p.getInt(KEY_CARE_COUNT, 0),
                    lastCare = lastCare,
                    turnsTogether = p.getInt(KEY_TURNS_TOGETHER, 0),
                    firstDay = p.getLong(KEY_FIRST_DAY, -1L).takeIf { it >= 0L },
                ),
            )
            _pet.value = progress
            loaded = true
        }

        // A clock set backwards must not invent absence or move the anchor backwards. While the
        // screen is resumed the person is with the Pet, so that interval is not absence either.
        val elapsed = if (!screenVisible && lastSeenMillis > 0L && nowMillis >= lastSeenMillis) {
            nowMillis - lastSeenMillis
        } else {
            0L
        }
        val fractional = decayRemainderMillis + elapsed % PetDecay.HOUR_MS
        val hours = (elapsed / PetDecay.HOUR_MS + fractional / PetDecay.HOUR_MS)
            .coerceAtMost(PetDecay.MAX_DECAY_HOURS)
        decayRemainderMillis = fractional % PetDecay.HOUR_MS
        lastSeenMillis = maxOf(lastSeenMillis, nowMillis)

        val day = PetStreak.dayOf(nowMillis)
        val before = _state.value
        val after = before.copy(
            needs = PetDecay.after(before.needs, hours * PetDecay.HOUR_MS),
            aloneHours = elapsed / PetDecay.HOUR_MS,
            streak = PetStreak.afterVisit(before.streak, day),
            memory = before.memory.firstSeen(day),
        )
        _state.value = after
        editState(context, after).apply {
            if (migratedTurnDay != null) putLong(KEY_DAY, migratedTurnDay)
        }.apply()
    }

    fun currentXp(): Long = _state.value.progress.xp

    /**
     * Reconcile at entry; on exit stamp the end of the visible visit before starting an absence.
     * Repeated lifecycle notifications are harmless. The screen calls this on RESUME/PAUSE, so
     * putting the phone in the background does not leave the Pet permanently "visible".
     */
    @Synchronized
    fun setVisible(context: Context, visible: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        if (screenVisible == visible) return
        load(context, nowMillis)
        screenVisible = visible
    }

    internal data class TurnResult(val state: PetState, val recorded: Boolean)

    /** Record one tracker run. Repeats within the bounded ledger never pay a second time. */
    @Synchronized
    internal fun recordTurn(
        context: Context,
        turnId: String,
        outcome: PetTurnOutcome,
        nowMillis: Long,
    ): TurnResult {
        load(context, nowMillis)
        val p = prefs(context)
        val recent = PetTurnLedger.decode(p.getString(KEY_RECENT_TURNS, null))
        if (!PetTurnLedger.shouldRecord(turnId, recent)) return TurnResult(_state.value, false)

        val before = _state.value
        val eligible = outcome == PetTurnOutcome.SUCCESS || outcome == PetTurnOutcome.FAILURE
        val day = PetStreak.dayOf(nowMillis)
        val storedDay = p.getLong(KEY_DAY, -1L)
        val storedCount = p.getInt(KEY_TURNS_TODAY, 0).coerceAtLeast(0)
        // A backwards clock or westward zone change must not erase the current recorded day.
        val count = if (day >= storedDay && day != storedDay) 0 else storedCount
        val after = if (eligible) {
            val xp = PetXpRules.apply(before.progress.xp, PetXpRules.forOutcome(outcome))
            before.copy(
                progress = PetLadder.progressFor(xp),
                needs = PetCare.afterTurn(before.needs, outcome, turnsToday = count),
                aloneHours = 0,
                memory = before.memory.afterTurn(),
            )
        } else {
            // A cancelled/unknown run is remembered for deduplication, but pays no XP, needs or
            // finished-turn count and does not tire the Pet on a later successful run.
            before
        }

        editState(context, after).apply {
            putString(KEY_RECENT_TURNS, PetTurnLedger.encodeWith(turnId, recent))
            if (eligible) {
                putLong(KEY_DAY, maxOf(day, storedDay))
                putInt(KEY_TURNS_TODAY, if (count == Int.MAX_VALUE) count else count + 1)
            }
        }.apply()
        _state.value = after
        _pet.value = after.progress
        if (after.progress.level > before.progress.level) {
            AppLogger.info(TAG, "pet levelled up: ${before.progress.level} -> ${after.progress.level}")
        }
        return TurnResult(after, true)
    }

    /** Feeding, resting and playing: the three direct care actions. */
    @Synchronized
    fun care(context: Context, need: PetCareAction, nowMillis: Long = System.currentTimeMillis()): PetState {
        load(context, nowMillis)
        val before = _state.value
        val needs = when (need) {
            PetCareAction.FEED -> PetCare.feed(before.needs)
            PetCareAction.REST -> PetCare.rest(before.needs)
            PetCareAction.PLAY -> PetCare.play(before.needs)
        }
        val after = before.copy(needs = needs, aloneHours = 0, memory = before.memory.afterCare(need))
        editState(context, after).apply()
        _state.value = after
        return after
    }

    /** A locked choice remains saved and is worn when its level is reached. */
    @Synchronized
    fun wear(context: Context, outfit: PetOutfit, nowMillis: Long = System.currentTimeMillis()): PetState {
        load(context, nowMillis)
        val after = _state.value.copy(wardrobe = _state.value.wardrobe.choose(outfit))
        editState(context, after).apply()
        _state.value = after
        return after
    }

    /** A visit must reconcile time before it updates the last-seen stamp. */
    @Synchronized
    fun touch(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        load(context, nowMillis)
    }

    private fun editState(context: Context, state: PetState): SharedPreferences.Editor =
        prefs(context).edit()
            .putLong(KEY_XP, state.progress.xp)
            .putInt(KEY_LEVEL, state.progress.level)
            .putInt(KEY_SATIETY, state.needs.satiety)
            .putInt(KEY_ENERGY, state.needs.energy)
            .putInt(KEY_MOOD, state.needs.mood)
            .putInt(KEY_BOND, state.needs.bond)
            .putLong(KEY_LAST_SEEN, lastSeenMillis)
            .putLong(KEY_DECAY_REMAINDER, decayRemainderMillis)
            .putString(KEY_OUTFIT, state.wardrobe.chosenId)
            .putLong(KEY_STREAK_LAST, state.streak.lastDay ?: -1L)
            .putInt(KEY_STREAK_DAYS, state.streak.days)
            .putInt(KEY_STREAK_BEST, state.streak.best)
            .putLong(KEY_FIRST_DAY, state.memory.firstDay ?: -1L)
            .putInt(KEY_CARE_COUNT, state.memory.careCount)
            .putString(KEY_LAST_CARE, state.memory.lastCare?.name)
            .putInt(KEY_TURNS_TOGETHER, state.memory.turnsTogether)
            .putBoolean(KEY_LOCAL_DAYS, true)

    /** Restore primitive for future integration; the app's backup/restore path does not call it. */
    @Synchronized
    fun restore(context: Context, xp: Long, needs: PetNeeds? = null) {
        val edit = prefs(context).edit().putLong(KEY_XP, xp.coerceAtLeast(0L))
        needs?.let { n ->
            edit.putInt(KEY_SATIETY, n.satiety)
                .putInt(KEY_ENERGY, n.energy)
                .putInt(KEY_MOOD, n.mood)
                .putInt(KEY_BOND, n.bond)
        }
        edit.apply()
        loaded = false
        load(context)
    }

    /** Test seam: forget in-memory state without erasing preferences. */
    @Synchronized
    fun forgetForTests() {
        loaded = false
        screenVisible = false
        lastSeenMillis = 0L
        decayRemainderMillis = 0L
        _state.value = PetState(PetLadder.progressFor(0), PetNeeds(), 0)
        _pet.value = PetLadder.progressFor(0)
    }
}

enum class PetCareAction { FEED, REST, PLAY }
