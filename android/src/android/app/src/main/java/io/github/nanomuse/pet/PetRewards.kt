package io.github.nanomuse.pet

import java.security.MessageDigest

/** Mission definitions are independent of the level formula and the tracker-cycle reward. */
enum class PetMission(val target: Int, val xp: Int) {
    FIRST_INTERACTION(1, 5),
    CONVERSATION(3, 6),
    TASK(1, 15),
    GOAL(1, 30),
    LEARN(2, 16),
    CREATE(1, 8),
    SKILL(1, 10),
    MILESTONE(1, 25),
    MINIGAME(1, 5),
}

enum class PetEvent { CHAT, GOAL, LEARN, CREATE, SKILL }

/** One device-local calendar day. It belongs to the Pet as a whole, not to a generation. */
data class PetDaily(
    val day: Long? = null,
    val earned: Int = 0,
    val missions: Map<PetMission, Int> = emptyMap(),
) {
    fun count(mission: PetMission): Int = (missions[mission] ?: 0).coerceIn(0, mission.target)
    fun completed(mission: PetMission): Boolean = count(mission) >= mission.target
}

data class PetRewardState(val xp: Long, val daily: PetDaily)

/**
 * Cycles and completed missions draw from the same 100 XP daily allowance. Partial final rewards
 * are allowed; a completed mission remains completed even when the allowance is already empty.
 * Callers serialize the returned XP, day and event ledger in one preferences edit.
 */
object PetRewards {
    const val DAILY_CAP = 100

    fun onDay(daily: PetDaily, day: Long): PetDaily = when {
        daily.day == null || day > daily.day -> PetDaily(day = day)
        else -> daily // moving the phone clock or time zone back never resets the allowance
    }

    fun cycle(state: PetRewardState, outcome: PetTurnOutcome): PetRewardState {
        val afterCycle = addXp(state, PetXpRules.forOutcome(outcome))
        return if (outcome == PetTurnOutcome.SUCCESS) mission(afterCycle, PetMission.TASK) else afterCycle
    }

    fun event(state: PetRewardState, event: PetEvent): PetRewardState = when (event) {
        PetEvent.CHAT -> mission(mission(state, PetMission.FIRST_INTERACTION), PetMission.CONVERSATION)
        PetEvent.GOAL -> mission(state, PetMission.GOAL)
        PetEvent.LEARN -> mission(state, PetMission.LEARN)
        PetEvent.CREATE -> mission(state, PetMission.CREATE)
        PetEvent.SKILL -> mission(state, PetMission.SKILL)
    }

    fun mission(state: PetRewardState, mission: PetMission): PetRewardState {
        val count = state.daily.count(mission)
        if (count >= mission.target) return state
        val next = count + 1
        val updated = state.copy(daily = state.daily.copy(missions = state.daily.missions + (mission to next)))
        return if (next == mission.target) addXp(updated, mission.xp.toLong()) else updated
    }

    private fun addXp(state: PetRewardState, requested: Long): PetRewardState {
        val current = state.xp.coerceAtLeast(0L)
        val earned = state.daily.earned.coerceIn(0, DAILY_CAP)
        val allowed = requested.coerceAtLeast(0L).coerceAtMost((DAILY_CAP - earned).toLong())
        val gain = allowed.coerceAtMost(Long.MAX_VALUE - current)
        if (gain == 0L) return state.copy(xp = current, daily = state.daily.copy(earned = earned))
        val previousStage = PetStage.forLevel(PetLadder.levelFor(current))
        val result = state.copy(xp = current + gain, daily = state.daily.copy(earned = earned + gain.toInt()))
        val nextStage = PetStage.forLevel(PetLadder.levelFor(result.xp))
        // A new stage is a confirmed milestone. This mission is once per local day; awarding it
        // through the same function prevents it bypassing the shared ceiling or being paid twice.
        return if (nextStage.ordinal > previousStage.ordinal) mission(result, PetMission.MILESTONE) else result
    }

    /** Store only a bounded hash, never a message, file path or raw external event identifier. */
    fun eventKey(kind: String, id: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$kind\u0000$id".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
