package io.github.nanomuse.pet

import java.util.TimeZone

/**
 * Days in a row the person looked after the Pet, and what it remembers about that.
 *
 * The streak is the only part of the Pet that can go *down*, and it is the part that makes coming
 * back feel like something: a Pet that remembers you were here yesterday greets you differently
 * from one that does not.
 *
 * It counts the device's local *calendar days*, not rolling periods or UTC midnights. Day numbers
 * are local dates expressed as days since the epoch, so successive dates stay consecutive across
 * daylight-saving changes. The time zone can be supplied explicitly in tests.
 */
data class PetStreak(
    /** The last day the person was here, as a day number; `null` before the first visit. */
    val lastDay: Long? = null,
    /** Days in a row ending at [lastDay]. */
    val days: Int = 0,
    /** The longest run ever, so a bad week does not erase what was earned. */
    val best: Int = 0,
) {
    companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L

        /** The local date containing this instant, represented as an epoch day. */
        fun dayOf(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long {
            val utcDay = Math.floorDiv(epochMillis, DAY_MS)
            val millisInUtcDay = Math.floorMod(epochMillis, DAY_MS)
            // Add the offset only to the within-day part: adding it to an extreme Long instant
            // could overflow. getOffset uses the offset in force at this particular instant.
            return utcDay + Math.floorDiv(millisInUtcDay + zone.getOffset(epochMillis), DAY_MS)
        }

        /** The UTC day written by Pet versions predating local-calendar support. */
        fun legacyUtcDayOf(epochMillis: Long): Long = Math.floorDiv(epochMillis, DAY_MS)

        /**
         * The streak after a visit on [day].
         *
         * Three cases, and the middle one is the whole feature:
         * - the same day again: unchanged, so opening the app twice does not double-count;
         * - the next day: extended, which is what a streak *is*;
         * - any later day: restarted at one. A gap resets rather than freezing, because a streak
         *   that resumes after a week is not a streak.
         *
         * A day number that went backwards (a clock change, a restored backup) is treated as
         * "already counted": it must never shorten what the person earned.
         */
        fun afterVisit(streak: PetStreak, day: Long): PetStreak {
            val last = streak.lastDay
            if (last == null) {
                return PetStreak(lastDay = day, days = 1, best = maxOf(streak.best, 1))
            }
            if (day <= last) return streak
            val next = if (day == last + 1) {
                if (streak.days == Int.MAX_VALUE) Int.MAX_VALUE else streak.days + 1
            } else {
                1
            }
            return PetStreak(lastDay = day, days = next, best = maxOf(streak.best, next))
        }

        /**
         * Whether a streak is still alive on [day] without a visit.
         *
         * A streak from yesterday is alive (today is not over yet); from before that it is broken.
         * The UI uses this to show "3 days" in the past tense rather than a lie.
         */
        fun isAlive(streak: PetStreak, day: Long): Boolean {
            val last = streak.lastDay ?: return false
            // A clock or time zone moving back must not hide an earned streak either.
            return day <= last || (last < Long.MAX_VALUE && day == last + 1)
        }

        /** The streak as it should be *shown* on [day]: zero once it is no longer alive. */
        fun shownOn(streak: PetStreak, day: Long): Int = if (isAlive(streak, day)) streak.days else 0
    }
}

/**
 * What the Pet remembers about the person's care, as short facts the UI can say out loud.
 *
 * Memory here means "a few counters that survive a restart", not a model of the person. Keeping it
 * to named facts is deliberate: it can be shown, tested and erased, and it cannot quietly grow into
 * a profile of somebody's behaviour.
 */
data class PetMemory(
    /** Total times the person fed, rested or played, all three together. */
    val careCount: Int = 0,
    /** The last care action taken, so the Pet can refer to it. */
    val lastCare: PetCareAction? = null,
    /** The day the Pet was first seen. */
    val firstDay: Long? = null,
    /** Total finished turns ever paid, so the Pet can say how much it has worked. */
    val turnsTogether: Int = 0,
) {
    /** A care action, remembered. */
    fun afterCare(action: PetCareAction): PetMemory = copy(
        careCount = increment(careCount),
        lastCare = action,
    )

    fun afterTurn(): PetMemory = copy(turnsTogether = increment(turnsTogether))

    fun firstSeen(day: Long): PetMemory = if (firstDay == null) copy(firstDay = day) else this

    /** How many days the Pet and the person have known each other, counting today as day one. */
    fun daysKnown(day: Long): Int {
        val first = firstDay ?: return 1
        return (day - first).coerceAtLeast(0).let { if (it > MAX_DAYS) MAX_DAYS else it.toInt() } + 1
    }

    companion object {
        /** Counters are capped so a long-lived account cannot overflow the copy or the layout. */
        const val MAX_COUNT = 1_000_000
        const val MAX_DAYS = 100_000

        private fun increment(value: Int): Int =
            if (value >= MAX_COUNT) MAX_COUNT else value.coerceAtLeast(0) + 1
    }
}
