package com.openminis.app.offload

import io.github.nanomuse.pet.PetPrefsContext
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffloadPermissionQueueTest {
    private val manager = OffloadPermissionManager

    private fun respond(response: OffloadPermissionManager.Response) {
        val request = requireNotNull(manager.pendingRequest.value)
        manager.respondToRequest(request.requestId, response)
    }

    @Test fun `image generation is registered and asks by default`() {
        manager.init(PetPrefsContext())
        assertEquals(OffloadPermissionManager.PermissionLevel.ASK_ONCE, manager.getLevel("image_generation"))
        assertEquals(OffloadPermissionManager.PermissionLevel.BYPASS, manager.getLevel("unknown_tool"))
        assertTrue(manager.toolRegistry.single { it.toolName == "image_generation" }.showInSettings)
    }

    @Test fun `concurrent tools get separate dialogs and cannot steal an answer`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("calendar", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        val image = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "Image", session)
        }
        val calendar = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("calendar", "Calendar", session)
        }
        assertEquals("image_generation", manager.pendingRequest.value?.toolName)
        respond(OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(image.await())
        withTimeout(2_000) { manager.pendingRequest.first { it?.toolName == "calendar" } }
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(calendar.await())
        assertFalse(manager.checkPermission("image_generation", "Image", session))
        manager.clearSessionGrants(session)
    }

    @Test fun `late answer to a cancelled dialog cannot approve the next request`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "first", session)
        }
        val oldId = requireNotNull(manager.pendingRequest.value).requestId
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "second", session)
        }
        first.cancelAndJoin()
        val current = withTimeout(2_000) { manager.pendingRequest.first { it?.toolTitle == "second" } }!!
        assertTrue(oldId != current.requestId)
        manager.respondToRequest(oldId, OffloadPermissionManager.Response.ALLOW_SESSION)
        assertEquals(current.requestId, manager.pendingRequest.value?.requestId)
        assertFalse(next.isCompleted)
        manager.respondToRequest(current.requestId, OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(next.await())
        manager.clearSessionGrants(session)
    }

    @Test fun `duplicate answer cannot approve a later dialog`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("calendar", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("calendar", "calendar", session)
        }
        val oldId = requireNotNull(manager.pendingRequest.value).requestId
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "image", session)
        }
        manager.respondToRequest(oldId, OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(first.await())
        val current = withTimeout(2_000) { manager.pendingRequest.first { it?.toolName == "image_generation" } }!!
        manager.respondToRequest(oldId, OffloadPermissionManager.Response.ALLOW_ONCE)
        assertEquals(current.requestId, manager.pendingRequest.value?.requestId)
        assertFalse(next.isCompleted)
        manager.respondToRequest(current.requestId, OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(next.await())
        manager.clearSessionGrants(session)
    }

    @Test fun `legacy image calls require a fresh yes even with a session grant or bypass`() = runBlocking {
        manager.init(PetPrefsContext())
        val session = UUID.randomUUID().toString()
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.BYPASS)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "Image", session, requireFreshApproval = true)
        }
        assertTrue(manager.pendingRequest.value?.oneTimeOnly == true)
        respond(OffloadPermissionManager.Response.ALLOW_SESSION)
        assertTrue(first.await())
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "Image", session, requireFreshApproval = true)
        }
        assertTrue(manager.pendingRequest.value?.oneTimeOnly == true)
        respond(OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(second.await())
        assertFalse(manager.checkPermission("image_generation", "Image", session, requireFreshApproval = true))
        manager.clearSessionGrants(session)
    }

    @Test fun `changing settings invalidates old grants and an answer to an old dialog`() = runBlocking {
        manager.init(PetPrefsContext())
        val session = UUID.randomUUID().toString()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "Image", session)
        }
        respond(OffloadPermissionManager.Response.ALLOW_SESSION)
        assertTrue(first.await())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "Image", session)
        }
        assertEquals("image_generation", manager.pendingRequest.value?.toolName)
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertFalse(next.await())
        manager.clearSessionGrants(session)
    }

    @Test fun `scoped session grants do not leak and settings invalidate every scope`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        suspend fun ask(scope: String, answer: OffloadPermissionManager.Response) {
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                manager.checkPermission("image_generation", "Image", session, grantScope = scope)
            }
            assertFalse(request.isCompleted)
            respond(answer)
            assertTrue(request.await())
        }
        ask("model-a", OffloadPermissionManager.Response.ALLOW_SESSION)
        assertTrue(manager.checkPermission("image_generation", "Image", session, grantScope = "model-a"))
        ask("model-b", OffloadPermissionManager.Response.ALLOW_SESSION)
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        ask("model-a", OffloadPermissionManager.Response.ALLOW_ONCE)
        ask("model-b", OffloadPermissionManager.Response.ALLOW_ONCE)
        val denied = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "Image", session, grantScope = "model-c")
        }
        respond(OffloadPermissionManager.Response.DENY_SESSION)
        assertFalse(denied.await())
        assertFalse(manager.checkPermission("image_generation", "Image", session, grantScope = "model-a"))
        manager.clearSessionGrants(session)
    }

    @Test fun `clearing a chat forgets its grants and denials without changing another chat`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val cleared = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        suspend fun answer(session: String, response: OffloadPermissionManager.Response): Boolean {
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                manager.checkPermission("image_generation", "Image", session, grantScope = "model")
            }
            assertFalse(request.isCompleted)
            respond(response)
            return withTimeout(2_000) { request.await() }
        }
        assertTrue(answer(cleared, OffloadPermissionManager.Response.ALLOW_SESSION))
        assertTrue(answer(other, OffloadPermissionManager.Response.ALLOW_SESSION))
        manager.clearSessionGrants(cleared)
        assertTrue(manager.checkPermission("image_generation", "Image", other, grantScope = "model"))
        assertFalse(answer(cleared, OffloadPermissionManager.Response.DENY_SESSION))
        assertFalse(manager.checkPermission("image_generation", "Image", cleared, grantScope = "model"))
        manager.clearSessionGrants(cleared)
        assertTrue(answer(cleared, OffloadPermissionManager.Response.ALLOW_ONCE))
        assertTrue(manager.checkPermission("image_generation", "Image", other, grantScope = "model"))
        manager.clearSessionGrants(cleared)
        manager.clearSessionGrants(other)
    }

    @Test fun `ending a session withdraws its active and queued requests only`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("calendar", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val oldSession = UUID.randomUUID().toString()
        val otherSession = UUID.randomUUID().toString()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "Image", oldSession)
        }
        val oldId = requireNotNull(manager.pendingRequest.value).requestId
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("calendar", "Calendar", oldSession)
        }
        val other = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("calendar", "Other", otherSession)
        }
        manager.clearSessionGrants(oldSession)
        assertFalse(withTimeout(2_000) { first.await() })
        assertFalse(withTimeout(2_000) { queued.await() })
        val current = withTimeout(2_000) { manager.pendingRequest.first { it?.toolTitle == "Other" } }!!
        manager.respondToRequest(oldId, OffloadPermissionManager.Response.ALLOW_SESSION)
        assertEquals(current.requestId, manager.pendingRequest.value?.requestId)
        manager.respondToRequest(current.requestId, OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(other.await())
        val fresh = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "Fresh", oldSession)
        }
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertTrue(fresh.await())
        manager.clearSessionGrants(otherSession)
        manager.clearSessionGrants(oldSession)
    }

    @Test fun `one time approval remains observable until closed and settings ABA revokes it`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        val observation = manager.observePermission("image_generation", session)
        try {
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                manager.checkPermission("image_generation", "Image", session, grantScope = "model")
            }
            respond(OffloadPermissionManager.Response.ALLOW_ONCE)
            assertTrue(request.await())
            assertTrue(observation.isCurrent())
            manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
            manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
            assertFalse(observation.isCurrent())
        } finally {
            observation.close()
            observation.close() // release is idempotent
            manager.clearSessionGrants(session)
        }
        assertFalse(observation.isCurrent())
        val epochs = manager.javaClass.getDeclaredField("activeSessionEpochs").apply { isAccessible = true }.get(manager) as Map<*, *>
        assertFalse(epochs.containsKey(session))
    }

    @Test fun `clearing or denying a session revokes its live observations only`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val cleared = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val one = manager.observePermission("image_generation", cleared)
        val two = manager.observePermission("image_generation", cleared)
        val otherObservation = manager.observePermission("image_generation", other)
        try {
            suspend fun answer(session: String, response: OffloadPermissionManager.Response): Boolean {
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    manager.checkPermission("image_generation", "Image", session, grantScope = "model")
                }
                respond(response)
                return request.await()
            }
            assertTrue(answer(cleared, OffloadPermissionManager.Response.ALLOW_SESSION))
            assertTrue(answer(other, OffloadPermissionManager.Response.ALLOW_ONCE))
            manager.clearSessionGrants(cleared)
            assertFalse(one.isCurrent())
            assertFalse(two.isCurrent())
            assertTrue(otherObservation.isCurrent())
            assertFalse(answer(other, OffloadPermissionManager.Response.DENY_SESSION))
            assertFalse(otherObservation.isCurrent())
        } finally {
            one.close()
            two.close()
            otherObservation.close()
            manager.clearSessionGrants(cleared)
            manager.clearSessionGrants(other)
        }
        val epochs = manager.javaClass.getDeclaredField("activeSessionEpochs").apply { isAccessible = true }.get(manager) as Map<*, *>
        assertFalse(epochs.containsKey(cleared))
        assertFalse(epochs.containsKey(other))
    }

    @Test fun `cancellation withdraws only its own dialog and a settings denial wins`() = runBlocking {
        manager.init(PetPrefsContext())
        manager.setLevel("calendar", OffloadPermissionManager.PermissionLevel.ASK_ONCE)
        val session = UUID.randomUUID().toString()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "Image", session)
        }
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("calendar", "Calendar", session)
        }
        queued.cancelAndJoin()
        assertEquals("image_generation", manager.pendingRequest.value?.toolName)
        first.cancelAndJoin()
        assertEquals(null, manager.pendingRequest.value)
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            manager.checkPermission("image_generation", "Image", session)
        }
        assertEquals("image_generation", manager.pendingRequest.value?.toolName)
        manager.setLevel("image_generation", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
        respond(OffloadPermissionManager.Response.ALLOW_ONCE)
        assertFalse(next.await())
        manager.clearSessionGrants(session)
    }
}
