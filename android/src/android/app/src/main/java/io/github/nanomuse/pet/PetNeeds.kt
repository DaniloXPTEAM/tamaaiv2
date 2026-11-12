package io.github.nanomuse.pet

import kotlin.math.abs

/**
 * What the Pet needs, as four numbers 0..100 that fall as time passes.
 *
 * This is the part that makes TAMA a tamagotchi rather than a progress bar: the Pet has state of
 * its own that changes when the person is *away*, and comes back with an opinion about it. XP
 * ([PetProgress]) never falls; a need always does. That asymmetry is the whole design — effort is
 * remembered, attention is not.
 *
 * Pure: no Android, no clock of its own. Every function takes the elapsed time, so the decay can
 * be reasoned about and tested without waiting hours.
 */
data class PetNeeds(
    /** How fed the Pet feels, 0..100. */
    val satiety: Int = HIGH,
    /** How rested, 0..100. Falls fastest: a Pet that never sleeps is a Pet that nags. */
    val energy: Int = HIGH,
    /** How much it wants company, 0..100. */
    val mood: Int = HIGH,
    /** How sure it is that the person is around, 0..100. This is the one that "misses" them. */
    val bond: Int = HIGH,
) {
    companion object {
        const val HIGH = 80
        const val FULL = 100
        const val EMPTY = 0

        /** Below this a need is visibly a problem. */
        const val NEEDY_THRESHOLD = 30

        /** Below this the Pet is content rather than fine. */
        const val CONTENT_THRESHOLD = 60
    }

    /** The need asking for attention most urgently, or `null` when nothing is wrong. */
    val weakest: PetNeed?
        get() = PetNeed.entries
            .filter { this[it] < NEEDY_THRESHOLD }
            .minByOrNull { this[it] }
            ?: PetNeed.entries.minBy { this[it] }.takeIf { this[it] < CONTENT_THRESHOLD }

    operator fun get(need: PetNeed): Int = when (need) {
        PetNeed.SATIETY -> satiety
        PetNeed.ENERGY -> energy
        PetNeed.MOOD -> mood
        PetNeed.BOND -> bond
    }

    fun with(need: PetNeed, value: Int): PetNeeds {
        val v = value.coerceIn(PetNeeds.EMPTY, PetNeeds.FULL)
        return when (need) {
            PetNeed.SATIETY -> copy(satiety = v)
            PetNeed.ENERGY -> copy(energy = v)
            PetNeed.MOOD -> copy(mood = v)
            PetNeed.BOND -> copy(bond = v)
        }
    }

    /** The average of the four, 0..100: what the face reflects. */
    val overall: Int get() = (satiety + energy + mood + bond) / 4
}

/** The four needs. The order is the order the screen shows them. */
enum class PetNeed { SATIETY, ENERGY, MOOD, BOND }

/**
 * How fast each need falls, and what tops it up.
 *
 * Per-hour rates, deliberately slow enough that a person who opens the app daily never sees a
 * crisis, and fast enough that leaving for a week is visible. The Pet must never be lost or die:
 * this app's agent works for people who are away, so punishing absence would be backwards. Every
 * number floors at zero and nothing here can delete anything.
 */
object PetDecay {
    /** Points lost per hour, per need. */
    const val SATIETY_PER_HOUR = 4
    const val ENERGY_PER_HOUR = 6
    const val MOOD_PER_HOUR = 3
    const val BOND_PER_HOUR = 2

    /** A single absence cannot burn more than this, so a month away is not a wiped Pet. */
    const val MAX_DECAY_HOURS = 24L * 7

    fun rate(need: PetNeed): Int = when (need) {
        PetNeed.SATIETY -> SATIETY_PER_HOUR
        PetNeed.ENERGY -> ENERGY_PER_HOUR
        PetNeed.MOOD -> MOOD_PER_HOUR
        PetNeed.BOND -> BOND_PER_HOUR
    }

    /**
     * The needs after [elapsedMs] with nobody around.
     *
     * Integer arithmetic throughout: a fractional need would show up as a value the UI can never
     * render and that a test can never pin.
     */
    fun after(needs: PetNeeds, elapsedMs: Long): PetNeeds {
        if (elapsedMs <= 0L) return needs
        val hours = (elapsedMs / HOUR_MS).coerceAtMost(MAX_DECAY_HOURS)
        if (hours <= 0L) return needs
        return PetNeeds(
            satiety = drop(needs.satiety, SATIETY_PER_HOUR, hours),
            energy = drop(needs.energy, ENERGY_PER_HOUR, hours),
            mood = drop(needs.mood, MOOD_PER_HOUR, hours),
            bond = drop(needs.bond, BOND_PER_HOUR, hours),
        )
    }

    private fun drop(value: Int, perHour: Int, hours: Long): Int =
        // Long arithmetic on purpose (hours can be large), narrowed once at the end after the
        // floor, so the Int conversion can never overflow into a negative need.
        (value - perHour * hours).coerceAtLeast(PetNeeds.EMPTY.toLong()).toInt()

