package io.github.nanomuse.isolated

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.PRootKernel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No assumptions or skips: actually boot the packaged PRoot and run commands through the app. */
@RunWith(AndroidJUnit4::class)
class StrictSandboxInstrumentedTest {
    @Test fun packagedSandboxExecutesAndPersistsAWorkspaceFile() = runBlocking {
        requireIsolatedEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
        } finally {
            ExecutionCoordinator.sessionDidTerminate(session)
        }
    }
}
