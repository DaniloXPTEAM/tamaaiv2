package io.github.nanomuse.pet

/**
 * What the Pet is wearing and where it is standing.
 *
 * SL-001. The point is not new art: the app already ships a face system (a drawn dragon with five
 * expressions, four preset characters and an emoji look), and the person already chooses one of
 * those for the agent. An outfit is a *scene* laid over whichever face is current, so the Pet can
 * be dressed without becoming a second character renderer — the failure mode this project keeps
 * out of the tree on purpose.
 *
 * Each outfit is therefore two things and no more: a backdrop (a colour, from the app's own
 * palette) and an accessory (one emoji, drawn the same way the emoji look already is). Both are
 * data, so adding a fifth outfit is one line here and no new asset pipeline.
 *
 * Pure: no Android. The screen maps [PetOutfit.backdrop] to a colour and draws the accessory.
 */
data class PetOutfit(
    val id: String,
    /** Which backdrop the scene paints behind the face. */
    val backdrop: PetBackdrop,
    /** The accessory emoji worn at the face's corner, or `null` for none. */
    val accessory: String?,
    /**
     * The level at which the Pet may wear it.
     *
     * Progression earns *choice*, never power: an outfit changes nothing about the numbers. A
     * locked outfit is still visible in the list, greyed, with what it needs — a reward the person
     * can see is a reward; a hidden one is just a missing feature.
     */
    val unlockLevel: Int = 1,
) {
    companion object {
        /** The default: no outfit at all, which is what a new Pet wears. */
        val NONE = PetOutfit(id = "none", backdrop = PetBackdrop.NONE, accessory = null)

        /**
         * The catalogue, using what the app already ships.
         *
         * The four presets are the characters the avatar studio offers (copper acorn, dusk moth,
         * moss courier, tide tinker); their accessories name the same motif, so choosing an outfit
         * reads as dressing the character the person already picked rather than as a new system.
         * The last two are earned.
         */
        val ALL: List<PetOutfit> = listOf(
            NONE,
            PetOutfit("spark", PetBackdrop.DAWN, "\u2728"),
            PetOutfit("acorn", PetBackdrop.MOSS, "\uD83C\uDF30", unlockLevel = 2),
            PetOutfit("moth", PetBackdrop.DUSK, "\uD83E\uDD8B", unlockLevel = 3),
            PetOutfit("courier", PetBackdrop.MOSS, "\uD83C\uDF92", unlockLevel = 4),
            PetOutfit("tinker", PetBackdrop.TIDE, "\uD83D\uDD27", unlockLevel = 5),
            PetOutfit("guardian", PetBackdrop.DUSK, "\uD83D\uDEE1\uFE0F", unlockLevel = 6),
            PetOutfit("legend", PetBackdrop.DAWN, "\uD83D\uDC51", unlockLevel = 10),
        )

        fun byId(id: String?): PetOutfit = ALL.firstOrNull { it.id == id } ?: NONE

        /** What the person may wear at [level]; the rest are shown locked. */
        fun unlocked(level: Int): List<PetOutfit> =
            ALL.filter { level >= it.unlockLevel }

        fun isUnlocked(outfit: PetOutfit, level: Int): Boolean = level >= outfit.unlockLevel
    }
}

/**
 * The scene behind the face. A named palette entry rather than a hex string, so the dark theme and
 * the app's existing tones stay one source of truth at the call site.
 */
enum class PetBackdrop {
    /** No backdrop: the screen's own canvas shows through. */
    NONE,
    DAWN,
    MOSS,
    DUSK,
    TIDE,
    ;

    /** Whether this backdrop paints anything at all. */
    val paints: Boolean get() = this != NONE
}

/**
 * The outfit the person chose, and what they are allowed to wear.
 *
 * Kept separate from [PetOutfit] so a locked choice is representable: the app must remember that
 * somebody picked "legend" while at level 3 and show it greyed, not silently swap it for "none" —
 * losing a choice is worse than showing it as not yet available.
 */
data class PetWardrobe(
    val chosenId: String = PetOutfit.NONE.id,
) {
    /** The outfit actually worn at [level]: the choice when it is unlocked, otherwise none. */
    fun wornAt(level: Int): PetOutfit {
        val chosen = PetOutfit.byId(chosenId)
        return if (PetOutfit.isUnlocked(chosen, level)) chosen else PetOutfit.NONE
    }

    /** The chosen outfit even while locked, for the list to mark as "wanted". */
    val chosen: PetOutfit get() = PetOutfit.byId(chosenId)

    fun choose(outfit: PetOutfit): PetWardrobe = copy(chosenId = outfit.id)

    companion object {
        fun fromStorage(raw: String?): PetWardrobe =
            PetWardrobe(chosenId = PetOutfit.byId(raw).id)
    }
}
