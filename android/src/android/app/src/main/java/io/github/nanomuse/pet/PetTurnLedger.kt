package io.github.nanomuse.pet

/**
 * A bounded history of random identifiers for completed tracker runs.
 *
 * A run receives its identifier when it starts, not from the wall clock when it ends. Keeping a
 * recent history rather than only the last identifier also rejects out-of-order repeats. The
 * store writes this history and the Pet's updated state in the same preferences edit.
 *
 * This is deliberately bounded; a repeat older than [MAX_RECENT_TURNS] distinct runs may be
 * paid again. Tracker runs can encompass queued prompts, so this is not a durable per-message
 * ledger and does not establish parity with the separate Pet B3 implementation.
 */
object PetTurnLedger {
    const val MAX_RECENT_TURNS = 256
    private const val MAX_ID_LENGTH = 128

    fun validId(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX_ID_LENGTH &&
            id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    /** Invalid or damaged entries are ignored; newer entries are kept when the history is full. */
    fun decode(raw: String?): List<String> = raw.orEmpty().lineSequence()
        .filter(::validId)
        .distinct()
        .toList()
        .takeLast(MAX_RECENT_TURNS)

    fun shouldRecord(id: String, recent: List<String>): Boolean = validId(id) && id !in recent

    fun encodeWith(id: String, recent: List<String>): String {
        require(shouldRecord(id, recent))
        return (recent + id).takeLast(MAX_RECENT_TURNS).joinToString("\n")
    }
}
