package ai.tama.chat

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Coordinates a provisional binding write with checks on the owning turn and
 * the live selection. The caller must include the original Job's liveness in
 * `isCurrent`, even though cleanup runs in a non-cancellable context. Neither
 * a local write version nor a value-only database CAS detects an external writer
 * that changes the row away and back to exactly the same value.
 */
internal object FallbackBindingConfirmation {
    enum class Outcome { SKIPPED, NOT_WRITTEN, CONFIRMED, RESTORED, RESTORE_SKIPPED }

    suspend fun confirm(
        isCurrent: () -> Boolean,
        write: suspend () -> Long?,
        accept: () -> Boolean,
        restore: suspend (Long) -> Boolean,
    ): Outcome = withContext(NonCancellable) {
        if (!isCurrent()) return@withContext Outcome.SKIPPED
        val version = write() ?: return@withContext Outcome.NOT_WRITTEN
        val accepted = try {
            isCurrent() && accept()
        } catch (error: Throwable) {
            // A failed post-write check is not confirmation. Attempt compensation
            // without hiding the original failure if compensation also fails.
            try { restore(version) } catch (restoreError: Throwable) { error.addSuppressed(restoreError) }
            throw error
        }
        if (accepted) Outcome.CONFIRMED
        else if (restore(version)) Outcome.RESTORED else Outcome.RESTORE_SKIPPED
    }
}
