package io.github.nanomuse.hub

import com.openminis.app.BuildConfig
import com.openminis.app.data.db.MessageEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import testfixtures.headless.AdmissionFixture
import testfixtures.headless.FakeMessage
import testfixtures.headless.FinishFixture
import testfixtures.headless.TruncationFixture

/** Extracted admission and callback/settlement wiring; no real VM, database, loop or network. */
class HeadlessFixtureHostTest {
    @Before fun parallelPackage() { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }

    private fun row(id: String, role: String, text: String) = MessageEntity(
        id, "session", role,
        """[{"type":"text","value":"$text"}]""", 0L, sortOrder = 0,
    )
    private val rows = listOf(row("user", "user", "question"), row("answer", "assistant", "answer"))
    private fun text(json: String) = JSONArray(json).getJSONObject(0).getString("value")

    @Test fun becomingBusyBetweenTheTwoChecksDoesNotQueueAnUnreceiptedRemoteTurn() = runBlocking {
        val fixture = AdmissionFixture()
        fixture.onContextCheck = { fixture._isStreaming.value = true }
        val result = HeadlessTurn.run(
            "turn", 1000, strict = true,
            send = { fixture.nmSendHeadless("remote prompt", it) },
            stop = { error("the refused turn must not stop another") },
            text = ::text,
        )
        assertEquals(HeadlessTurn.Outcome.NotStarted, result)
        assertEquals(0, fixture.accepted)
        assertTrue(fixture.queued.isEmpty())
    }

    @Test fun aBusyLocalPromptIsStillQueuedWithItsGeneration() {
        val fixture = AdmissionFixture()
        fixture._isStreaming.value = true
        fixture.sendLocal("local prompt", 123L)
        assertEquals(listOf("local prompt" to 123L), fixture.queued)
        assertEquals(0, fixture.accepted)
    }

    @Test fun existingBusyCompactingPendingAndDialogGatesRefuseRemoteAdmission() {
        fun refused(fixture: AdmissionFixture) {
            val receipt = HeadlessTurn.Receipt("turn")
            fixture.nmSendHeadless("remote prompt", receipt)
            assertFalse(receipt.started)
            assertTrue(fixture.queued.isEmpty())
            assertEquals(0, fixture.accepted)
        }
        refused(AdmissionFixture().apply { _isStreaming.value = true })
        refused(AdmissionFixture().apply { _isCompacting.value = true })
        refused(AdmissionFixture().apply { contextAction = AdmissionFixture.PreSendContextAction.ASK_USER })
        refused(AdmissionFixture().apply { nmHeadlessReceipt = HeadlessTurn.Receipt("old").also { it.start() } })
    }

    @Test fun aPersistentTurnDoesNotConsumeOrOverwriteTheLocalDraft() {
        val draftStates: List<AdmissionFixture.() -> Unit> = listOf(
            { _attachments.value = listOf("private attachment") },
            { _pastedTexts.value = listOf("private paste") },
            { _editingMessageId.value = "local-edit" },
            { _hasInjectedShareContent.value = true },
        )
        for (setDraft in draftStates) {
            val fixture = AdmissionFixture().apply(setDraft)
            val receipt = HeadlessTurn.Receipt("turn")
            fixture.nmSendHeadless("remote prompt", receipt)
            assertFalse(receipt.started)
            assertEquals(0, fixture.accepted)
            assertTrue(fixture.queued.isEmpty())
        }
    }

    @Test fun aDraftAppearingBetweenChecksAlsoRefusesTheRemoteTurn() {
        val fixture = AdmissionFixture()
        fixture.onContextCheck = { fixture._attachments.value = listOf("private attachment") }
        val receipt = HeadlessTurn.Receipt("turn")
        fixture.nmSendHeadless("remote prompt", receipt)
        assertFalse(receipt.started)
        assertEquals(0, fixture.accepted)
        assertTrue(fixture.queued.isEmpty())
        assertEquals(listOf("private attachment"), fixture._attachments.value)
    }

