package io.github.nanomuse.isolated

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No assumptions or skips: actually boot the packaged PRoot and run commands through the app. */
@RunWith(AndroidJUnit4::class)
class StrictSandboxInstrumentedTest {
    @Test fun processSnapshotIsBoundedToTheSyntheticAppTree() {
        requireIsolatedEmulator()
        val ps = listOf(
            "PID PPID S NAME",
            "43 42 T /tmp/busybox",
            "900 1 S private-unrelated-process",
            "42 21 S /tmp/libproot.so",
            "21 1 R ai.tama.assistant",
            "44 42 S arbitrary-private-name",
            "45 42 D /tmp/libproot-loader.so",
            "malformed row",
        )
        val waits = mapOf(21 to "0", 42 to "futex_wait", 43 to "ptrace_stop", 44 to "invalid%wait", 45 to "pipe_read")
        val snapshot = SandboxProcessSnapshot.summarize(ps, 21, { waits[it] }) { pid -> if (pid == 43) 42 else null }
        assertEquals(
            "v1 root=21 truncated=0\n" +
                "v1 process pid=21 ppid=1 state=R wait=zero_or_hidden kind=app tracer=unknown\n" +
                "v1 process pid=42 ppid=21 state=S wait=futex kind=proot tracer=unknown\n" +
                "v1 process pid=43 ppid=42 state=T wait=ptrace kind=busybox tracer=42\n" +
                "v1 process pid=44 ppid=42 state=S wait=other kind=other tracer=unknown\n" +
                "v1 process pid=45 ppid=42 state=D wait=pipe kind=loader tracer=unknown\n",
            snapshot,
        )
        assertFalse(snapshot.contains("private"))
        assertFalse(snapshot.contains("900"))
        assertFalse(snapshot.contains("/tmp/"))

        val unavailable = "v1 root=21 truncated=unknown\nv1 unavailable\n"
        assertEquals(unavailable, SandboxProcessSnapshot.summarize(ps.drop(1), 21, { null }) { null })
        assertEquals(unavailable, SandboxProcessSnapshot.summarize(ps.filterNot { it.startsWith("21 ") }, 21, { null }) { null })
        assertEquals(unavailable, SandboxProcessSnapshot.summarize(ps + "x".repeat(4_097), 21, { null }) { null })
        assertEquals(unavailable, SandboxProcessSnapshot.summarize(ps + List(2_000) { "x" }, 21, { null }) { null })

        val many = listOf("PID PPID S NAME", "21 1 R ai.tama.assistant") +
            (100..130).map { "$it 21 S /tmp/secret-$it" }
        val limited = SandboxProcessSnapshot.summarize(many, 21, { throw IllegalStateException("private") }) {
            throw IllegalStateException("private")
        }
        assertTrue(limited.startsWith("v1 root=21 truncated=1\n"))
        assertEquals(25, limited.lineSequence().count { it.isNotEmpty() })
        assertFalse(limited.contains("secret"))
        assertFalse(limited.contains("private"))
        assertTrue(limited.contains("tracer=unknown"))
    }

    @Test fun packagedSandboxExecutesAndPersistsAWorkspaceFile() = runBlocking {
        requireIsolatedEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val snapshot = File(context.cacheDir, "sandbox-process-snapshot.txt")
        check(!snapshot.exists() || snapshot.delete()) { "Could not clear the prior test diagnostic" }
        val session = "tama-ci-shell-${UUID.randomUUID()}"
        ExecutionCoordinator.init(context)
        try {
            PRootKernel.boot(context)
            assertTrue("PRoot did not boot", PRootKernel.isBooted)
            val first = ExecutionCoordinator.execute(
                session,
                "printf 'TAMA_SHELL_OK\\n'; printf 'persisted' > /var/minis/workspace/probe.txt; cat /var/minis/workspace/probe.txt",
                timeout = 60_000L,
            )
            assertEquals(
                "First shell command: ${first.output}; ${ExecutionCoordinator.testShellDiagnostics(session)}",
                0,
                first.exitCode,
            )
            assertTrue(first.output.contains("TAMA_SHELL_OK"))
            assertTrue(first.output.contains("persisted"))
            assertEquals("persisted", File(context.filesDir, "minis-sessions/$session/workspace/probe.txt").readText())

            val second = ExecutionCoordinator.execute(session, "cat /var/minis/workspace/probe.txt", timeout = 30_000L)
            assertEquals("Second shell command: ${second.output}", 0, second.exitCode)
            assertTrue(second.output.contains("persisted"))
            val failed = ExecutionCoordinator.execute(session, "sh -c 'exit 7'", timeout = 30_000L)
            assertEquals("The shell must report a failing command", 7, failed.exitCode)
        } catch (failure: Throwable) {
            // Capture before cleanup, without changing or masking the original failure.
            runCatching { snapshot.writeText(SandboxProcessSnapshot.capture()) }
            throw failure
        } finally {
            ExecutionCoordinator.sessionDidTerminate(session)
        }
    }
}
