package io.github.nanomuse.sandbox

import android.content.Context
import com.openminis.app.sandbox.PersistentShell
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/** Runs the real reader against a synthetic process; no PRoot, device or network is used. */
class PersistentShellStreamTest {
    private enum class Mode { COMPLETE, PREFIX, STATUS, UTF8, BYTEWISE, TIMEOUT_FIRST, NO_RESPONSE, EOF_PARTIAL }

    private class ChunkedInput : InputStream() {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        fun add(vararg values: ByteArray) = values.forEach { chunks.put(it) }
        override fun read(): Int = throw UnsupportedOperationException("Use a buffer")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val part = chunks.take()
            if (part.isEmpty()) return -1
            check(part.size <= length)
            part.copyInto(buffer, offset)
            return part.size
        }
        override fun close() { chunks.offer(ByteArray(0)) }
    }

    private class FakeProcess(private val mode: Mode) : Process() {
        private val stdout = ChunkedInput()
        private val written = ByteArrayOutputStream()
        val requests = LinkedBlockingQueue<Unit>()
        @Volatile private var running = true
        private var count = 0

        private val stdin = object : OutputStream() {
            override fun write(value: Int) { written.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { written.write(bytes, offset, length) }
            override fun flush() {
                val command = written.toString(StandardCharsets.UTF_8.name())
                written.reset()
                val id = Regex("__MINIS_DONE_([0-9a-f]{8})_EXIT_").find(command)?.groupValues?.get(1) ?: return
                val marker = "__MINIS_DONE_${id}_EXIT_0__\n".toByteArray(StandardCharsets.UTF_8)
                val hello = "hello\n".toByteArray(StandardCharsets.UTF_8)
                when (mode) {
                    Mode.COMPLETE -> stdout.add(hello + marker)
                    Mode.PREFIX -> stdout.add(hello + marker.copyOfRange(0, 8), marker.copyOfRange(8, marker.size))
                    Mode.STATUS -> stdout.add(hello + marker.copyOfRange(0, marker.size - 3), marker.copyOfRange(marker.size - 3, marker.size))
                    Mode.UTF8 -> {
                        val text = "olá\n".toByteArray(StandardCharsets.UTF_8)
                        stdout.add(text.copyOfRange(0, 3), text.copyOfRange(3, text.size) + marker)
                    }
                    Mode.BYTEWISE -> stdout.add(*(hello + marker).map { byteArrayOf(it) }.toTypedArray())
                    Mode.TIMEOUT_FIRST -> if (count > 0) stdout.add(hello + marker)
                    Mode.NO_RESPONSE -> Unit
                    Mode.EOF_PARTIAL -> {
                        stdout.add("hello\n__MINIS_DONE_${id}_EXIT_2_".toByteArray(StandardCharsets.UTF_8))
                        running = false
                        stdout.close()
                    }
                }
                count++
                requests.offer(Unit)
            }
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int { while (running) Thread.sleep(1); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !running
        override fun exitValue(): Int { if (running) throw IllegalThreadStateException(); return 0 }
        override fun isAlive(): Boolean = running
        override fun destroy() { running = false; stdout.close() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    private fun withShell(mode: Mode, body: (PersistentShell, FakeProcess) -> Unit) {
        val process = FakeProcess(mode)
        val shell = PersistentShell(mock(Context::class.java), "synthetic-session", emptyMap())
        val type = PersistentShell::class.java
        type.getDeclaredField("process").apply { isAccessible = true }.set(shell, process)
        type.getDeclaredField("stdinWriter").apply { isAccessible = true }
            .set(shell, BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)))
        val readLoop = type.getDeclaredMethod("readLoop", Process::class.java).apply { isAccessible = true }
        val error = AtomicReference<Throwable?>(null)
        val reader = Thread({
            try {
                readLoop.invoke(shell, process)
            } catch (failure: Throwable) {
                error.set(failure.cause ?: failure)
            }
        }, "synthetic-persistent-shell-reader").apply { isDaemon = true; start() }
        try {
            body(shell, process)
        } finally {
            process.destroy()
            reader.join(5_000)
            assertFalse("Synthetic reader did not stop", reader.isAlive)
            error.get()?.let { throw AssertionError("Synthetic reader failed", it) }
        }
    }

    @Test fun `fragmented marker status and UTF-8 survive the real reader`() {
        for (mode in listOf(Mode.COMPLETE, Mode.PREFIX, Mode.STATUS, Mode.UTF8, Mode.BYTEWISE)) {
            withShell(mode) { shell, _ ->
                val result = runBlocking { shell.executeCommand("synthetic", timeout = 3_000) }
                val output = if (mode == Mode.UTF8) "olá\n" else "hello\n"
                assertEquals(mode.toString(), output to 0, result)
            }
        }
    }

    @Test fun `successive commands do not steal each other's callback or marker newline`() {
        withShell(Mode.BYTEWISE) { shell, _ ->
            runBlocking {
                repeat(500) { index ->
                    val streamed = Collections.synchronizedList(mutableListOf<String>())
                    val result = shell.executeCommand("synthetic", timeout = 3_000) { streamed.add(it) }
                    assertEquals("command $index", "hello\n" to 0, result)
                    assertEquals("stream $index", "hello", streamed.joinToString(""))
                }
            }
        }
    }

    @Test fun `a process exit preserves an unfinished marker in its error output`() {
        withShell(Mode.EOF_PARTIAL) { shell, _ ->
            val result = runBlocking { shell.executeCommand("synthetic", timeout = 3_000) }
            assertEquals(-1, result.second)
            assertTrue(result.first.startsWith("hello\n__MINIS_DONE_"))
            assertTrue(result.first.endsWith("_EXIT_2_"))
        }
    }

    @Test fun `a timeout does not clear the next command callback`() {
        withShell(Mode.TIMEOUT_FIRST) { shell, _ ->
            runBlocking {
                assertEquals(124, shell.executeCommand("synthetic", timeout = 50).second)
                assertEquals("hello\n" to 0, shell.executeCommand("synthetic", timeout = 3_000))
            }
        }
    }

    @Test fun `stopping a waiting shell returns failure without hanging`() {
        withShell(Mode.NO_RESPONSE) { shell, process ->
            runBlocking {
                val result = async { shell.executeCommand("synthetic", timeout = 5_000) }
                val sent = withContext(Dispatchers.IO) { process.requests.poll(3, TimeUnit.SECONDS) != null }
                assertTrue("Command was not sent", sent)
                shell.stop()
                assertEquals(-1, result.await().second)
            }
        }
    }
}
