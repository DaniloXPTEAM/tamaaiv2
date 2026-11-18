package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import testfixtures.runner.Context
import testfixtures.runner.InputAttachment
import testfixtures.runner.RunnerFixture

/** Actual prompt method with substituted dispatcher, VM and persistence; no Android or network. */
class RunnerFixtureHostTest {
    @Before fun parallelPackage() { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }

    private fun row(id: String, role: String, json: String) = MessageEntity(id, "session", role, json, 0L, sortOrder = 0)
    private val rows = listOf(
        row("user", "user", """[{"type":"text","value":"question"}]"""),
        row("call", "assistant", """[{"type":"text","value":"checking"},{"type":"toolUse","value":{"toolUseId":"t1","name":"fake"}}]"""),
        row("result", "user", """[{"type":"toolResult","value":{"toolUseId":"t1","name":"fake","output":"fake","success":true}}]"""),
        row("answer", "assistant", """[{"type":"text","value":"actual answer"}]"""),
    )

    @Test fun explicitRemoteAttachmentsAreRefusedBeforeTouchingTheSharedViewModel() = runBlocking {
        val runner = RunnerFixture(rows)
        val result = runner.prompt(
            Context(), "session", "question", attachments = listOf(InputAttachment("remote")),
            wait = true, timeoutMs = 1000, persistentTurn = "turn",
        )
        assertEquals("Blocked", result.status)
        assertEquals(0, runner.viewModelAccesses)
        assertTrue(runner.vm.admission._attachments.value.isEmpty())
        assertEquals(0, runner.vm.headlessCalls)
    }

    @Test fun aLocalDraftBlocksThePersistentTurnWithoutConsumingTheAttachment() = runBlocking {
        val runner = RunnerFixture(rows)
        runner.vm.admission._attachments.value = listOf("private local attachment")
        val result = runner.prompt(Context(), "session", "question", wait = true, timeoutMs = 1000, persistentTurn = "turn")
        assertEquals("Blocked", result.status)
        assertEquals(listOf("private local attachment"), runner.vm.admission._attachments.value)
        assertEquals(1, runner.vm.headlessCalls)
    }

    @Test fun parallelRunnerUsesTheFinalAnswerAfterToolResults() = runBlocking {
        val runner = RunnerFixture(rows)
        val result = runner.prompt(Context(), "session", "question", wait = true, timeoutMs = 1000, persistentTurn = "turn")
        assertEquals("Completed", result.status)
        assertEquals("actual answer", result.responseText)
        assertEquals(1, runner.vm.headlessCalls)
    }

    @Test fun normalPackagePreservesPriorAttachmentAndReceiptBehavior() = runBlocking {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        try {
            val runner = RunnerFixture(rows)
            val result = runner.prompt(
                Context(), "session", "question", attachments = listOf(InputAttachment("legacy")),
                wait = true, timeoutMs = 1000, persistentTurn = "turn",
            )
            assertEquals("Completed", result.status)
            assertEquals("checking", result.responseText) // existing behavior; not an endorsement
            assertEquals(listOf("legacy"), runner.vm.admission.capturedAttachments) // legacy sender consumes it
            assertTrue(runner.vm.admission._attachments.value.isEmpty())
        } finally { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }
    }

    @Test fun nonPersistentRpcRetainsItsExistingAttachmentPath() = runBlocking {
        val runner = RunnerFixture(rows)
        val result = runner.prompt(
            Context(), "session", "question", attachments = listOf(InputAttachment("legacy rpc")),
            wait = false, timeoutMs = 1000,
        )
        assertEquals("Running", result.status)
        assertEquals(listOf("legacy rpc"), runner.vm.admission._attachments.value)
        assertEquals(1, runner.vm.automatedCalls)
        assertEquals(0, runner.vm.headlessCalls)
    }
}