    const val HOUR_MS = 60L * 60L * 1000L
}

/**
 * How the Pet feels, derived from its needs and from what just happened.
 *
 * The face has five expressions ([io.github.nanomuse.ui.avatar.AgentMood]); this decides which
 * one a Pet in a given state wears, so the character and the chat header stay one character.
 */
enum class PetMood(val asset: PetMoodAsset) {
    /** Everything is fine and the person is here. */
    CONTENT(PetMoodAsset.HAPPY),

    /** Nothing is wrong, nothing is happening. */
    CALM(PetMoodAsset.IDLE),

    /** A need is low: the Pet wants food, sleep or company. */
    NEEDY(PetMoodAsset.WAITING),

    /** The agent is working right now, and the Pet is paying attention. */
    FOCUSED(PetMoodAsset.WORKING),

    /** Something just failed, or the Pet has been alone long enough to sulk. */
    GRUMPY(PetMoodAsset.ERROR),
    ;

    companion object {
        /**
         * The mood for a state.
         *
         * Precedence is deliberate and is the interesting part: what the agent is doing *now*
         * (working) outranks everything, because the face has to follow the action; a recent
         * success is a moment of delight; a low need is next, so the Pet asks before it sulks;
         * and being away for a long time is a sulk only after the needs are already low, so the
         * Pet does not greet somebody returning after an hour with a complaint.
         */
        fun of(
            needs: PetNeeds,
            working: Boolean = false,
            recentSuccess: Boolean = false,
            recentFailure: Boolean = false,
            aloneHours: Long = 0,
        ): PetMood = when {
            working -> FOCUSED
            recentFailure -> GRUMPY
            recentSuccess -> CONTENT
            // Away *and* worn is the sulk, and it outranks the plain "I need something": a Pet
            // whose needs have collapsed while nobody was there is not asking, it is unhappy.
            aloneHours > 0L && needs.overall <= PetNeeds.NEEDY_THRESHOLD -> GRUMPY
            needs.weakest != null -> NEEDY
            needs.overall >= PetNeeds.CONTENT_THRESHOLD -> CONTENT
            else -> CALM
        }

        /**
         * Kept for callers that want to name the span, not as the sulk's trigger.
         *
         * The store does not hand [of] a live absence (it passes `aloneHours = 0`, because the
         * decayed needs already say how long it was), so gating the sulk on this constant meant a
         * sulk that never happened. "Away, and the needs have fallen" is the condition now.
         */
        const val ALONE_SULK_HOURS = 72L
    }
}

/** Which asset a mood wears. Named here so the Pet does not import Compose to describe itself. */
enum class PetMoodAsset { IDLE, WORKING, WAITING, HAPPY, ERROR }

/**
 * What a finished turn does to the needs.
 *
 * Work feeds some needs and starves others, which is what makes the loop interesting: a busy day
 * is good for the bond and bad for the energy. A failed turn costs mood but still counts as
 * company.
 */
object PetCare {
    fun afterTurn(needs: PetNeeds, outcome: PetTurnOutcome, turnsToday: Int): PetNeeds {
        val fed = needs.with(PetNeed.SATIETY, needs.satiety + when (outcome) {
            PetTurnOutcome.SUCCESS -> 12
            PetTurnOutcome.FAILURE -> 6
            else -> 4
        })
        val company = fed.with(PetNeed.MOOD, fed.mood + when (outcome) {
            PetTurnOutcome.SUCCESS -> 8
            PetTurnOutcome.CANCELLED -> 2
            else -> 3
        })
        val seen = company.with(PetNeed.BOND, company.bond + 10)
        // Attention is company but not rest: every turn past a handful costs energy, so a person
        // who works the agent all night finds a tired Pet in the morning rather than a full one.
        val tired = if (turnsToday > BUSY_TURNS) {
            seen.with(PetNeed.ENERGY, seen.energy - ENERGY_PER_EXTRA_TURN)
        } else {
            seen
        }
        return tired.with(PetNeed.MOOD, tired.mood - if (outcome == PetTurnOutcome.FAILURE) 6 else 0)
    }

    /** Turns in a day past which each one costs energy. */
    const val BUSY_TURNS = 6
    const val ENERGY_PER_EXTRA_TURN = 5

    /** Feeding and resting, the two things the person can do directly. */
    fun feed(needs: PetNeeds): PetNeeds = needs.with(PetNeed.SATIETY, needs.satiety + 30)

    fun rest(needs: PetNeeds): PetNeeds =
        needs.with(PetNeed.ENERGY, needs.energy + 40).with(PetNeed.SATIETY, needs.satiety - 5)

    fun play(needs: PetNeeds): PetNeeds =
        needs.with(PetNeed.MOOD, needs.mood + 25).with(PetNeed.BOND, needs.bond + 15)

    /** Whether [needs] are close enough to [other] that a write is not worth it. */
    fun near(a: PetNeeds, b: PetNeeds, tolerance: Int = 2): Boolean =
        PetNeed.entries.all { abs(a[it] - b[it]) <= tolerance }
}
