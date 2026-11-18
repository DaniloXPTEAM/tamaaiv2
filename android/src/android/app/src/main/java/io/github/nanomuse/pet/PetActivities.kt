package io.github.nanomuse.pet

/** These activities are manual and local: none schedules work or calls a model. */
enum class PetActivity(val energyCost: Int) {
    DREAM(2), EXPLORE(5), EXPERIMENT(5), JOURNAL(2),
}

data class PetActivityResult(val needs: PetNeeds, val curiosity: Int)

data class PetDiaryEntry(val at: Long, val level: Int, val energy: Int, val bond: Int) {
    fun encode(): String = listOf(at, level, energy, bond).joinToString("|")

    companion object {
        fun decode(raw: String?): PetDiaryEntry? = runCatching {
            val p = raw.orEmpty().split('|')
            require(p.size == 4)
            PetDiaryEntry(p[0].toLong(), p[1].toInt(), p[2].toInt(), p[3].toInt())
        }.getOrNull()
    }
}

object PetActivities {
    /** Null means there is not enough energy; the caller must not change anything. */
    fun perform(activity: PetActivity, needs: PetNeeds, curiosity: Int): PetActivityResult? {
        if (needs.energy < activity.energyCost) return null
        var next = needs.with(PetNeed.ENERGY, needs.energy - activity.energyCost)
        if (activity == PetActivity.EXPERIMENT) next = next.with(PetNeed.MOOD, next.mood + 2)
        val nextCuriosity = (curiosity.coerceIn(0, 100) + if (activity == PetActivity.EXPLORE) 2 else 0)
            .coerceAtMost(100)
        return PetActivityResult(next, nextCuriosity)
    }
}

enum class PetMiniGame { COLOR_ECHO, STEP_PATTERN }
enum class PetColor { BLUE, YELLOW, GREEN }

object PetMiniGames {
    val colorSequence = listOf(PetColor.BLUE, PetColor.YELLOW, PetColor.GREEN)
    val stepOptions = listOf(7, 8, 9)

    fun won(game: PetMiniGame, colors: List<PetColor>, number: Int?): Boolean = when (game) {
        PetMiniGame.COLOR_ECHO -> colors == colorSequence && number == null
        PetMiniGame.STEP_PATTERN -> colors.isEmpty() && number == 8
    }
}
