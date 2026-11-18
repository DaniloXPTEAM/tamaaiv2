package ai.tama.chat

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Register a chat setup before starting it, so session cleanup can invalidate
 * it even if its dispatcher has not run it yet. These helpers do not cancel or
 * retract already-sent network requests. Callbacks must be safe to invoke from
 * the thread that completes a job; UI callbacks should dispatch to Main.
 */
internal object ChatSetupLaunch {
    /** A late completion may update UI only while it still owns the current job. */
    fun mayUpdateOwner(enabled: Boolean, owner: Job?, current: Job?): Boolean =
        !enabled || (owner != null && owner === current)

    fun launch(
        scope: CoroutineScope,
        sessionId: String,
        enabled: Boolean,
        onOwner: (Job) -> Unit,
        onNeverStarted: (Job) -> Unit,
        lazyInNormal: Boolean = false,
        watchPromotion: Boolean = false,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        // Preserve the original scheduling and ownership for the normal package.
        if (!enabled) {
            if (!lazyInNormal) return scope.launch(block = block)
            val job = scope.launch(start = CoroutineStart.LAZY, block = block)
            onOwner(job)
            job.start()
            return job
        }

        val reservation = ChatSessionJobs.reserve(sessionId, watchPromotion = watchPromotion)
        val entered = AtomicBoolean(false)
        val job = try {
            scope.launch(start = CoroutineStart.LAZY) {
                coroutineContext.ensureActive()
                entered.set(true)
                block()
            }
        } catch (failure: Throwable) {
            reservation.close()
            throw failure
        }
        try {
            onOwner(job)
            job.invokeOnCompletion {
                if (!entered.get()) onNeverStarted(job)
            }
            reservation.bind(job)
            job.start()
        } catch (failure: Throwable) {
            reservation.close()
            job.cancel()
            throw failure
        }
        return job
    }

    /**
     * A child stream is published before it starts in the parallel package.
     * Its completion callback covers cancellation before the body is entered.
     * The caller chooses the scope and must retain setup as the parent there.
     */
    fun launchStream(
        scope: CoroutineScope,
        enabled: Boolean,
        onOwner: (Job) -> Unit,
        onFinished: (Job) -> Unit,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val job = scope.launch(
            Dispatchers.IO,
            start = if (enabled) CoroutineStart.LAZY else CoroutineStart.DEFAULT,
            block = block,
        )
        onOwner(job)
        if (enabled) {
            job.invokeOnCompletion { onFinished(job) }
            job.start()
        }
        return job
    }
}
