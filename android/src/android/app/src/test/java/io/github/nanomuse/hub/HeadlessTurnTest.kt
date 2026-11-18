package io.github.nanomuse.hub

import com.openminis.app.data.db.MessageEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HeadlessTurnTest {
    private fun parts(vararg values: JSONObject): String = JSONArray(values.toList()).toString()
    private fun textPart(text: String) = JSONObject().put("type", "text").put("value", text)
    private fun toolUse(id: String) = JSONObject().put("type", "toolUse")
        .put("value", JSONObject().put("toolUseId", id).put("name", "fake_tool"))
    private fun toolResult(id: String) = JSONObject().put("type", "toolResult")
        .put("value", JSONObject().put("toolUseId", id).put("name", "fake_tool").put("output", "fake").put("success", true))
    private fun rawRow(id: String, role: String, partsJson: String, error: String? = null) =
        MessageEntity(id, "session", role, partsJson, 0L, sortOrder = 0, errorInfo = error)
    private fun row(id: String, role: String, text: String = "", error: String? = null) =
        rawRow(id, role, parts(textPart(text)), error)

    // Same text-only extraction shape as the runner; no model or Android code is run.
    private fun extractText(json: String): String = JSONArray(json).let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString("") { it.optString("value", "") }
    }

    private val history = listOf(row("old-user", "user"), row("old-answer", "assistant", "same answer"))
    private val reply = history + listOf(row("new-user", "user"), row("new-answer", "assistant", "same answer"))

    @Test fun anUnstartedOrCompactingCallNeverReadsThePreviousAnswer() = runBlocking {
        val result = HeadlessTurn.run(
            turnId = "turn-a", timeoutMs = 1000,
            // The VM refuses a compacting session, or returns before claiming this run.
            send = { it.complete(history) }, stop = { error("an unstarted turn must not stop another run") },
            text = { error("old history is not a result") },
        )
        assertEquals(HeadlessTurn.Outcome.NotStarted, result)
    }

    @Test fun setupFailureWithoutASavedUserRowCannotReuseHistory() = runBlocking {
        val result = execute { it.start(); it.complete(history) }
        assertEquals(HeadlessTurn.Outcome.Failed, result)
    }

    @Test fun aSavedUserWithoutItsOwnAnswerDoesNotReuseTheOldAnswer() = runBlocking {
        assertEquals(HeadlessTurn.Outcome.Failed, result(history + row("new-user", "user")))
    }

    @Test fun identicalTextInANewAssistantRowIsAValidReply() = runBlocking {
        assertEquals(HeadlessTurn.Outcome.Completed("same answer"), result(reply))
    }

    @Test fun aLaterLocalTurnsAnswerCannotReplaceThisTurnsReply() = runBlocking {
        val rows = history + listOf(
            row("new-user", "user"), row("new-answer", "assistant", "this reply"),
            row("local-user", "user"), row("local-answer", "assistant", "another reply"),
        )
        assertEquals(HeadlessTurn.Outcome.Completed("this reply"), result(rows, "new-answer"))
        assertEquals(HeadlessTurn.Outcome.Failed, result(rows.filterNot { it.id == "new-answer" }, "new-answer"))
    }

    @Test fun toolResultsDoNotHideTheFinalAnswerOrExtendIntoTheNextUserTurn() = runBlocking {
        val rows = history + listOf(
            row("new-user", "user", "question"),
            rawRow("first-tool-call", "assistant", parts(textPart("checking"), toolUse("t1"), toolUse("t2"))),
            rawRow("first-results", "user", parts(toolResult("t1"), toolResult("t2"))),
            rawRow("second-tool-call", "assistant", parts(toolUse("t3"))),
            rawRow("second-result", "user", parts(toolResult("t3"))),
            row("final", "assistant", "actual answer"),
            row("later-user", "user", "another question"),
            row("later-answer", "assistant", "unrelated answer"),
        )
        assertEquals(HeadlessTurn.Outcome.Completed("actual answer"), result(rows, "final"))
    }

    @Test fun theNormalPackageKeepsItsPreviousToolRowBoundary() = runBlocking {
        val rows = history + listOf(
            row("new-user", "user", "question"),
            rawRow("tool-call", "assistant", parts(textPart("checking"), toolUse("t1"))),
            rawRow("tool-result", "user", parts(toolResult("t1"))),
            row("final", "assistant", "actual answer"),
        )
        val legacy = HeadlessTurn.run(
            "turn-a", 1000, strict = false,
            send = { it.start(); it.saved("new-user"); it.complete(rows) },
            stop = { error("this turn should settle") }, text = ::extractText,
        )
        // Compatibility control: the legacy behavior can return an intermediate answer.
        assertEquals(HeadlessTurn.Outcome.Completed("checking"), legacy)
    }

    @Test fun aToolResultWithoutAFinalAssistantRowCannotReturnIntermediateText() = runBlocking {
        val rows = history + listOf(
            row("new-user", "user", "question"),
            rawRow("tool-call", "assistant", parts(textPart("checking"), toolUse("t1"))),
            rawRow("tool-result", "user", parts(toolResult("t1"))),
        )
        assertEquals(HeadlessTurn.Outcome.Failed, result(rows))
    }

    @Test fun anUnfinishedToolCallIsNotAFinalAnswer() = runBlocking {
        val rows = history + listOf(
            row("new-user", "user", "question"),
            rawRow("tool-call", "assistant", parts(textPart("checking"), toolUse("t1"))),
        )
        assertEquals(HeadlessTurn.Outcome.Failed, result(rows))
    }

    @Test fun aFailureAfterToolsCannotReturnTheEarlierAssistantText() = runBlocking {
        val rows = history + listOf(
            row("new-user", "user", "question"),
            rawRow("tool-call", "assistant", parts(textPart("checking"), toolUse("t1"))),
            rawRow("tool-result", "user", parts(toolResult("t1"))),
            row("final", "assistant", "partial", "provider failed"),
        )
        assertEquals(HeadlessTurn.Outcome.Failed, result(rows))
    }

    @Test fun mixedOrMalformedUserRowsAreNotAssumedToBeToolResults() = runBlocking {
        val ambiguousRows = listOf(
            parts(toolResult("t1"), textPart("new question")),
            parts(textPart("toolResult is just user text")),
            "[]",
            "not json",
            "[null]",
            "[{\"type\":\"toolResult\",\"value\":\"not an object\"}]",
            "[{\"type\":\"toolResult\",\"value\":{}}]",
            parts(toolResult("")),
            parts(JSONObject().put("type", "toolResult").put("value", JSONObject()
                .put("toolUseId", "t1").put("name", "fake_tool").put("output", "fake"))),
        )
        for (ambiguous in ambiguousRows) {
            val rows = history + listOf(
                row("new-user", "user", "question"),
                rawRow("tool-call", "assistant", parts(textPart("checking"), toolUse("t1"))),
                rawRow("ambiguous-user", "user", ambiguous),
                row("unrelated-answer", "assistant", "do not borrow"),
            )
            assertEquals("row=$ambiguous", HeadlessTurn.Outcome.Failed, result(rows))
        }
    }

    @Test fun ambiguousUserAfterTextCannotPromoteAnIntermediateAssistantRow() = runBlocking {
        val ambiguous = listOf("[]", "not json", parts(textPart("unexpected or truncated result")))
        for (body in ambiguous) {
            val rows = history + listOf(
                row("new-user", "user", "question"),
                row("intermediate", "assistant", "checking"),
                rawRow("ambiguous", "user", body),
                row("final", "assistant", "actual answer"),
            )
            assertEquals("ambiguous=$body", HeadlessTurn.Outcome.Failed, result(rows))
        }
    }

    @Test fun orphanedMismatchedAndDuplicateToolResultsCannotBeCrossed() = runBlocking {
        val start = history + row("new-user", "user", "question")
        val call = rawRow("call", "assistant", parts(textPart("checking"), toolUse("t1")))
        val invalidSequences = listOf(
            listOf(rawRow("orphan", "user", parts(toolResult("t1")))),
            listOf(row("intermediate", "assistant", "checking"), rawRow("orphan", "user", parts(toolResult("t1")))),
            listOf(call, rawRow("wrong-id", "user", parts(toolResult("t2")))),
            listOf(call, rawRow("wrong-name", "user", parts(JSONObject().put("type", "toolResult").put("value",
                JSONObject().put("toolUseId", "t1").put("name", "other_tool").put("output", "fake").put("success", true))))),
            listOf(call, rawRow("duplicate", "user", parts(toolResult("t1"), toolResult("t1")))),
            listOf(call), // no result was saved
        )
        for (sequence in invalidSequences) {
            val rows = start + sequence + row("final", "assistant", "do not borrow")
            assertEquals("sequence=$sequence", HeadlessTurn.Outcome.Failed, result(rows, "final"))
        }
    }

    @Test fun severalResultRowsCanResolveDifferentCallsFromTheSameAssistant() = runBlocking {
        val rows = history + listOf(
            row("new-user", "user", "question"),
            rawRow("calls", "assistant", parts(toolUse("t1"), toolUse("t2"))),
            rawRow("result-1", "user", parts(toolResult("t1"))),
            rawRow("result-2", "user", parts(toolResult("t2"))),
            row("final", "assistant", "answer"),
        )
        assertEquals(HeadlessTurn.Outcome.Completed("answer"), result(rows, "final"))
    }

    @Test fun malformedOrUnknownAssistantPartsCannotBecomeACompletedAnswer() = runBlocking {
        val badFinals = listOf(
            "not json", "[]", "[null]",
            parts(textPart("seems fine"), JSONObject().put("type", "unknown")),
            parts(JSONObject().put("type", "text").put("value", JSONObject().put("body", "not text"))),
        )
        for (bad in badFinals) {
            assertEquals("row=$bad", HeadlessTurn.Outcome.Failed, result(history + listOf(
                row("new-user", "user", "question"), rawRow("bad-answer", "assistant", bad),
            )))
        }
    }

    @Test fun aVerifiedReceiptRequiresTheOwningLoopsExactTerminalRow() = runBlocking {
        val rows = history + listOf(row("new-user", "user", "question"), row("new-answer", "assistant", "answer"))
        val witnesses = listOf(
            null,
            HeadlessTurn.TerminalAssistant(null, "stop"),
            HeadlessTurn.TerminalAssistant("", "stop"),
            HeadlessTurn.TerminalAssistant("old-answer", "stop"), // before the current user row
            HeadlessTurn.TerminalAssistant("new-user", "stop"), // not an assistant row
            HeadlessTurn.TerminalAssistant("missing", "stop"),
        )
        for (witness in witnesses) {
            val outcome = execute { it.start(); it.saved("new-user"); it.complete(rows, witness) }
            assertEquals("witness=$witness", HeadlessTurn.Outcome.Failed, outcome)
        }
        assertEquals(HeadlessTurn.Outcome.Completed("answer"), result(rows, "new-answer"))
    }

    @Test fun onlyConfirmedTerminalReasonsCanCompleteARealTextRow() = runBlocking {
        val rows = history + listOf(row("new-user", "user", "question"), row("new-answer", "assistant", "partial or final"))
        for (reason in listOf("stop", "end_turn", "completed")) {
            val outcome = execute { it.start(); it.saved("new-user"); it.complete(rows, HeadlessTurn.TerminalAssistant("new-answer", reason)) }
            assertEquals("reason=$reason", HeadlessTurn.Outcome.Completed("partial or final"), outcome)
        }
        for (reason in listOf(null, "", "length", "max_tokens", "content_filter", "tool_calls", "tool_use", "function_call")) {
            val outcome = execute { it.start(); it.saved("new-user"); it.complete(rows, HeadlessTurn.TerminalAssistant("new-answer", reason)) }
            assertEquals("reason=$reason", HeadlessTurn.Outcome.Failed, outcome)
        }
    }

    @Test fun aFinalAnswerCanHaveSeveralTextParts() = runBlocking {
        val final = rawRow("final", "assistant", parts(textPart("first "), textPart("second")))
        assertEquals(HeadlessTurn.Outcome.Completed("first second"), result(history + row("new-user", "user") + final))
    }

    @Test fun anOrdinaryShortAnswerCanMentionTheTruncationMarker() = runBlocking {
        val answer = "[example]\n\n[Content truncated at 500 KB — original length 500001 chars]"
        assertEquals(HeadlessTurn.Outcome.Completed(answer), result(history + listOf(
            row("new-user", "user", "question"), row("final", "assistant", answer),
        )))
    }

    @Test fun inconsistentSessionOrUnexpectedRoleFailsClosed() = runBlocking {
        val start = history + row("new-user", "user", "question")
        val otherSession = row("answer", "assistant", "wrong").copy(sessionId = "other-session")
        assertEquals(HeadlessTurn.Outcome.Failed, result(start + otherSession))
        assertEquals(HeadlessTurn.Outcome.Failed, result(start + row("unknown", "tool", "wrong")))
    }

    @Test fun emptyAndErroredAnswersNeverCompleteTheTurn() = runBlocking {
        for (answer in listOf(row("new-answer", "assistant", "  "), row("new-answer", "assistant", "partial", "provider failed"))) {
            assertEquals(HeadlessTurn.Outcome.Failed, result(history + listOf(row("new-user", "user"), answer)))
        }
    }

    @Test fun aRemovedUserRowCannotBorrowAnotherTurnsAnswer() = runBlocking {
        assertEquals(HeadlessTurn.Outcome.Failed, result(history + row("different-answer", "assistant", "unrelated")))
    }

    @Test fun aLocalStopWithAPartialReplyStaysCancelled() = runBlocking {
        val result = execute { it.start(); it.saved("new-user"); it.requestStop(); it.complete(reply); it.jobEnded(true) }
        assertEquals(HeadlessTurn.Outcome.Cancelled, result)
    }

    @Test fun aHandledFailureWithAPartialReplyStaysFailed() = runBlocking {
        val result = execute { it.start(); it.saved("new-user"); it.fail(); it.complete(reply) }
        assertEquals(HeadlessTurn.Outcome.Failed, result)
    }

    @Test fun completionPinsTheResultBeforeARetryOrQueuedTurn() = runBlocking {
        val rows = reply.toMutableList()
        val result = execute {
            it.start(); it.saved("new-user"); it.complete(rows, HeadlessTurn.TerminalAssistant("new-answer", "stop"))
            rows[rows.lastIndex] = row("replacement-answer", "assistant", "retry output")
            assertFalse(it.requestStop())
        }
        assertEquals(HeadlessTurn.Outcome.Completed("same answer"), result)
    }

    @Test fun aTimeoutStopsOnlyTheReceiptThatWasStarted() = runBlocking {
        var sent: HeadlessTurn.Receipt? = null
        var stopped: HeadlessTurn.Receipt? = null
        val result = HeadlessTurn.run(
            turnId = "turn-a", timeoutMs = 1,
            send = { sent = it; it.start(); it.saved("new-user") },
            stop = { stopped = it; it.requestStop() },
            text = { error("a timed-out turn has no completed result") },
        )
        assertEquals(HeadlessTurn.Outcome.TimedOut, result)
        assertSame(sent, stopped)
        assertTrue(checkNotNull(sent).pending)
        checkNotNull(sent).jobEnded(true)
        assertFalse(checkNotNull(sent).pending)
    }

    @Test fun aStopRequestDoesNotCompleteUntilItsOwningJobEnds() = runBlocking {
        val started = CompletableDeferred<HeadlessTurn.Receipt>()
        val result = async { execute { it.start(); it.saved("new-user"); started.complete(it) } }
        val receipt = started.await()
        assertTrue(HeadlessTurn.stop(receipt, "turn-a") {})
        receipt.complete(reply)
        yield()
        assertTrue(receipt.pending)
        assertFalse(result.isCompleted)
        receipt.jobEnded(true)
        assertEquals(HeadlessTurn.Outcome.Cancelled, result.await())
        assertFalse(receipt.pending)
    }

    @Test fun cancelledIsPublishedOnlyAfterTheChildFinishesCleanup() = runBlocking {
        val started = CompletableDeferred<HeadlessTurn.Receipt>()
        val cleaningUp = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var setup: Job? = null
        var stream: Job? = null
        val result = async {
            HeadlessTurn.run(
                turnId = "turn-a", timeoutMs = 5000,
                send = { receipt ->
                    receipt.start(); receipt.saved("new-user")
                    setup = launch {
                        stream = launch {
                            try {
                                started.complete(receipt)
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    cleaningUp.complete(Unit)
                                    releaseCleanup.await()
                                }
                            }
                        }
                    }.also { parent ->
                        parent.invokeOnCompletion { cause -> receipt.jobEnded(cause is CancellationException) }
                    }
                },
                stop = { error("this test must not time out") }, text = { it },
            )
        }
        val receipt = started.await()
        assertTrue(HeadlessTurn.stop(receipt, "turn-a") { checkNotNull(stream).cancel() })
        cleaningUp.await()
        yield()
        assertFalse(checkNotNull(setup).isCompleted)
        assertTrue(receipt.pending)
        assertFalse(result.isCompleted)
        releaseCleanup.complete(Unit)
        checkNotNull(setup).join()
        assertEquals(HeadlessTurn.Outcome.Cancelled, result.await())
        assertFalse(receipt.pending)
    }

    @Test fun cancellationBeforeTheStreamStartsStillSettlesFromTheSetupJob() = runBlocking {
        val result = execute { it.start(); it.requestStop(); it.fail(); it.jobEnded(true) }
        assertEquals(HeadlessTurn.Outcome.Cancelled, result)
    }

    @Test fun aLateRemoteStopDoesNotCancelOrMarkAFinishedRun() {
        val receipt = HeadlessTurn.Receipt("turn-a")
        receipt.start(); receipt.saved("new-user"); receipt.complete(reply)
        val running = HubTasks()
        running.started("socket-a", "caller", "conversation", "session")
        var cancelled = 0
        // The execution result is ready, but HubActions has not yet removed its run.
        assertFalse(running.stop("caller", "socket-a", null) {
            HeadlessTurn.stop(receipt, "turn-a") { cancelled++ }
        })
        assertEquals(0, cancelled)
        assertFalse(running.finished("socket-a"))
    }

    @Test fun stopAndCompletionRaceSettlesExactlyOnce() = runBlocking {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val receipt = HeadlessTurn.Receipt("turn-a", strict = true)
                receipt.start(); receipt.saved("new-user")
                val gate = CountDownLatch(1)
                val stopped = workers.submit<Boolean> { gate.await(); receipt.requestStop() }
                val completed = workers.submit { gate.await(); receipt.complete(reply, HeadlessTurn.TerminalAssistant("new-answer", "stop")) }
                gate.countDown()
                val stopWon = stopped.get(5, TimeUnit.SECONDS)
                completed.get(5, TimeUnit.SECONDS)
                receipt.jobEnded(cancelled = false)
                val end = receipt.awaitEnd()
                if (stopWon) assertEquals(HeadlessTurn.End.Cancelled, end)
                else assertEquals(HeadlessTurn.End.Completed(reply.last().partsJson), end)
                assertFalse(receipt.pending)
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun aRemoteStopTargetsOnlyTheSameLiveTurnOnce() {
        val next = HeadlessTurn.Receipt("turn-b")
        next.start()
        var cancelled = 0
        assertFalse(HeadlessTurn.stop(next, "turn-a") { cancelled++ })
        assertEquals(0, cancelled)
        assertTrue(HeadlessTurn.stop(next, "turn-b") { cancelled++ })
        assertFalse(HeadlessTurn.stop(next, "turn-b") { cancelled++ })
        assertEquals(1, cancelled)
        assertTrue(next.pending)
        next.jobEnded(true)
        assertFalse(next.pending)
    }

    private suspend fun execute(send: (HeadlessTurn.Receipt) -> Unit) = HeadlessTurn.run(
        turnId = "turn-a", timeoutMs = 1000, strict = true,
        send = send, stop = { error("this turn should settle") }, text = ::extractText,
    )

    private suspend fun result(rows: List<MessageEntity>, terminalId: String? = rows.lastOrNull { it.role == "assistant" }?.id) = execute {
        // Tests supply a simulated loop witness; they do not run the actual agent loop.
        it.start(); it.saved("new-user"); it.complete(rows, HeadlessTurn.TerminalAssistant(terminalId, "stop"))
    }
}
