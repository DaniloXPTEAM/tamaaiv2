package io.github.nanomuse.pet

/**
 * Decides *whether* a finished turn still owes XP, so the same turn is never paid twice.
 *
 * This exists because of a real hazard in the app: the "turn ended" signal is observed from a
 * Compose effect that can be re-entered after a configuration change (rotation, theme change,
 * a window resize), and from a process that can be restarted. Paying directly from that signal
 * would pay twice for one turn — invisible, and it inflates the level.
 *
 * The rule is one line of state: the last turn id that was paid, remembered across process
 * restarts. A turn id is the session id plus the assistant message id, both already in the
 * database, so nothing new is generated and nothing needs a clock.
 *
 * Pure: no Android, no I/O. The caller supplies what was stored.
 */
object PetTurnLedger {

    /** The value handed to the ledger for a finished turn. Ids come from the app's own records. */
    data class TurnKey(val sessionId: String, val messageId: String) {
        /** A stable string form for persistence; `|` cannot appear in either id. */
        val storageKey: String get() = "$sessionId|$messageId"

        companion object {
            /** Rebuilds a key from storage; `null` when the record is unusable. */
            fun fromStorage(raw: String?): TurnKey? {
                if (raw.isNullOrBlank()) return null
                val cut = raw.indexOf('|')
                if (cut <= 0 || cut == raw.length - 1) return null
                val session = raw.substring(0, cut)
                val message = raw.substring(cut + 1)
                if (session.isBlank() || message.isBlank()) return null
                return TurnKey(session, message)
            }
        }
    }

    /**
     * Whether [turn] still has to be paid, given the last key [lastPaid] that was.
     *
     * Only an exact repeat is refused. A different turn — even in the same session — is always
     * paid, so a user who sends two messages in a row earns twice. A *missing* last key always
     * pays: losing the record must not lock a user out of earning.
     */
    fun shouldPay(turn: TurnKey?, lastPaid: TurnKey?): Boolean {
        if (turn == null) return false
        if (turn.sessionId.isBlank() || turn.messageId.isBlank()) return false
        return lastPaid != turn
    }

    /** The key to store after paying [turn]; `null` input stores nothing. */
    fun nextStored(turn: TurnKey?): String? = turn?.takeIf {
        it.sessionId.isNotBlank() && it.messageId.isNotBlank()
    }?.storageKey
}
