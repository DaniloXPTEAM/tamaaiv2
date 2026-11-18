package com.openminis.app.data.repository

import com.openminis.app.data.db.ChatSessionEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred

/** Simulates a published row and optionally an insert that ignores coroutine cancellation. */
class FixtureDao(var blockInsert: Boolean = false) {
    val rowVisible = CompletableDeferred<String>()
    val releaseInsert = CountDownLatch(1)
    @Volatile var inserted = false
        private set

    suspend fun insertSession(session: ChatSessionEntity) {
        inserted = true
        rowVisible.complete(session.id)
        if (blockInsert) check(releaseInsert.await(5, TimeUnit.SECONDS))
    }
}