    @Test fun aComposerMutationImmediatelyAfterTheGuardIsNotConsumed() {
        val fixture = AdmissionFixture()
        fixture.afterGuard = {
            fixture._attachments.value = listOf("late private attachment")
            fixture._editingMessageId.value = "late-edit"
            fixture._pastedTexts.value = listOf("late paste")
            fixture._hasInjectedShareContent.value = true
        }
        val receipt = HeadlessTurn.Receipt("turn", strict = true)
        fixture.nmSendHeadless("remote prompt", receipt)
        assertTrue(receipt.started)
        assertEquals(emptyList<String>(), fixture.capturedAttachments)
        assertEquals(null, fixture.capturedEditId)
        assertEquals(listOf("late private attachment"), fixture._attachments.value)
        assertEquals("late-edit", fixture._editingMessageId.value)
        assertEquals(listOf("late paste"), fixture._pastedTexts.value)
        assertTrue(fixture._hasInjectedShareContent.value)
    }

    @Test fun normalPackageRetainsItsPriorAdmissionAndFailureWiring() = runBlocking {
        BuildConfig.APPLICATION_ID = "ai.tama.assistant"
        try {
            val withDraft = AdmissionFixture().apply { _attachments.value = listOf("legacy draft") }
            val admitted = HeadlessTurn.Receipt("legacy", strict = false)
            withDraft.nmSendHeadless("remote prompt", admitted)
            assertTrue(admitted.started) // This documents the old behavior, not its safety.
            assertEquals(1, withDraft.accepted)

            val busy = AdmissionFixture().apply { onContextCheck = { _isStreaming.value = true } }
            val refused = HeadlessTurn.Receipt("legacy", strict = false)
            busy.nmSendHeadless("remote prompt", refused)
            assertFalse(refused.started)
            assertEquals(listOf("remote prompt" to null), busy.queued)

            val finish = FinishFixture(rows)
            val receipt = HeadlessTurn.Receipt("legacy", strict = false).also { it.start(); it.saved("user") }
            finish.finish(receipt, failure = true)
            assertEquals(HeadlessTurn.End.Completed(rows.last().partsJson), withTimeout(1000) { receipt.awaitEnd() })
        } finally {
            BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test"
        }
    }

    @Test fun aSuccessfulSimulatedTurnSettlesFromItsOwnRows() = runBlocking {
        val admission = AdmissionFixture()
        val finish = FinishFixture(rows)
        val result = HeadlessTurn.run(
            "turn", 1000, strict = true,
            send = { receipt ->
                admission.nmSendHeadless("question", receipt)
                receipt.saved("user")
                finish.finish(receipt, failure = false)
            },
            stop = { error("this turn should settle") }, text = ::text,
        )
        assertEquals(HeadlessTurn.Outcome.Completed("answer"), result)
        assertEquals(1, admission.accepted)
        assertEquals(1, finish.chatRepository.dao.reads)
    }

    @Test fun missingOrUnconfirmedLoopWitnessCannotReturnAnApparentlyValidRow() = runBlocking {
        for (witness in listOf(null, HeadlessTurn.TerminalAssistant("answer", "length"))) {
            val finish = FinishFixture(rows)
            val receipt = HeadlessTurn.Receipt("turn", strict = true).also { it.start(); it.saved("user") }
            finish.finish(receipt, failure = false, terminal = witness)
            assertEquals("witness=$witness", HeadlessTurn.End.Failed, withTimeout(1000) { receipt.awaitEnd() })
        }
    }

    @Test fun aHandledLoopFailureOverridesAnApparentlyValidPersistedAnswer() = runBlocking {
        val finish = FinishFixture(rows)
        val result = HeadlessTurn.run(
            "turn", 1000, strict = true,
            send = { receipt ->
                receipt.start(); receipt.saved("user")
                // No UI error and a plausible final row: only the callback marks the failure.
                finish.finish(receipt, failure = true)
            },
            stop = { error("this turn should settle") }, text = ::text,
        )
        assertEquals(HeadlessTurn.Outcome.Failed, result)
        assertEquals(0, finish.chatRepository.dao.reads)
    }

