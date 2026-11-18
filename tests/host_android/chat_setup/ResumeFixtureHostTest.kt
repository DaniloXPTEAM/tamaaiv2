package ai.tama.chat

import com.openminis.app.BuildConfig
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import testfixtures.resume.LLMMessage
import testfixtures.resume.ResumeFixture
import testfixtures.resume.ResumeRepository

/** Extracted resume preparation, with fake persistence and no real stream, VM, or Android. */
class ResumeFixtureHostTest {
    private fun id() = UUID.randomUUID().toString()
    private fun parallel() { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }

    @Test fun cleanupBeforeDispatchDoesNotWriteOrAddAReminder() = runBlocking {
        parallel()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + dispatcher)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val session = id()
        val repo = ResumeRepository()
        val fixture = ResumeFixture(scope, repo, session)
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            fixture.resume()
            val owner = checkNotNull(fixture.streamJob)
            assertTrue(fixture._isStreaming.value)
            ChatSessionJobs.pauseSession(session)
            ChatSessionJobs.resumeSession(session)
            release.countDown()
            withTimeout(5_000) { owner.join(); fixture._isStreaming.first { !it } }
            assertTrue(owner.isCancelled)
            assertTrue(repo.cleared.isEmpty())
            assertTrue(repo.appended.isEmpty())
            assertEquals(1, fixture.agentHistory.size)
            assertFalse(fixture.streamStarted.isCompleted)
        } finally {
            release.countDown()
            ChatSessionJobs.resumeSession(session)
            scopeJob.cancel()
            dispatcher.close()
        }
    }

    @Test fun cancelledSlowErrorClearCannotStartTheReminderWrite() = runBlocking {
        parallel()
        val session = id()
        val scopeJob = SupervisorJob()
        val repo = ResumeRepository(blockClear = true)
        val fixture = ResumeFixture(CoroutineScope(scopeJob + Dispatchers.Default), repo, session)
        try {
            fixture.resume()
            val owner = checkNotNull(fixture.streamJob)
            withTimeout(5_000) { repo.clearEntered.await() }
            ChatSessionJobs.pauseSession(session)
            ChatSessionJobs.resumeSession(session)
            repo.releaseClear.countDown()
            withTimeout(5_000) { owner.join() }
            assertTrue(owner.isCancelled)
            assertEquals(listOf(session), repo.cleared.toList()) // fake write was already in flight
            assertTrue(repo.appended.isEmpty())
            assertEquals(1, fixture.agentHistory.size)
            assertFalse(fixture.streamStarted.isCompleted)
            assertFalse(fixture._isStreaming.value)
        } finally {
            repo.releaseClear.countDown()
            ChatSessionJobs.resumeSession(session)
            scopeJob.cancel()
        }
    }

    @Test fun cancelledSlowReminderMayPersistButCannotStartTheStream() = runBlocking {
        parallel()
        val session = id()
        val scopeJob = SupervisorJob()
        val repo = ResumeRepository(blockAppend = true)
        val fixture = ResumeFixture(CoroutineScope(scopeJob + Dispatchers.Default), repo, session)
        try {
            fixture.resume()
            val owner = checkNotNull(fixture.streamJob)
            withTimeout(5_000) { repo.appendEntered.await() }
            ChatSessionJobs.pauseSession(session)
            ChatSessionJobs.resumeSession(session)
            repo.releaseAppend.countDown()
            withTimeout(5_000) { owner.join() }
            assertTrue(owner.isCancelled)
            assertEquals(listOf(session), repo.appended.toList()) // fake in-flight write is not undone
            assertEquals(1, fixture.agentHistory.size) // cancelled before in-memory publication
            assertFalse(fixture.streamStarted.isCompleted)
            assertFalse(fixture._isStreaming.value)
        } finally {
            repo.releaseAppend.countDown()
            ChatSessionJobs.resumeSession(session)
            scopeJob.cancel()
        }
    }

    @Test fun anOlderCancelledSetupCannotHideANewerTurn() = runBlocking {
        parallel()
        val session = id()
        val scopeJob = SupervisorJob()
        val repo = ResumeRepository(blockClear = true)
        val fixture = ResumeFixture(CoroutineScope(scopeJob + Dispatchers.Default), repo, session)
        val newerOwner = Job()
        try {
            fixture.resume()
            val oldOwner = checkNotNull(fixture.streamJob)
            withTimeout(5_000) { repo.clearEntered.await() }
            ChatSessionJobs.pauseSession(session)
            ChatSessionJobs.resumeSession(session)
            fixture.streamJob = newerOwner // a later turn has claimed the UI
            fixture._isStreaming.value = true
            repo.releaseClear.countDown()
            withTimeout(5_000) { oldOwner.join() }
            assertTrue(oldOwner.isCancelled)
            assertTrue(newerOwner.isActive)
            assertTrue(fixture.streamJob === newerOwner)
            assertTrue(fixture._isStreaming.value)
            assertFalse(fixture.streamStarted.isCompleted)
        } finally {
            repo.releaseClear.countDown()
            ChatSessionJobs.resumeSession(session)
            newerOwner.cancel()
            scopeJob.cancel()
        }
    }

    @Test fun successfulResumePersistsBeforePublishingTheReminder() = runBlocking {
        parallel()
        val session = id()
        val scopeJob = SupervisorJob()
        val repo = ResumeRepository()
        val fixture = ResumeFixture(CoroutineScope(scopeJob + Dispatchers.Default), repo, session)
        try {
            fixture.resume()
            val owner = checkNotNull(fixture.streamJob)
            withTimeout(5_000) { owner.join() }
            assertFalse(owner.isCancelled)
            assertEquals(listOf(session), repo.cleared.toList())
            assertEquals(listOf(session), repo.appended.toList())
            assertEquals(2, fixture.agentHistory.size)
            assertEquals(LLMMessage.Role.USER, fixture.agentHistory.last().role)
            assertTrue(fixture.streamStarted.isCompleted)
        } finally { scopeJob.cancel() }
    }

    @Test fun resumeWithoutAnAssistantTailDoesNotWriteAReminder() = runBlocking {
        parallel()
        val session = id()
        val scopeJob = SupervisorJob()
        val repo = ResumeRepository()
        val fixture = ResumeFixture(CoroutineScope(scopeJob + Dispatchers.Default), repo, session)
        fixture.agentHistory.clear()
        fixture.agentHistory.add(LLMMessage(LLMMessage.Role.USER, "tool result"))
        try {
            fixture.resume()
            val owner = checkNotNull(fixture.streamJob)
            withTimeout(5_000) { owner.join() }
            assertEquals(listOf(session), repo.cleared.toList())
            assertTrue(repo.appended.isEmpty())
            assertEquals(1, fixture.agentHistory.size)
            assertTrue(fixture.streamStarted.isCompleted)
        } finally { scopeJob.cancel() }
    }

    @Test fun normalPackageKeepsIndependentWritesBeforeTheQueuedSetup() = runBlocking {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + dispatcher)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val session = id()
        val repo = ResumeRepository()
        val fixture = ResumeFixture(scope, repo, session)
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            fixture.resume()
            withTimeout(5_000) { repo.clearDone.await(); repo.appendDone.await() }
            assertEquals(2, fixture.agentHistory.size) // original synchronous history update
            assertFalse(fixture.streamStarted.isCompleted)
            ChatSessionJobs.pauseSession(session)
            ChatSessionJobs.resumeSession(session)
            release.countDown()
            withTimeout(5_000) { fixture.streamStarted.await() }
        } finally {
            release.countDown()
            ChatSessionJobs.resumeSession(session)
            scopeJob.cancel()
            dispatcher.close()
            parallel()
        }
    }
}
