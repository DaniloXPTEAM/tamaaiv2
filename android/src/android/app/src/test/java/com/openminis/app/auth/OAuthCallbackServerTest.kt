package com.openminis.app.auth

import com.openminis.app.BuildConfig
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Only loopback HTTP and synthetic codes; no browser, Android intents, or provider services. */
class OAuthCallbackServerTest {
    private fun parallelOnly() = assumeTrue(BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun request(port: Int, line: String, headers: String = ""): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 3_000
        socket.getOutputStream().write("$line HTTP/1.1\r\nHost: localhost\r\n${headers}\r\n".toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }

    @Test fun wrongPathOrStateCannotConsumeTheCurrentCallback() {
        parallelOnly()
        val calls = AtomicInteger()
        val completed = CountDownLatch(1)
        val server = OAuthCallbackServer(freePort(), expectedPath = "/callback", expectedState = "expected") { code, state ->
            assertEquals("synthetic-code", code)
            assertEquals("expected", state)
            calls.incrementAndGet()
            completed.countDown()
        }
        try {
            server.start()
            assertTrue(request(server.boundPort, "GET /other?code=bad&state=expected").startsWith("HTTP/1.1 400"))
            assertTrue(request(server.boundPort, "GET /callback?code=bad&state=wrong").startsWith("HTTP/1.1 400"))
            assertTrue(request(server.boundPort, "GET /callback?code=bad").startsWith("HTTP/1.1 400"))
            assertTrue(request(server.boundPort, "POST /callback?code=bad&state=expected").startsWith("HTTP/1.1 400"))
            assertEquals(0, calls.get())
            assertTrue(request(server.boundPort, "GET /callback?code=synthetic-code&state=expected").startsWith("HTTP/1.1 200"))
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { server.stop() }
    }

    @Test fun newerOwnerCancelsOldWaitAndOldStopCannotCloseNewServer() {
        parallelOnly()
        val port = freePort()
        val cancelled = CountDownLatch(1)
        val received = CountDownLatch(1)
        val old = OAuthCallbackServer(port, expectedPath = "/callback", expectedState = "old") { _, _ -> fail("old callback") }
        val newer = OAuthCallbackServer(port, expectedPath = "/callback", expectedState = "new") { code, _ ->
            assertEquals("synthetic-new", code)
            received.countDown()
        }
        old.onExternalCancel = { cancelled.countDown() }
        try {
            old.start()
            newer.start()
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            old.stop()
            assertTrue(request(port, "GET /callback?code=stale&state=old").startsWith("HTTP/1.1 400"))
            assertTrue(request(port, "GET /callback?code=synthetic-new&state=new").startsWith("HTTP/1.1 200"))
            assertTrue(received.await(3, TimeUnit.SECONDS))
        } finally { old.stop(); newer.stop() }
    }

    @Test fun cancellationHookRunsWithoutTheOwnerLock() {
        parallelOnly()
        val port = freePort()
        val probeFinished = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val unblocked = java.util.concurrent.atomic.AtomicBoolean(false)
        val old = OAuthCallbackServer(port) { _, _ -> }
        val newer = OAuthCallbackServer(port) { _, _ -> }
        old.onExternalCancel = {
            Thread { old.stop(); probeFinished.countDown() }.apply { isDaemon = true }.start()
            unblocked.set(probeFinished.await(2, TimeUnit.SECONDS))
            callbackFinished.countDown()
        }
        try {
            old.start()
            newer.start()
            assertTrue(callbackFinished.await(3, TimeUnit.SECONDS))
            assertTrue("cancellation hook must not hold the owner lock", unblocked.get())
        } finally { old.stop(); newer.stop() }
    }

    @Test fun codeHookRunsWithoutTheOwnerLock() {
        parallelOnly()
        val finished = CountDownLatch(1)
        val unblocked = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var server: OAuthCallbackServer
        server = OAuthCallbackServer(freePort(), expectedPath = "/callback") { _, _ ->
            val probe = CountDownLatch(1)
            Thread { server.stop(); probe.countDown() }.apply { isDaemon = true }.start()
            unblocked.set(probe.await(2, TimeUnit.SECONDS))
            finished.countDown()
        }
        try {
            server.start()
            assertTrue(request(server.boundPort, "GET /callback?code=synthetic").startsWith("HTTP/1.1 200"))
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertTrue("code hook must not hold the owner lock", unblocked.get())
        } finally { server.stop() }
    }

    @Test fun delayedOlderStartCannotStealTheListenerEvenAfterNewerStopped() {
        parallelOnly()
        val port = freePort()
        val received = CountDownLatch(1)
        val old = OAuthCallbackServer(port, expectedPath = "/callback", startOrder = 1) { _, _ -> fail("old callback") }
        val newer = OAuthCallbackServer(port, expectedPath = "/callback", startOrder = 2) { _, _ -> received.countDown() }
        val rejected = OAuthCallbackServer(port, expectedPath = "/callback", startOrder = 3, mayStart = { false }) { _, _ -> fail("rejected callback") }
        try {
            newer.start()
            try { old.start(); fail("older listener started") } catch (_: IllegalStateException) {}
            try { rejected.start(); fail("stale ticket started") } catch (_: IllegalStateException) {}
            assertTrue(request(port, "GET /callback?code=synthetic").startsWith("HTTP/1.1 200"))
            assertTrue(received.await(3, TimeUnit.SECONDS))
            newer.stop()
            try { old.start(); fail("older listener reclaimed the port") } catch (_: IllegalStateException) {}
        } finally { old.stop(); newer.stop(); rejected.stop() }
    }

    @Test fun fallbackPortIsBoundBeforeStartReturnsAndOnlyOnLoopback() {
        parallelOnly()
        val occupied = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val completed = CountDownLatch(1)
        val server = OAuthCallbackServer(occupied.localPort, listOf(0), expectedPath = "/callback") { _, _ -> completed.countDown() }
        try {
            server.start()
            assertNotEquals(occupied.localPort, server.boundPort)
            val field = OAuthCallbackServer::class.java.getDeclaredField("serverSocket").apply { isAccessible = true }
            assertTrue((field.get(server) as ServerSocket).inetAddress.isLoopbackAddress)
            assertTrue(request(server.boundPort, "GET /callback?code=synthetic").startsWith("HTTP/1.1 200"))
            assertTrue(completed.await(3, TimeUnit.SECONDS))
        } finally { server.stop(); occupied.close() }
    }

    @Test fun xaiPreflightDoesNotConsumeCallbackOrEchoLookalikeOrigins() {
        parallelOnly()
        val server = OAuthCallbackServer(freePort(), expectedPath = "/callback", expectedState = "expected") { _, _ -> }
        try {
            server.start()
            val trusted = request(server.boundPort, "OPTIONS /callback", "Origin: https://auth.x.ai\r\n")
            assertTrue(trusted.contains("Access-Control-Allow-Origin: https://auth.x.ai\r\n"))
            val lookalike = request(server.boundPort, "OPTIONS /callback", "Origin: https://auth.x.ai.evil.test\r\n")
            assertTrue(lookalike.contains("Access-Control-Allow-Origin: null\r\n"))
            assertTrue(request(server.boundPort, "GET /callback?code=synthetic&state=expected").startsWith("HTTP/1.1 200"))
        } finally { server.stop() }
    }
}
