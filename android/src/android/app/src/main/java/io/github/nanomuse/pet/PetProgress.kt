package io.github.nanomuse.pet

/**
 * The Pet's progression numbers: XP in, level and stage out.
 *
 * Deliberately pure Kotlin with no Android imports, so the rules can be exercised by a plain
 * JUnit test on the host (the tests in `src/test` run on the JVM). Everything mutable lives in
 * [PetStore]; this file only decides arithmetic, and arithmetic is the part that must never
 * regress silently — a wrong threshold would either freeze a user at a level or jump them
 * several at once, and neither shows up as a crash.
 *
 * Progression is monotonic during normal use: XP only grows, and [levelFor] is a pure function
 * of the total. Pet backup/restore is not wired into the app yet, so reinstalling can still lose
 * that total; the progression rule alone does not protect it.
 */
data class PetProgress(
    /** Lifetime XP. Never decreases; a clamp at 0 is the only guard needed. */
    val xp: Long,
    val level: Int,
    val stage: PetStage,
) {
    /** XP still needed to reach the next level; `null` once the ladder tops out. */
    val toNextLevel: Long? get() = PetLadder.nextLevelAt(level)?.let { (it - xp).coerceAtLeast(0) }

    /** Fraction of the way through the current level, 0f..1f, for the progress ring. */
    val levelFraction: Float
        get() {
            val from = PetLadder.levelStart(level)
            val to = PetLadder.nextLevelAt(level) ?: return 1f
            val span = to - from
            if (span <= 0) return 1f
            return ((xp - from).toFloat() / span).coerceIn(0f, 1f)
        }
}

/**
 * The confirmed TAMA stage names live in one enum. Level bands are inclusive.
 */
enum class PetStage(val fromLevel: Int, val label: String) {
    HATCHLING(1, "Filhote"),
    COMPANION(3, "Companheiro"),
    GUARDIAN(6, "Guardião"),
    LEGEND(10, "Lenda"),
    ;

    companion object {
        fun forLevel(level: Int): PetStage =
            entries.lastOrNull { level >= it.fromLevel } ?: HATCHLING
    }
}

/**
 * The XP ladder.
 *
 * Level 1 starts at 0. Each level costs `BASE + (level - 1) * STEP`, so the cumulative cost grows
 * quadratically: level 2 at 100, level 3 at 250, level 4 at 450, level 6 at 1000, level 10 at 2700.
 * Quadratic
 * rather than linear on purpose — a linear ladder makes later levels arrive almost instantly once
 * a user is in the habit, which reads as a bug ("the number keeps changing").
 */
object PetLadder {
    const val BASE = 100L
    const val STEP = 50L

    /** No level beyond this, so a long-lived account cannot overflow the ring or the copy. */
    const val MAX_LEVEL = 99

    /** Total XP at which [level] begins. Level 1 is 0 by definition. */
    fun levelStart(level: Int): Long {
        val n = level.coerceAtLeast(1)
        if (n == 1) return 0L
        // sum over k in 1 until n of (BASE + (k-1)*STEP)
        val count = (n - 1).toLong()
        return count * BASE + STEP * (count * (count - 1) / 2)
    }

    /** XP at which the level after [level] begins, or `null` at [MAX_LEVEL]. */
    fun nextLevelAt(level: Int): Long? =
        if (level >= MAX_LEVEL) null else levelStart(level + 1)

    /** The level a lifetime XP total earns. Clamps at 1 and at [MAX_LEVEL]. */
    fun levelFor(xp: Long): Int {
        val total = xp.coerceAtLeast(0L)
        var level = 1
        while (level < MAX_LEVEL && total >= levelStart(level + 1)) level++
        return level
    }

    /** The whole derived state from a raw XP total. */
    fun progressFor(xp: Long): PetProgress {
        val total = xp.coerceAtLeast(0L)
        val level = levelFor(total)
        return PetProgress(xp = total, level = level, stage = PetStage.forLevel(level))
    }
}

/**
 * What a finished turn is worth, and the one place that decides it.
 *
 * The numbers are small on purpose: a Pet that levels up several times an evening stops being a
 * record of anything. A failure still pays a little — the agent did work — but far less than a
 * success, so the total stays a rough measure of usefulness rather than of activity.
 */
object PetXpRules {
    const val SUCCESS = 10L
    const val FAILURE = 3L
    const val CANCELLED = 0L

    /** A single turn can never award more than this, whatever a caller passes. */
    const val TURN_CAP = 25L

    fun forOutcome(outcome: PetTurnOutcome): Long = when (outcome) {
        PetTurnOutcome.SUCCESS -> SUCCESS
        PetTurnOutcome.FAILURE -> FAILURE
        PetTurnOutcome.CANCELLED -> CANCELLED
        PetTurnOutcome.UNKNOWN -> 0L
    }

    /** Adds [amount] to [current], clamped so XP never decreases and a turn never exceeds the cap. */
    fun apply(current: Long, amount: Long): Long {
        val gain = amount.coerceIn(0L, TURN_CAP)
        return (current.coerceAtLeast(0L) + gain).coerceAtLeast(0L)
    }
}

/** The outcome of a finished turn, as the Pet understands it. Kept separate from the app's own
 * `ToolOutcome` so this file stays free of Android and of that enum's growth. */
enum class PetTurnOutcome { SUCCESS, FAILURE, CANCELLED, UNKNOWN }