    @Test fun oversizedSerializedToolRowsCannotMasqueradeAsAFinalTextAnswer() = runBlocking {
        val longBody = "x".repeat(TruncationFixture.MAX_MESSAGE_PARTS_JSON_LENGTH + 1)
        val originalCall = JSONArray()
            .put(JSONObject().put("type", "text").put("value", "checking"))
            .put(JSONObject().put("type", "toolUse").put("value", JSONObject().put("input", longBody)))
            .toString()
        val originalResult = JSONArray().put(
            JSONObject().put("type", "toolResult").put("value", JSONObject().put("output", longBody)),
        ).toString()
        val cappedCall = TruncationFixture.buildTruncatedPartsJson(originalCall)
        val cappedResult = TruncationFixture.buildTruncatedPartsJson(originalResult)
        val truncatedRows = listOf(
            row("user", "user", "question"),
            row("call", "assistant", "ignored").copy(partsJson = cappedCall),
            row("tool", "user", "ignored").copy(partsJson = cappedResult),
            row("answer", "assistant", "real final answer"),
        )
        val outcome = HeadlessTurn.run(
            "turn", 1000, strict = true,
            send = { receipt -> receipt.start(); receipt.saved("user"); receipt.complete(truncatedRows, HeadlessTurn.TerminalAssistant("answer", "stop")) },
            stop = { error("this turn should settle") }, text = ::text,
        )
        // The original shape is no longer knowable from these capped rows: fail
        // closed instead of returning the serialized tool call as the answer.
        assertTrue("a capped tool call must not become a completed answer", outcome is HeadlessTurn.Outcome.Failed)
    }

    @Test fun aCappedFinalTextIsReportedAsIncompleteRatherThanRawSerializedJson() = runBlocking {
        val longAnswer = "x".repeat(TruncationFixture.MAX_MESSAGE_PARTS_JSON_LENGTH + 1)
        val original = JSONArray().put(JSONObject().put("type", "text").put("value", longAnswer)).toString()
        val capped = TruncationFixture.buildTruncatedPartsJson(original)
        val cappedRows = listOf(row("user", "user", "question"), row("answer", "assistant", "").copy(partsJson = capped))
        val outcome = HeadlessTurn.run(
            "turn", 1000, strict = true,
            send = { receipt -> receipt.start(); receipt.saved("user"); receipt.complete(cappedRows, HeadlessTurn.TerminalAssistant("answer", "stop")) },
            stop = { error("this turn should settle") }, text = ::text,
        )
        assertTrue("a truncated serialized answer is not complete", outcome is HeadlessTurn.Outcome.Failed)
    }

    @Test fun aResumeOrVisibleErrorAlsoFailsWithoutReadingTheRows() = runBlocking {
        for (resume in listOf(true, false)) {
            val finish = FinishFixture(rows)
            if (resume) finish._canResume.value = true
            else finish._messages.value = listOf(FakeMessage("assistant", "provider error"))
            val receipt = HeadlessTurn.Receipt("turn").also { it.start(); it.saved("user") }
            finish.finish(receipt, failure = false)
            assertEquals(HeadlessTurn.End.Failed, withTimeout(1000) { receipt.awaitEnd() })
            assertEquals(0, finish.chatRepository.dao.reads)
        }
    }

    @Test fun stopStillWaitsForOwnerCompletionWhenTheFailureCallbackRuns() = runBlocking {
        val finish = FinishFixture(rows)
        val receipt = HeadlessTurn.Receipt("turn").also { it.start(); it.saved("user") }
        assertTrue(receipt.requestStop())
        finish.finish(receipt, failure = true)
        assertTrue(receipt.pending)
        assertEquals(0, finish.chatRepository.dao.reads)
        receipt.jobEnded(true)
        assertEquals(HeadlessTurn.End.Cancelled, withTimeout(1000) { receipt.awaitEnd() })
    }
}
