package io.github.nanomuse.pet

import java.util.Base64

/** Dates are wall-clock instants; elapsed thresholds are not calendar days or a fixed time zone. */
data class PetLife(
    val generation: Long = 1L,
    val bornAt: Long = 0L,
    val lastFedAt: Long = 0L,
    val deathAt: Long? = null,
    val name: String = "TAMA AI",
) {
    val grieving: Boolean get() = deathAt != null
}

object PetLifeRules {
    const val DEATH_AFTER_MS = 72L * PetDecay.HOUR_MS
    const val GRIEF_MS = 72L * PetDecay.HOUR_MS

    private fun plusSaturated(value: Long, amount: Long): Long =
        if (value > Long.MAX_VALUE - amount) Long.MAX_VALUE else value + amount

    fun deathAt(lastFedAt: Long): Long = plusSaturated(lastFedAt.coerceAtLeast(0L), DEATH_AFTER_MS)
    fun griefEndsAt(deathAt: Long): Long = plusSaturated(deathAt.coerceAtLeast(0L), GRIEF_MS)

    /** Once death has been observed it stays latched even if the phone clock is moved back. */
    fun reconcile(life: PetLife, now: Long): PetLife {
        if (life.deathAt != null || now < deathAt(life.lastFedAt)) return life
        return life.copy(deathAt = deathAt(life.lastFedAt))
    }

    fun griefFinished(life: PetLife, now: Long): Boolean =
        life.deathAt?.let { now >= griefEndsAt(it) } ?: false

    fun untilDeath(life: PetLife, now: Long): Long {
        val due = deathAt(life.lastFedAt)
        return if (now >= due) 0L else if (now < 0L && due > Long.MAX_VALUE + now) Long.MAX_VALUE else due - now
    }
}

/** A compact, local record of the Pet before a new generation starts. */
data class PetArchive(
    val life: PetLife,
    val progress: PetProgress,
    val needs: PetNeeds,
    val wardrobe: PetWardrobe,
    val streak: PetStreak,
    val memory: PetMemory,
    val curiosity: Int,
    val diary: PetDiaryEntry? = null,
)

/**
 * Versioned line records, with a UTF-8 name encoded so separators cannot corrupt other records.
 * Appending preserves unknown or damaged lines, allowing a newer writer to recover them later.
 */
object PetArchiveCodec {
    fun append(raw: String?, archive: PetArchive): String =
        listOfNotNull(raw?.takeIf { it.isNotEmpty() }, encode(archive)).joinToString("\n")

    fun decode(raw: String?): List<PetArchive> = raw.orEmpty().lineSequence().mapNotNull(::decodeLine).toList()

    private fun encode(a: PetArchive): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val name = encoder.encodeToString(a.life.name.toByteArray(Charsets.UTF_8))
        val diary = a.diary?.encode()?.let { encoder.encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()
        return listOf(
            "2", a.life.generation, name, a.life.bornAt, a.life.lastFedAt, a.life.deathAt ?: -1L,
            a.progress.xp, a.needs.satiety, a.needs.energy, a.needs.mood, a.needs.bond,
            a.wardrobe.chosenId, a.streak.lastDay ?: -1L, a.streak.days, a.streak.best,
            a.memory.careCount, a.memory.lastCare?.name ?: "", a.memory.firstDay ?: -1L,
            a.memory.turnsTogether, a.curiosity, diary,
        ).joinToString("|")
    }

    private fun decodeLine(line: String): PetArchive? = runCatching {
        val p = line.split('|')
        require((p.size == 20 && p[0] == "1") || (p.size == 21 && p[0] == "2"))
        val decoder = Base64.getUrlDecoder()
        val name = String(decoder.decode(p[2]), Charsets.UTF_8)
        val diary = if (p.size == 21 && p[20].isNotEmpty()) {
            PetDiaryEntry.decode(String(decoder.decode(p[20]), Charsets.UTF_8))
        } else null
        val death = p[5].toLong().takeIf { it >= 0L }
        require(death != null)
        PetArchive(
            life = PetLife(p[1].toLong(), p[3].toLong(), p[4].toLong(), death, name),
            progress = PetLadder.progressFor(p[6].toLong()),
            needs = PetNeeds(p[7].toInt(), p[8].toInt(), p[9].toInt(), p[10].toInt()),
            wardrobe = PetWardrobe.fromStorage(p[11]),
            streak = PetStreak(p[12].toLong().takeIf { it >= 0L }, p[13].toInt(), p[14].toInt()),
            memory = PetMemory(
                careCount = p[15].toInt(),
                lastCare = PetCareAction.entries.firstOrNull { it.name == p[16] },
                firstDay = p[17].toLong().takeIf { it >= 0L },
                turnsTogether = p[18].toInt(),
            ),
            curiosity = p[19].toInt(),
            diary = diary,
        )
    }.getOrNull()
}
