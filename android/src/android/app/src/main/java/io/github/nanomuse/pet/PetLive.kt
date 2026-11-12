package io.github.nanomuse.pet

// Pure Kotlin only: this object is read from Compose, but it must stay testable off-device, so it
// takes booleans rather than a ViewModel.

/**
 * What the agent is doing *right now*, as the Pet sees it.
 *
 * Item 2.3 of the plan. Until this existed the Pet only reacted after a turn ended — a report,
 * not a companion. The app already knows whether a session is streaming and whether it failed;
 * this is the seam that turns that into a face, so the Pet looks up while the work happens and
 * settles when it stops.
 *
 * It holds no state of its own and starts no timer: it is a function of what the caller already
 * observes. The one thing remembered is the *afterglow* of a finished turn, which is a short
 * value the caller passes in (see `rememberPetPresence`), so nothing here can drift or leak.
 */
object PetLive {

    /** How the Pet is being, for the face and for the one-line status. */
    enum class Presence {
        /** Nobody is working; the Pet is whatever its needs make it. */
        RESTING,

        /** A turn is running: the Pet watches the work. */
        WORKING,

        /** A turn just ended well: a brief, bright moment. */
        PLEASED,

        /** A turn just failed: a brief wince. */
        WINCED,
        ;

        /** Whether this presence is a short reaction rather than a resting state. */
        val isAfterglow: Boolean get() = this == PLEASED || this == WINCED
    }

    /**
     * The presence for a moment.
     *
     * Precedence: work beats everything, because the face has to follow the action rather than an
     * old reaction; then a failure, then a success; and only with nothing happening does the
     * afterglow count down. That order is what stops a wince from outliving the next turn.
     */
    fun presence(
        streaming: Boolean,
        failed: Boolean = false,
        afterglow: Presence? = null,
    ): Presence = when {
        streaming -> Presence.WORKING
        failed -> Presence.WINCED
        afterglow != null -> afterglow
        else -> Presence.RESTING
    }

    /**
     * The Pet's mood, combining the live presence with its needs.
     *
     * The rule that matters: a low need never *interrupts* work (the Pet watches the agent
     * regardless), but work also never hides a need once the work stops — so a Pet whose energy is
     * gone looks needy again the moment the turn ends, instead of staying bright.
     */
    fun mood(
        presence: Presence,
        needs: PetNeeds,
        aloneHours: Long = 0,
        recentSuccess: Boolean = false,
        recentFailure: Boolean = false,
    ): PetMood = PetMood.of(
        needs = needs,
        working = presence == Presence.WORKING,
        recentSuccess = recentSuccess || presence == Presence.PLEASED,
        recentFailure = recentFailure || presence == Presence.WINCED,
        aloneHours = aloneHours,
    )

    /** How long a finished turn's expression stays on screen, in milliseconds. */
    const val AFTERGLOW_MS = 2_400L

    /** The afterglow a finished turn earns; `null` when it earns none (a cancel). */
    fun afterglowFor(outcome: PetTurnOutcome): Presence? = when (outcome) {
        PetTurnOutcome.SUCCESS -> Presence.PLEASED
        PetTurnOutcome.FAILURE -> Presence.WINCED
        PetTurnOutcome.CANCELLED, PetTurnOutcome.UNKNOWN -> null
    }
}
