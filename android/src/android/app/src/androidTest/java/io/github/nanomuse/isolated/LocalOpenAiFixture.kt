package io.github.nanomuse.isolated

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/** Minimal loopback-only OpenAI Chat Completions fixture. No account or real credential is used. */
internal class LocalOpenAiFixture : AutoCloseable {
    companion object {
        const val MODEL_ID = "tama-local-fixture"
        const val MODEL_NAME = "TAMA local fixture"
        const val PROMPT = "Ola TAMA, este e um teste local."
        const val REPLY = "Ola! Resposta do teste local."
    }

    private val server = ServerSocket().apply {
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    }
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"
    val posts: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
    val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var running = true
    private val acceptThread = thread(name = "tama-fixture-accept", isDaemon = true) {
        while (running) {
            try {
                val client = server.accept()
                thread(name = "tama-fixture-client", isDaemon = true) { handle(client) }
            } catch (e: SocketException) {
                if (running) errors.add("accept: ${e.message}")
                break
            } catch (e: Exception) {
                errors.add("accept: ${e.message}")
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (line.size() < 16_384) {
            val b = input.read()
            if (b == -1) return if (line.size() == 0) null else line.toString("UTF-8")
            if (b == '\n'.code) return line.toString("UTF-8").trimEnd('\r')
            line.write(b)
        }
        throw IllegalArgumentException("HTTP header is too long")
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 20_000
                val input = socket.getInputStream().buffered()
                val request = readLine(input)?.split(' ') ?: return
                var length = 0
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        length = header.substringAfter(':').trim().toInt()
                    }
                    require(!header.startsWith("Transfer-Encoding: chunked", ignoreCase = true)) {
                        "The fixture does not accept chunked request bodies"
                    }
                }
                require(length in 0..2_000_000)
                val body = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(body, offset, length - offset)
                    require(read > 0) { "Incomplete request body" }
                    offset += read
                }
                val path = request.getOrNull(1)?.substringBefore('?')
                val (status, contentType, payload) = when {
                    request.firstOrNull() == "GET" && path == "/v1/models" -> Triple(
                        "200 OK", "application/json",
                        JSONObject().put("object", "list").put("data", JSONArray().put(
                            JSONObject().put("id", MODEL_ID).put("object", "model").put("name", MODEL_NAME)
                                .put("context_length", 32768),
                        )).toString(),
                    )
                    request.firstOrNull() == "POST" && path == "/v1/chat/completions" -> {
                        posts.add(JSONObject(String(body, Charsets.UTF_8)))
                        val delta = JSONObject().put("id", "fixture-reply").put("object", "chat.completion.chunk")
                            .put("choices", JSONArray().put(JSONObject().put("index", 0)
                                .put("delta", JSONObject().put("role", "assistant").put("content", REPLY))))
                        val finish = JSONObject().put("id", "fixture-reply").put("object", "chat.completion.chunk")
                            .put("choices", JSONArray().put(JSONObject().put("index", 0)
                                .put("delta", JSONObject()).put("finish_reason", "stop")))
                        Triple("200 OK", "text/event-stream", "data: $delta\n\ndata: $finish\n\ndata: [DONE]\n\n")
                    }
                    else -> Triple("404 Not Found", "application/json", "{\"error\":\"fixture route not found\"}")
                }
                val bytes = payload.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().use { output ->
                    output.write("HTTP/1.1 $status\r\nContent-Type: $contentType; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(bytes)
                    output.flush()
                }
            }
        } catch (e: Exception) {
            errors.add("request: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun close() {
        running = false
        server.close()
        acceptThread.join(2_000)
    }
}
