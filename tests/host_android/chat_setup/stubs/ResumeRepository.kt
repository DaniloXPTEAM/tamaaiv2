package testfixtures.resume

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred

/** Fake writes can block without cooperating with cancellation; no Room semantics are implied. */
class ResumeRepository(var blockClear: Boolean = false, var blockAppend: Boolean = false) {
    val clearEntered = CompletableDeferred<Unit>()
    val appendEntered = CompletableDeferred<Unit>()
    val clearDone = CompletableDeferred<Unit>()
    val appendDone = CompletableDeferred<Unit>()
    val releaseClear = CountDownLatch(1)
    val releaseAppend = CountDownLatch(1)
    val cleared = Collections.synchronizedList(mutableListOf<String>())
    val appended = Collections.synchronizedList(mutableListOf<String>())

    @Suppress("UNUSED_PARAMETER")
    suspend fun updateLastAssistantError(sessionId: String, error: String?) {
        clearEntered.complete(Unit)
        if (blockClear) check(releaseClear.await(5, TimeUnit.SECONDS))
        cleared.add(sessionId)
        clearDone.complete(Unit)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun appendMessage(sessionId: String, role: String, partsJson: String) {
        appendEntered.complete(Unit)
        if (blockAppend) check(releaseAppend.await(5, TimeUnit.SECONDS))
        appended.add(sessionId)
        appendDone.complete(Unit)
    }
}
