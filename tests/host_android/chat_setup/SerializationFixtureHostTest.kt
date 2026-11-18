package io.github.nanomuse.hub

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import testfixtures.serialization.AssistantBlock
import testfixtures.serialization.SerializationFixture

/** Actual block and JSON serialization methods with fake blocks, route metadata and persistence. */
class SerializationFixtureHostTest {
    private fun row(id: String, role: String, json: String) = MessageEntity(id, "session", role, json, 0L, sortOrder = 0)
    private fun text(json: String): String = JSONArray(json).let { parts ->
        (0 until parts.length()).mapNotNull(parts::optJSONObject)
            .filter { it.optString("type") == "text" }.joinToString("") { it.optString("value") }
    }

    @Test fun realSerializersProduceMatchingToolRowsAndAFinalTextRow() = runBlocking {
        val serializer = SerializationFixture()
        val blocks = listOf(
            AssistantBlock("text", "text", content = "checking "),
            AssistantBlock("t1", "tool_use", toolName = "fake_tool", thoughtSignature = "signature"),
            AssistantBlock("t2", "tool_use", toolName = "another_tool"),
            AssistantBlock("thinking", "thinking", content = "not persisted as a part"),
        )
        val call = row("call", "assistant", serializer.assistant(
            blocks, mapOf("t1" to "{\"q\":\"value\"}", "t2" to "{}"), "a".repeat(64),
        ))
        val result = checkNotNull(serializer.results(listOf(
            AgentContentPart.ToolResult("t1", "fake_tool", "first result"),
            AgentContentPart.ToolResult("t2", "another_tool", "failed result", isError = true),
        )))
        val answer = row("answer", "assistant", serializer.assistant(listOf(AssistantBlock("answer", "text", "final answer"))))
        val rows = listOf(row("user", "user", "[{\"type\":\"text\",\"value\":\"question\"}]"), call, result, answer)
        val outcome = HeadlessTurn.run("turn", 1000, strict = true,
            send = { it.start(); it.saved("user"); it.complete(rows, HeadlessTurn.TerminalAssistant("answer", "stop")) },
            stop = { error("this turn settled") }, text = ::text,
        )
        assertEquals(HeadlessTurn.Outcome.Completed("final answer"), outcome)
        assertEquals("assistant", call.role)
        assertEquals("user", result.role)
        assertEquals("signature", JSONArray(call.partsJson).getJSONObject(1).getJSONObject("value").getString("thoughtSignature"))
    }

    @Test fun escapedIdentifiersStillMatchAcrossTheActualSerializers() = runBlocking {
        val serializer = SerializationFixture()
        val id = "id\"\\\n雪"
        val name = "fake\"_tool"
        val call = row("call", "assistant", serializer.assistant(listOf(AssistantBlock(id, "tool_use", toolName = name))))
        val result = checkNotNull(serializer.results(listOf(AgentContentPart.ToolResult(id, name, "line\nvalue"))))
        val answer = row("answer", "assistant", serializer.assistant(listOf(AssistantBlock("text", "text", "done"))))
        val rows = listOf(row("user", "user", "[]"), call, result, answer)
        val outcome = HeadlessTurn.run("turn", 1000, strict = true,
            send = { it.start(); it.saved("user"); it.complete(rows, HeadlessTurn.TerminalAssistant("answer", "end_turn")) },
            stop = { error("this turn settled") }, text = ::text,
        )
        assertEquals(HeadlessTurn.Outcome.Completed("done"), outcome)
    }

    @Test fun aDifferentResultNameCannotBeCrossedEvenWhenBothSerializersAcceptIt() = runBlocking {
        val serializer = SerializationFixture()
        val call = row("call", "assistant", serializer.assistant(listOf(AssistantBlock("t1", "tool_use", toolName = "first"))))
        val result = checkNotNull(serializer.results(listOf(AgentContentPart.ToolResult("t1", "second", "fake"))))
        val answer = row("answer", "assistant", serializer.assistant(listOf(AssistantBlock("text", "text", "do not borrow"))))
        val rows = listOf(row("user", "user", "[]"), call, result, answer)
        val outcome = HeadlessTurn.run("turn", 1000, strict = true,
            send = { it.start(); it.saved("user"); it.complete(rows, HeadlessTurn.TerminalAssistant("answer", "completed")) },
            stop = { error("this turn settled") }, text = ::text,
        )
        assertEquals(HeadlessTurn.Outcome.Failed, outcome)
    }
}
