package io.github.nanomuse.pet

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import io.github.nanomuse.identity.NanoMuseIdentity
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
    val life: PetLife = PetLife(),
    val daily: PetDaily = PetDaily(),
    val curiosity: Int = 50,
    val diary: PetDiaryEntry? = null,
    val archives: List<PetArchive> = emptyList(),
) {
    val mood: PetMood get() = PetMood.of(needs, aloneHours = aloneHours)
    val outfit: PetOutfit get() = wardrobe.wornAt(progress.level)
}

/**
 * Pet state stays in the existing `minis_settings` preferences. All changes share one lock;
 * XP, the local-day allowance and the bounded deduplication ledger are written in one edit.
 * A legacy Pet has no reliable feeding instant, so its 72-hour clock starts on first migration,
 * never retroactively from an old visit. Normal edits use apply(); death and archival use commit()
 * so an irreversible transition is not deliberately left waiting for a later disk write.
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
    private const val KEY_RECENT_EVENTS = "tama.pet.recent_events_v1"
    private const val KEY_DAILY_DAY = "tama.pet.rewards_day_v1"
    private const val KEY_DAILY_XP = "tama.pet.rewards_xp_v1"
    private const val KEY_MISSION = "tama.pet.mission_v1."
    private const val KEY_GENERATION = "tama.pet.generation_v1"
    private const val KEY_BORN = "tama.pet.born_at_v1"
    private const val KEY_LAST_FED = "tama.pet.last_fed_v1"
    private const val KEY_DEATH = "tama.pet.death_at_v1"
    private const val KEY_NAME = "tama.pet.name_v1"
    private const val KEY_ARCHIVES = "tama.pet.archives_v1"
    private const val KEY_CURIOSITY = "tama.pet.curiosity_v1"
    private const val KEY_DIARY = "tama.pet.diary_v1"
    private const val TAG = "PetStore"

    private val _state = MutableStateFlow(PetState(PetLadder.progressFor(0), PetNeeds(), 0))
    val state: StateFlow<PetState> = _state

    private val _pet = MutableStateFlow(PetLadder.progressFor(0))
    val pet: StateFlow<PetProgress> = _pet

    private var loaded = false
    private var lastSeenMillis = 0L
    private var decayRemainderMillis = 0L
    private var screenVisible = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun identity(context: Context, fallback: String = "TAMA AI"): String =
        runCatching { NanoMuseIdentity.name(context).trim().take(120) }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: fallback

    /**
     * Reconciles life on every access, including while the screen is resumed. A death is dated
     * from the last actual FEED, not the time the app was next opened. If grief also elapsed
     * while the app was closed, archive the old Pet and start one new generation at observation
     * time: an unattended phone must not manufacture an endless chain of unseen generations.
     */
    @Synchronized
    fun load(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val now = nowMillis.coerceAtLeast(0L)
        val p = prefs(context)
        var migratedTurnDay: Long? = null
        if (!loaded) {
            lastSeenMillis = p.getLong(KEY_LAST_SEEN, 0L).coerceAtLeast(0L)
            decayRemainderMillis = p.getLong(KEY_DECAY_REMAINDER, 0L).coerceIn(0L, PetDecay.HOUR_MS - 1L)
            var streakLast = p.getLong(KEY_STREAK_LAST, -1L).takeIf { it >= 0L }
            if (!p.getBoolean(KEY_LOCAL_DAYS, false) && lastSeenMillis > 0L) {
                val oldUtcDay = PetStreak.legacyUtcDayOf(lastSeenMillis)
                val localDay = PetStreak.dayOf(lastSeenMillis)
                if (streakLast == oldUtcDay) streakLast = localDay
                if (p.getLong(KEY_DAY, -1L) == oldUtcDay) migratedTurnDay = localDay
            }
            val lastCare = p.getString(KEY_LAST_CARE, null)?.let { saved ->
                PetCareAction.entries.firstOrNull { it.name == saved }
            }
            val day = p.getLong(KEY_DAILY_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            val missionCounts = PetMission.entries.associateWith { mission ->
                p.getInt(KEY_MISSION + mission.name, 0).coerceIn(0, mission.target)
            }
            _state.value = PetState(
                progress = PetLadder.progressFor(p.getLong(KEY_XP, 0L)),
                needs = PetNeeds(
                    satiety = p.getInt(KEY_SATIETY, PetNeeds.HIGH).coerceIn(0, 100),
                    energy = p.getInt(KEY_ENERGY, PetNeeds.HIGH).coerceIn(0, 100),
                    mood = p.getInt(KEY_MOOD, PetNeeds.HIGH).coerceIn(0, 100),
                    bond = p.getInt(KEY_BOND, PetNeeds.HIGH).coerceIn(0, 100),
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
                life = PetLife(
                    generation = p.getLong(KEY_GENERATION, 1L).coerceAtLeast(1L),
                    bornAt = p.getLong(KEY_BORN, now).coerceAtLeast(0L),
                    lastFedAt = p.getLong(KEY_LAST_FED, now).coerceAtLeast(0L),
                    deathAt = p.getLong(KEY_DEATH, -1L).takeIf { it >= 0L },
                    name = p.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: identity(context),
                ),
                daily = PetDaily(day, p.getInt(KEY_DAILY_XP, 0).coerceIn(0, PetRewards.DAILY_CAP), missionCounts),
                curiosity = p.getInt(KEY_CURIOSITY, 50).coerceIn(0, 100),
                diary = PetDiaryEntry.decode(p.getString(KEY_DIARY, null)),
                archives = PetArchiveCodec.decode(p.getString(KEY_ARCHIVES, null)),
            )
            loaded = true
        }

        val before = _state.value
        val named = if (before.life.grieving) before.life else before.life.copy(name = identity(context, before.life.name))
        val life = PetLifeRules.reconcile(named, now)
        val newDeath = before.life.deathAt == null && life.deathAt != null
        // The need decay stops at death. A resumed screen is presence, not absence, but does not
        // stop the separate feeding clock. Backwards clock changes never move lastSeen backwards.
        val until = life.deathAt?.let { minOf(now, it) } ?: now
        val elapsed = if (!screenVisible && lastSeenMillis > 0L && until >= lastSeenMillis) {
            until - lastSeenMillis
        } else 0L
        val fractional = decayRemainderMillis + elapsed % PetDecay.HOUR_MS
        val hours = (elapsed / PetDecay.HOUR_MS + fractional / PetDecay.HOUR_MS).coerceAtMost(PetDecay.MAX_DECAY_HOURS)
        decayRemainderMillis = fractional % PetDecay.HOUR_MS
        lastSeenMillis = maxOf(lastSeenMillis, now)
        val day = PetStreak.dayOf(now)
        val base = before.copy(
            needs = PetDecay.after(before.needs, hours * PetDecay.HOUR_MS),
            aloneHours = if (before.life.grieving) before.aloneHours else elapsed / PetDecay.HOUR_MS,
            life = life,
            daily = PetRewards.onDay(before.daily, day),
        )

        if (PetLifeRules.griefFinished(life, now)) {
            val archive = PetArchive(life, base.progress, base.needs, base.wardrobe, base.streak, base.memory, base.curiosity, base.diary)
            val generation = if (life.generation == Long.MAX_VALUE) Long.MAX_VALUE else life.generation + 1L
            lastSeenMillis = now
            decayRemainderMillis = 0L
            val next = PetState(
                progress = PetLadder.progressFor(0L),
                needs = PetNeeds(),
                aloneHours = 0L,
                streak = PetStreak.afterVisit(PetStreak(), day),
                memory = PetMemory().firstSeen(day),
                life = PetLife(generation, now, now, null, identity(context, life.name)),
                daily = base.daily,
                archives = before.archives + archive,
            )
            val edit = editState(context, next)
                .putString(KEY_ARCHIVES, PetArchiveCodec.append(p.getString(KEY_ARCHIVES, null), archive))
                .putLong(KEY_DAY, day)
                .putInt(KEY_TURNS_TODAY, 0)
            if (!edit.commit()) AppLogger.warning(TAG, "could not persist the Pet archive")
            publish(next)
            return
        }

        val after = if (life.grieving) base else base.copy(
            streak = PetStreak.afterVisit(base.streak, day),
            memory = base.memory.firstSeen(day),
        )
        val edit = editState(context, after).apply {
            if (migratedTurnDay != null) putLong(KEY_DAY, migratedTurnDay)
        }
        if (newDeath) {
            if (!edit.commit()) AppLogger.warning(TAG, "could not persist the Pet's mourning state")
        } else edit.apply()
        publish(after)
    }

    fun currentXp(): Long = _state.value.progress.xp

    @Synchronized
    fun setVisible(context: Context, visible: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        if (screenVisible == visible) return
        load(context, nowMillis)
        screenVisible = visible
    }

    /** A run started in grief can never pay a later generation. */
    @Synchronized
    fun generationForRun(context: Context, nowMillis: Long = System.currentTimeMillis()): Long {
        load(context, nowMillis)
        return if (_state.value.life.grieving) -1L else _state.value.life.generation
    }

    internal data class TurnResult(val state: PetState, val recorded: Boolean)

    @Synchronized
    internal fun recordTurn(
        context: Context,
        turnId: String,
        outcome: PetTurnOutcome,
        nowMillis: Long,
        expectedGeneration: Long? = null,
    ): TurnResult {
        load(context, nowMillis)
        val p = prefs(context)
        val recent = PetTurnLedger.decode(p.getString(KEY_RECENT_TURNS, null))
        if (!PetTurnLedger.shouldRecord(turnId, recent)) return TurnResult(_state.value, false)
        val before = _state.value
        if (before.life.grieving || (expectedGeneration != null && expectedGeneration != before.life.generation)) {
            p.edit().putString(KEY_RECENT_TURNS, PetTurnLedger.encodeWith(turnId, recent)).apply()
            return TurnResult(before, false)
        }
        val eligible = outcome == PetTurnOutcome.SUCCESS || outcome == PetTurnOutcome.FAILURE
        val day = PetStreak.dayOf(nowMillis.coerceAtLeast(0L))
        val storedDay = p.getLong(KEY_DAY, -1L)
        val storedCount = p.getInt(KEY_TURNS_TODAY, 0).coerceAtLeast(0)
        val count = if (day > storedDay) 0 else storedCount
        val after = if (eligible) {
            val reward = PetRewards.cycle(PetRewardState(before.progress.xp, before.daily), outcome)
            before.copy(
                progress = PetLadder.progressFor(reward.xp),
                daily = reward.daily,
                needs = PetCare.afterTurn(before.needs, outcome, turnsToday = count),
                aloneHours = 0,
                memory = before.memory.afterTurn(),
            )
        } else before
        editState(context, after).apply {
            putString(KEY_RECENT_TURNS, PetTurnLedger.encodeWith(turnId, recent))
            if (eligible) {
                putLong(KEY_DAY, maxOf(day, storedDay))
                putInt(KEY_TURNS_TODAY, if (count == Int.MAX_VALUE) count else count + 1)
            }
        }.apply()
        publish(after)
        if (after.progress.level > before.progress.level) {
            AppLogger.info(TAG, "pet levelled up: ${before.progress.level} -> ${after.progress.level}")
        }
        return TurnResult(after, true)
    }

    /** Count a real external event without retaining its content or identifier. */
    @Synchronized
    fun recordEvent(
        context: Context,
        event: PetEvent,
        eventId: String,
        nowMillis: Long = System.currentTimeMillis(),
        expectedGeneration: Long? = null,
    ): PetState {
        load(context, nowMillis)
        if (eventId.isBlank() || eventId.length > 1024) return _state.value
        val p = prefs(context)
        val key = PetRewards.eventKey(event.name, eventId)
        val recent = PetTurnLedger.decode(p.getString(KEY_RECENT_EVENTS, null))
        if (!PetTurnLedger.shouldRecord(key, recent)) return _state.value
        val before = _state.value
        val blocked = before.life.grieving || (expectedGeneration != null && expectedGeneration != before.life.generation)
        val reward = if (blocked) PetRewardState(before.progress.xp, before.daily)
            else PetRewards.event(PetRewardState(before.progress.xp, before.daily), event)
        val after = before.copy(progress = PetLadder.progressFor(reward.xp), daily = reward.daily)
        editState(context, after).putString(KEY_RECENT_EVENTS, PetTurnLedger.encodeWith(key, recent)).apply()
        publish(after)
        return after
    }

    /** Feeding is the only action that resets the life clock; it must happen before death. */
    @Synchronized
    fun care(
        context: Context,
        need: PetCareAction,
        nowMillis: Long = System.currentTimeMillis(),
        expectedGeneration: Long? = null,
    ): PetState {
        load(context, nowMillis)
        val before = _state.value
        if (before.life.grieving || (expectedGeneration != null && expectedGeneration != before.life.generation)) return before
        val needs = when (need) {
            PetCareAction.FEED -> PetCare.feed(before.needs)
            PetCareAction.REST -> PetCare.rest(before.needs)
            PetCareAction.PLAY -> PetCare.play(before.needs)
        }
        val reward = PetRewards.mission(PetRewardState(before.progress.xp, before.daily), PetMission.FIRST_INTERACTION)
        val after = before.copy(
            progress = PetLadder.progressFor(reward.xp),
            daily = reward.daily,
            needs = needs,
            life = if (need == PetCareAction.FEED) before.life.copy(lastFedAt = maxOf(before.life.lastFedAt, nowMillis)) else before.life,
            aloneHours = 0,
            memory = before.memory.afterCare(need),
        )
        editState(context, after).apply()
        publish(after)
        return after
    }

    @Synchronized
    fun wear(
        context: Context,
        outfit: PetOutfit,
        nowMillis: Long = System.currentTimeMillis(),
        expectedGeneration: Long? = null,
    ): PetState {
        load(context, nowMillis)
        val before = _state.value
        if (before.life.grieving || (expectedGeneration != null && expectedGeneration != before.life.generation)) return before
        val after = before.copy(wardrobe = before.wardrobe.choose(outfit))
        editState(context, after).apply()
        publish(after)
        return after
    }

    enum class ActivityStatus { PERFORMED, LOW_ENERGY, UNAVAILABLE }
    data class ActivityOutcome(val state: PetState, val status: ActivityStatus) {
        val performed: Boolean get() = status == ActivityStatus.PERFORMED
    }

    @Synchronized
    fun activity(
        context: Context,
        activity: PetActivity,
        nowMillis: Long = System.currentTimeMillis(),
        expectedGeneration: Long? = null,
    ): ActivityOutcome {
        load(context, nowMillis)
        val before = _state.value
        if (before.life.grieving || (expectedGeneration != null && expectedGeneration != before.life.generation)) {
            return ActivityOutcome(before, ActivityStatus.UNAVAILABLE)
        }
        val result = PetActivities.perform(activity, before.needs, before.curiosity)
            ?: return ActivityOutcome(before, ActivityStatus.LOW_ENERGY)
        val reward = if (activity == PetActivity.EXPERIMENT) {
            PetRewards.mission(PetRewardState(before.progress.xp, before.daily), PetMission.CREATE)
        } else PetRewardState(before.progress.xp, before.daily)
        val progress = PetLadder.progressFor(reward.xp)
        val after = before.copy(
            progress = progress,
            daily = reward.daily,
            needs = result.needs,
            curiosity = result.curiosity,
            diary = if (activity == PetActivity.JOURNAL) {
                PetDiaryEntry(nowMillis, progress.level, result.needs.energy, result.needs.bond)
            } else before.diary,
        )
        editState(context, after).apply()
        publish(after)
        return ActivityOutcome(after, ActivityStatus.PERFORMED)
    }

    data class MiniGameOutcome(val state: PetState, val recorded: Boolean)

    @Synchronized
    fun finishGame(
        context: Context,
        game: PetMiniGame,
        colors: List<PetColor>,
        number: Int?,
        sessionId: String,
        generation: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): MiniGameOutcome {
        load(context, nowMillis)
        val before = _state.value
        if (before.life.grieving || before.life.generation != generation || sessionId.isBlank() || sessionId.length > 1024 ||
            !PetMiniGames.won(game, colors, number)) return MiniGameOutcome(before, false)
        val p = prefs(context)
        val key = PetRewards.eventKey("GAME", sessionId)
        val recent = PetTurnLedger.decode(p.getString(KEY_RECENT_EVENTS, null))
        if (!PetTurnLedger.shouldRecord(key, recent)) return MiniGameOutcome(before, false)
        val reward = PetRewards.mission(PetRewardState(before.progress.xp, before.daily), PetMission.MINIGAME)
        val after = before.copy(
            progress = PetLadder.progressFor(reward.xp),
            daily = reward.daily,
            needs = before.needs.with(PetNeed.MOOD, before.needs.mood + 1),
        )
        editState(context, after).putString(KEY_RECENT_EVENTS, PetTurnLedger.encodeWith(key, recent)).apply()
        publish(after)
        return MiniGameOutcome(after, true)
    }

    @Synchronized
    fun touch(context: Context, nowMillis: Long = System.currentTimeMillis()) = load(context, nowMillis)

    private fun publish(state: PetState) {
        _state.value = state
        _pet.value = state.progress
    }

    private fun editState(context: Context, state: PetState): SharedPreferences.Editor {
        val edit = prefs(context).edit()
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
            .putLong(KEY_GENERATION, state.life.generation)
            .putLong(KEY_BORN, state.life.bornAt)
            .putLong(KEY_LAST_FED, state.life.lastFedAt)
            .putLong(KEY_DEATH, state.life.deathAt ?: -1L)
            .putString(KEY_NAME, state.life.name)
            .putLong(KEY_DAILY_DAY, state.daily.day ?: Long.MIN_VALUE)
            .putInt(KEY_DAILY_XP, state.daily.earned)
            .putInt(KEY_CURIOSITY, state.curiosity)
            .putString(KEY_DIARY, state.diary?.encode())
        for (mission in PetMission.entries) edit.putInt(KEY_MISSION + mission.name, state.daily.count(mission))
        return edit
    }

    /** Restore primitive for future integration; the app's backup/restore path does not call it. */
    @Synchronized
    fun restore(context: Context, xp: Long, needs: PetNeeds? = null) {
        val edit = prefs(context).edit().putLong(KEY_XP, xp.coerceAtLeast(0L))
        needs?.let { n ->
            edit.putInt(KEY_SATIETY, n.satiety).putInt(KEY_ENERGY, n.energy)
                .putInt(KEY_MOOD, n.mood).putInt(KEY_BOND, n.bond)
        }
        edit.apply()
        loaded = false
        load(context)
    }

    @Synchronized
    fun forgetForTests() {
        loaded = false
        screenVisible = false
        lastSeenMillis = 0L
        decayRemainderMillis = 0L
        publish(PetState(PetLadder.progressFor(0), PetNeeds(), 0))
    }
}

enum class PetCareAction { FEED, REST, PLAY }
