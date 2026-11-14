package io.github.nanomuse.isolated

import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Diagnostic for the explicitly isolated, disposable emulator only. The test calls this while
 * the failing shell is still alive. Output never includes paths, arguments, process names, shell
 * output, environment variables or other processes' names. No claim about the cause is implied.
 */
internal object SandboxProcessSnapshot {
    private data class Row(val pid: Int, val parent: Int, val state: String, val name: String)

    private const val MAX_ROWS = 24
    private const val MAX_INPUT_LINES = 2_000
    private const val MAX_LINE_CHARS = 4_096
    private const val MAX_INPUT_CHARS = 256 * 1024
    private const val TIMEOUT_SECONDS = 10L
    private val space = Regex("\\s+")
    private val safeState = Regex("[A-Za-z?]")
    private val safeWait = Regex("[A-Za-z0-9_]{1,128}")

    private fun unavailable(rootPid: Int) = "v1 root=$rootPid truncated=unknown\nv1 unavailable\n"

    private fun procPrefix(pid: Int, name: String, limit: Int): String? {
        val chars = CharArray(limit)
        val count = File("/proc/$pid/$name").bufferedReader().use { it.read(chars) }
        return if (count > 0) String(chars, 0, count) else null
    }

    private fun waitCategory(value: String?): String {
        val name = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            name.isEmpty() -> "unknown"
            name == "0" || name == "-" -> "zero_or_hidden"
            !safeWait.matches(name) -> "other"
            name.contains("ptrace") -> "ptrace"
            name.contains("futex") -> "futex"
            name.contains("pipe") -> "pipe"
            name.contains("poll") -> "poll"
            name.contains("wait") -> "wait"
            else -> "other"
        }
    }

    private fun processCategory(row: Row, rootPid: Int): String {
        if (row.pid == rootPid) return "app"
        // NAME is argv[0] on Android's toybox ps; reduce it to a fixed vocabulary.
        return when (row.name.substringBefore(' ').substringAfterLast('/').lowercase(Locale.ROOT)) {
            "proot", "libproot.so" -> "proot"
            "busybox" -> "busybox"
            "loader", "loader32", "libproot-loader.so", "libproot-loader32.so" -> "loader"
            "ndk_translation", "libndk_translation.so" -> "translation"
            "sh" -> "sh"
            else -> "other"
        }
    }

    fun summarize(
        lines: List<String>,
        rootPid: Int,
        waitOf: (Int) -> String?,
        tracerOf: (Int) -> Int?,
    ): String {
        require(rootPid > 0)
        if (lines.isEmpty() || lines.size > MAX_INPUT_LINES || lines.any { it.length > MAX_LINE_CHARS }) {
            return unavailable(rootPid)
        }
        // Unsupported ps versions and unexpected column orders must not be interpreted as data.
        if (lines[0].trim().split(space) != listOf("PID", "PPID", "S", "NAME")) {
            return unavailable(rootPid)
        }
        val rows = lines.drop(1).mapNotNull { line ->
            val fields = line.trim().split(space, limit = 4)
            if (fields.size != 4) return@mapNotNull null
            val pid = fields[0].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val parent = fields[1].toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val state = fields[2].takeIf { safeState.matches(it) } ?: "?"
            Row(pid, parent, state, fields[3])
        }.distinctBy { it.pid }
        val root = rows.firstOrNull { it.pid == rootPid } ?: return unavailable(rootPid)
        val children = rows.filterNot { it.pid == rootPid }.groupBy { it.parent }
        val found = linkedMapOf(root.pid to root)
        val queue = ArrayDeque<Row>().apply { add(root) }
        var truncated = false
        while (queue.isNotEmpty() && !truncated) {
            val parent = queue.removeFirst()
            for (child in children[parent.pid].orEmpty().sortedBy { it.pid }) {
                if (found.containsKey(child.pid)) continue
                if (found.size == MAX_ROWS) {
                    truncated = true
                    break
                }
                found[child.pid] = child
                queue.add(child)
            }
        }
        val out = mutableListOf("v1 root=$rootPid truncated=${if (truncated) 1 else 0}")
        for (row in found.values.sortedBy { it.pid }) {
            val wait = runCatching { waitOf(row.pid) }.getOrNull()
            val tracer = runCatching { tracerOf(row.pid) }.getOrNull()?.takeIf { it >= 0 }?.toString() ?: "unknown"
            out += "v1 process pid=${row.pid} ppid=${row.parent} state=${row.state} " +
                "wait=${waitCategory(wait)} kind=${processCategory(row, rootPid)} tracer=$tracer"
        }
        return out.joinToString("\n", postfix = "\n")
    }

    fun capture(): String {
        val root = Process.myPid()
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        val accepting = AtomicBoolean(true)
        // A broken ps or pipe must not turn a failed 60-second shell check into an indefinite hang.
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tama-sandbox-snapshot").apply { isDaemon = true }
        }
        val future = executor.submit<String> {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("ps -A -w -o PID,PPID,S,NAME")
            descriptor.set(fd)
            if (!accepting.get()) {
                runCatching { descriptor.getAndSet(null)?.close() }
                return@submit unavailable(root)
            }
            try {
                val text = ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    val output = StringBuilder()
                    while (output.length < MAX_INPUT_CHARS) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_INPUT_CHARS - output.length))
                        if (count < 0) break
                        output.append(buffer, 0, count)
                    }
                    output.toString()
                }
                if (text.length >= MAX_INPUT_CHARS) return@submit unavailable(root)
                val lines = text.lineSequence().take(MAX_INPUT_LINES + 1).toList()
                // Read wchan separately: an empty ps WCHAN column can shift whitespace-parsed
                // fields. Android may restrict either /proc file; absence stays unknown.
                summarize(
                    lines,
                    root,
                    waitOf = { pid -> procPrefix(pid, "wchan", 128) },
                    tracerOf = { pid ->
                        procPrefix(pid, "status", 4096)?.lineSequence()
                            ?.firstOrNull { it.startsWith("TracerPid:") }
                            ?.substringAfter(':')?.trim()?.toIntOrNull()
                    },
                )
            } finally {
                descriptor.compareAndSet(fd, null)
            }
        }
        return try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            unavailable(root)
        } catch (_: Exception) {
            unavailable(root)
        } finally {
            accepting.set(false)
            future.cancel(true)
            runCatching { descriptor.getAndSet(null)?.close() }
            executor.shutdownNow()
        }
    }
}
