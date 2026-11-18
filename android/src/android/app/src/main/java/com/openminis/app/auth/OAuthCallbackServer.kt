package com.openminis.app.auth

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI

class OAuthCallbackServer(
    private val port: Int,
    private val fallbackPorts: List<Int> = emptyList(),
    // nanoMuse: in the parallel build reject callbacks for another path/attempt
    // before consuming the server. Null state is for providers without one.
    private val expectedPath: String? = null,
    private val expectedState: String? = null,
    private val startOrder: Long? = null,
    private val mayStart: () -> Boolean = { true },
    private val onCode: (code: String, state: String?) -> Unit,
) {
    companion object {
        private const val TAG = "OAuthCallbackServer"
        // A later parallel attempt on the same registered port owns the listener.
        // This coordinates this process only; it does not make an external port available.
        private val nmOwners = mutableMapOf<Int, OAuthCallbackServer>()
        // Retain the most recent start even after its socket closes, so a
        // delayed older attempt cannot reclaim the same port afterwards.
        private val nmHighestStart = mutableMapOf<Int, Long>()
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    // Count down before acquiring the owner lock in finally. Closing a socket
    // can take a moment to unblock accept() and release its bound port.
    private val nmListenerDone = java.util.concurrent.CountDownLatch(1)

    /** The port actually bound (may differ from [port] if fallback was used). */
    var boundPort: Int = port
        private set

    /**
     * Invoked by [stop] when the server is shut down by an external
     * caller (e.g. the user dismisses Chrome Custom Tab and the auth
     * manager wants to abort the in-flight wait without waiting for the
     * 5-minute timeout). The callback is _only_ fired when stop() is
     * called by something other than the success path —
     * [onCode] callers set this to null before calling stop() so they
     * don't fire a cancel after a successful redirect.
     *
     * [T-xai-oauth-stop-resume, port iOS d1dbdd5d]
     */
    @Volatile var onExternalCancel: (() -> Unit)? = null

    fun start() {
        // nanoMuse: binding is synchronous in the parallel app, so callers use the
        // real fallback port and cannot launch a browser for a listener that failed.
        if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") {
            nmStart()
            return
        }
        running = true
        Thread {
            try {
                val portsToTry = listOf(port) + fallbackPorts
                var bound = false
                for (p in portsToTry) {
                    try {
                        serverSocket = ServerSocket(p)
                        boundPort = p
                        bound = true
                        break
                    } catch (e: java.net.BindException) {
                        Log.w(TAG, "Port $p in use, trying next...")
                    }
                }
                if (!bound) {
                    Log.e(TAG, "All ports unavailable: $portsToTry")
                    return@Thread
                }
                Log.d(TAG, "Listening on port $boundPort")
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    try {
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine() ?: continue
                        // nanoMuse: the query contains the OAuth code and state.
                        if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
                            Log.d(TAG, "Callback request received") else Log.d(TAG, "Request: $requestLine")

                        // CORS preflight for providers (e.g. xAI) that
                        // OPTIONS /callback from their authorization page
                        // before redirecting the browser. Without this we
                        // return a 404 to the preflight and the browser
                        // never follows the redirect — OAuth stalls.
                        // Read the rest of the headers to find Origin and
                        // only echo back permissive CORS for known xAI hosts.
                        if (requestLine.startsWith("OPTIONS")) {
                            var origin: String? = null
                            while (true) {
                                val h = reader.readLine() ?: break
                                if (h.isEmpty()) break
                                val lower = h.lowercase()
                                if (lower.startsWith("origin:")) {
                                    origin = h.substringAfter(":").trim()
                                }
                            }
                            val trustedHosts = listOf("auth.x.ai", "accounts.x.ai")
                            val allowOrigin = if (origin != null && trustedHosts.any { origin!!.contains(it) }) {
                                origin
                            } else {
                                "null"
                            }
                            val pre = "HTTP/1.1 204 No Content\r\n" +
                                "Access-Control-Allow-Origin: $allowOrigin\r\n" +
                                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: *\r\n" +
                                "Access-Control-Max-Age: 600\r\n" +
                                "Connection: close\r\n\r\n"
                            socket.getOutputStream().write(pre.toByteArray())
                            socket.close()
                            continue
                        }

                        // Parse GET /callback?code=xxx&state=yyy HTTP/1.1
                        val parts = requestLine.split(" ")
                        if (parts.size >= 2) {
                            val uri = URI("http://localhost${ parts[1] }")
                            val params = uri.query?.split("&")?.associate {
                                val kv = it.split("=", limit = 2)
                                kv[0] to (if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else "")
                            } ?: emptyMap()

                            val code = params["code"]
                            val state = params["state"]

                            // Send response
                            val html = "<html><body><h1>Authorization complete</h1><p>You can close this tab.</p><script>window.close()</script></body></html>"
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\nConnection: close\r\n\r\n$html"
                            socket.getOutputStream().write(response.toByteArray())
                            socket.close()

                            if (code != null) {
                                onCode(code, state)
                                stop()
                                return@Thread
                            }
                        }
                        socket.close()
                    } catch (e: Exception) {
                        // nanoMuse: URI parsing exceptions can carry the callback query.
                        if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
                            Log.w(TAG, "Error handling connection") else Log.w(TAG, "Error handling connection", e)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
                        Log.e(TAG, "Server error") else Log.e(TAG, "Server error", e)
                }
            }
        }.start()
    }

    // nanoMuse: bind to IPv4 loopback only. A later in-process owner cancels the
    // previous wait before binding; an occupied external port fails closed.
    private fun nmStart() {
        var cancelPrior: (() -> Unit)? = null
        try {
            synchronized(nmOwners) {
                // Check the ticket while ownership is serialized. The predicate is
                // a short in-memory check; it must never call back into this server.
                if (!mayStart() || (startOrder != null && startOrder <= (nmHighestStart[port] ?: 0L))) {
                    throw IllegalStateException("OAuth sign-in was superseded")
                }
                if (startOrder != null) nmHighestStart[port] = startOrder
                val prior = nmOwners[port]
                // Detach under the lock, but invoke cancellation only after releasing
                // it. A continuation may run arbitrary code or stop another listener.
                cancelPrior = prior?.nmDetachLocked()
                // Bound the wait even if a callback is being processed; no consent
                // or provider-config lock is held while waiting for this listener.
                if (prior != null) {
                    try {
                        prior.nmListenerDone.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw java.io.IOException("OAuth callback start interrupted")
                    }
                }
                var bound: ServerSocket? = null
                for (candidate in listOf(port) + fallbackPorts) {
                    val socket = ServerSocket()
                    try {
                        socket.reuseAddress = true
                        socket.bind(java.net.InetSocketAddress("127.0.0.1", candidate))
                        bound = socket
                        break
                    } catch (_: java.io.IOException) {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
                if (bound == null) throw java.io.IOException("OAuth callback port unavailable")
                serverSocket = bound
                boundPort = bound.localPort
                running = true
                nmOwners[port] = this
            }
            Thread({ nmAcceptCallbacks() }, "TamaOAuthCallback").apply { isDaemon = true }.start()
        } finally {
            nmNotifyCancellation(cancelPrior)
        }
    }

    // Called only while holding nmOwners. Never invoke a caller's code here.
    private fun nmDetachLocked(): (() -> Unit)? {
        val wasRunning = running
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        if (nmOwners[port] === this) nmOwners.remove(port)
        val callback = if (wasRunning) onExternalCancel else null
        onExternalCancel = null
        return callback
    }

    private fun nmNotifyCancellation(callback: (() -> Unit)?) {
        try { callback?.invoke() } catch (_: Exception) { Log.w(TAG, "Callback cancellation failed") }
    }

    private fun nmAcceptCallbacks() {
        try {
            while (running) {
                val socket = try { serverSocket?.accept() } catch (_: java.io.IOException) { null } ?: break
                socket.use { client ->
                    try {
                        client.soTimeout = 5_000
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        val line = reader.readLine() ?: return@use
                        val parts = line.split(' ')
                        val uri = if (parts.size >= 2) URI("http://localhost${parts[1]}") else null
                        // xAI may preflight before redirecting. Only exact HTTPS
                        // origins are echoed, never an arbitrary lookalike host.
                        if (parts.firstOrNull() == "OPTIONS" && uri != null &&
                            (expectedPath == null || uri.path == expectedPath)) {
                            var origin: String? = null
                            while (true) {
                                val header = reader.readLine() ?: break
                                if (header.isEmpty()) break
                                if (header.substringBefore(':').equals("Origin", ignoreCase = true))
                                    origin = header.substringAfter(':').trim()
                            }
                            val trusted = origin?.let { raw ->
                                runCatching {
                                    val parsed = URI(raw)
                                    parsed.scheme == "https" && parsed.host in setOf("auth.x.ai", "accounts.x.ai") &&
                                        parsed.port == -1 && parsed.rawUserInfo == null &&
                                        parsed.rawPath.isNullOrEmpty() && parsed.rawQuery == null && parsed.rawFragment == null
                                }.getOrDefault(false)
                            } == true
                            val allowed = if (trusted) origin else "null"
                            val response = "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: $allowed\r\n" +
                                "Access-Control-Allow-Methods: GET, OPTIONS\r\nAccess-Control-Allow-Headers: *\r\n" +
                                "Access-Control-Max-Age: 600\r\nConnection: close\r\n\r\n"
                            client.getOutputStream().write(response.toByteArray())
                            return@use
                        }
                        val params = uri?.rawQuery?.split('&')?.associate { part ->
                            val kv = part.split('=', limit = 2)
                            kv[0] to if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else ""
                        }.orEmpty()
                        val code = params["code"]
                        val state = params["state"]
                        val valid = parts.firstOrNull() == "GET" && uri != null &&
                            (expectedPath == null || uri.path == expectedPath) &&
                            !code.isNullOrEmpty() && (expectedState == null || state == expectedState)
                        if (!valid) {
                            client.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            return@use
                        }
                        val accepted = synchronized(nmOwners) {
                            if (!running || nmOwners[port] !== this) false else {
                                // Atomically consume the listener without cancelling its
                                // waiter. User code runs only after the lock is released.
                                onExternalCancel = null
                                nmDetachLocked()
                                true
                            }
                        }
                        if (accepted) {
                            onCode(code!!, state)
                            val html = "<html><body><h1>Authorization complete</h1><p>You can close this tab.</p></body></html>"
                            val body = html.toByteArray(Charsets.UTF_8)
                            client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                            stop()
                            return
                        }
                    } catch (_: Exception) {
                        // A malformed callback may contain a code; do not log it.
                    }
                }
            }
        } finally {
            // Signal before stop() acquires the owner lock held by a replacer.
            nmListenerDone.countDown()
            stop()
        }
    }

    fun stop() {
        // nanoMuse: remove only this owner; an old completion cannot stop a
        // newer server. Close the socket before invoking the cancellation hook.
        if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") {
            val cancel = synchronized(nmOwners) { nmDetachLocked() }
            nmNotifyCancellation(cancel)
            return
        }
        val wasRunning = running
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        // Notify external callers (e.g. XAIOAuthManager) so they can
        // cancel an in-flight suspendCancellableCoroutine instead of
        // hanging until the next inbound connection / the 5-min
        // OAuth wait timeout fires. Consume the callback (set to
        // null before invoking) so re-entrant stop() calls don't
        // double-fire (T-xai-oauth-stop-resume).
        if (wasRunning) {
            val cancel = onExternalCancel
            onExternalCancel = null
            try { cancel?.invoke() } catch (e: Exception) {
                Log.w(TAG, "onExternalCancel threw: ${e.message}")
            }
        }
    }
}
