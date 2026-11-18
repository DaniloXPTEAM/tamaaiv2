package ai.tama.net

import org.junit.Assert.*
import org.junit.Test

class SandboxLogPrivacyTest {
    @Test fun `parallel shell diagnostics do not contain command session or exception text`() {
        val private = "fixture-private-prompt-credential"
        val values = listOf(
            SandboxLogPrivacy.dispatch(true, private, "echo $private"),
            SandboxLogPrivacy.start(true, "echo $private"),
            SandboxLogPrivacy.timeout(true, 123, "echo $private"),
            SandboxLogPrivacy.failure(true, "echo $private"),
            SandboxLogPrivacy.failureOutput(true, private),
            SandboxLogPrivacy.writeFailure(true, private),
            SandboxLogPrivacy.spawnFailure(true, private, private),
            SandboxLogPrivacy.readerFailure(true, private),
            SandboxLogPrivacy.captured(true, private),
        )
        values.forEach { assertFalse(it.contains(private)) }
        assertEquals("Command timed out after 123ms", values[2])
        assertEquals("[Error: shell operation failed]", values[4])
        assertEquals("", values.last())
    }

    @Test fun `normal shell diagnostics retain the existing details`() {
        assertEquals("[diag] execute sessionId=s cmd=first second", SandboxLogPrivacy.dispatch(false, "s", "first\nsecond"))
        assertEquals("Executing: command", SandboxLogPrivacy.start(false, "command"))
        assertEquals("Command timed out after 123ms: command", SandboxLogPrivacy.timeout(false, 123, "command"))
        assertEquals("Command failed: command", SandboxLogPrivacy.failure(false, "command"))
        assertEquals("[Error: detail]", SandboxLogPrivacy.failureOutput(false, "detail"))
        assertEquals("[Write error: detail]", SandboxLogPrivacy.writeFailure(false, "detail"))
        assertEquals("spawn threw: IOException: detail", SandboxLogPrivacy.spawnFailure(false, "IOException", "detail"))
        assertEquals("Reader loop ended: detail", SandboxLogPrivacy.readerFailure(false, "detail"))
        assertEquals("process output", SandboxLogPrivacy.captured(false, "process output"))
    }
}
