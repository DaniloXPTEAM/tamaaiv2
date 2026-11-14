package io.github.nanomuse.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.AgentTools
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSizeTest {
    private val identity = "  PERSONALIDADE-PRIVADA\n\nAvailable tools:\nfalso "
    private val template = """Introduction.

Available tools:
- tools

Sandbox directory /var/minis/ (writeability is stated per path):
- dirs

The minis:// URL scheme:
- urls

File creation guidelines:
- files

Tool call style:
- calls

Tone and style:
- tone

Android-only tools (android-* CLIs):
- minis-open original ${"x".repeat(900)}
- android-weather stays

Environment variables:
- secrets

Scheduled tasks: no guarantees.  """

    @Test fun `system sections sum to the exact layout before and after diet`() {
        val before = identity + template
        val after = PromptDiet.prompt(before)
        val stable = listOf(PromptSize.Section.RISK_POLICY to " risk ", PromptSize.Section.HANDS to "hands")
        val volatile = listOf(
            PromptSize.Section.SKILLS to " skills ",
            PromptSize.Section.MCP to null,
            PromptSize.Section.GLOBAL_MEMORY to " SEGREDO-DE-MEMORIA ",
        )
        val rows = PromptSize.system(before, after, identity.length, stable, volatile, " runtime ")
        val total = rows.single { it.label == PromptSize.Section.TOTAL }
        val beforeLayout = PromptCacheLayout.build(listOf(before, " risk ", "hands"), listOf(" skills ", null, " SEGREDO-DE-MEMORIA "), " runtime ")
        val afterLayout = PromptCacheLayout.build(listOf(after, " risk ", "hands"), listOf(" skills ", null, " SEGREDO-DE-MEMORIA "), " runtime ")
        assertEquals(beforeLayout.prompt.length, total.beforeChars)
        assertEquals(afterLayout.prompt.length, total.afterChars)
        assertEquals(total.beforeChars, rows.filter { it.label != PromptSize.Section.TOTAL }.sumOf { it.beforeChars })
        assertEquals(total.afterChars, rows.filter { it.label != PromptSize.Section.TOTAL }.sumOf { it.afterChars })
        assertTrue(rows.single { it.label == PromptSize.Section.ANDROID_TOOLS }.afterChars <
            rows.single { it.label == PromptSize.Section.ANDROID_TOOLS }.beforeChars)
        assertTrue(rows.single { it.label == PromptSize.Section.AVAILABLE_TOOLS }.afterChars < 50)
        val log = PromptSize.logLines("system", rows).joinToString("\n")
        assertFalse(log.contains("PERSONALIDADE-PRIVADA"))
        assertFalse(log.contains("SEGREDO-DE-MEMORIA"))
        assertFalse(log.contains("android-weather"))
    }

    @Test fun `unknown or empty templates remain accurate without inventing sections`() {
        val stable = listOf(PromptSize.Section.HANDS to " \n ")
        val volatile = listOf(PromptSize.Section.MCP to "mcp")
        val rows = PromptSize.system("unknown", "x", 0, stable, volatile, null)
        assertTrue(rows.any { it.label == PromptSize.Section.BASE_UNCLASSIFIED })
        assertFalse(rows.any { it.label == PromptSize.Section.ANDROID_TOOLS })
        assertEquals(
            PromptCacheLayout.build(listOf("x", " \n "), listOf("mcp"), null).prompt.length,
            rows.single { it.label == PromptSize.Section.TOTAL }.afterChars,
        )
        val empty = PromptSize.system("", "", 0, emptyList(), emptyList(), "runtime")
        assertEquals(9, empty.single { it.label == PromptSize.Section.TOTAL }.afterChars)
        assertEquals(2, empty.single { it.label == PromptSize.Section.SEPARATORS }.afterChars)
    }

    @Test fun `token estimate uses utf16 units and rounds up`() {
        assertEquals(0, PromptSize.estimateTokens(0))
        assertEquals(1, PromptSize.estimateTokens(1))
        assertEquals(1, PromptSize.estimateTokens(3))
        assertEquals(2, PromptSize.estimateTokens(4))
        assertEquals(2, "😀".length)
        assertEquals(1, PromptSize.estimateTokens("😀".length))
    }

    @Test fun `real tools match serialized arrays and browser diet is measurable`() {
        val before = AgentTools.makeAgentTools()
        val after = PromptDiet.tools(before)
        val rows = PromptSize.tools(before, after)
        val total = rows.single { it.label == PromptSize.Tool.TOTAL }
        val rawArray = JSONArray().apply { before.forEach { put(it.toOpenAIJson()) } }
        val dietArray = JSONArray().apply { after.forEach { put(it.toOpenAIJson()) } }
        assertEquals(rawArray.toString().length, total.beforeChars)
        assertEquals(dietArray.toString().length, total.afterChars)
        assertEquals(total.afterChars, rows.filter { it.label != PromptSize.Tool.TOTAL }.sumOf { it.afterChars })
        val browser = rows.single { it.label == PromptSize.Tool.BROWSER_USE }
        assertTrue(browser.beforeChars - browser.afterChars > 2_500)
        assertEquals(browser.beforeChars - browser.afterChars, total.beforeChars - total.afterChars)
        val disabled = AgentTools.makeAgentTools(supportsImageInput = false, memoryEnabled = false)
        val disabledRows = PromptSize.tools(disabled, PromptDiet.tools(disabled))
        assertFalse(disabledRows.any { it.label == PromptSize.Tool.MEMORY_GET })
        assertFalse(disabledRows.any { it.label == PromptSize.Tool.READ_IMAGE })
        assertEquals(0, PromptSize.tools(emptyList(), emptyList()).last().afterChars)
    }

    @Test fun `unknown tool names and descriptions are never copied into diagnostics`() {
        val privateTool = AgentToolDefinition(
            name = "NOME-PRIVADO", description = "CONTEUDO-PRIVADO",
            parameters = mapOf("private" to AgentToolParam("string", "OUTRO-SEGREDO")),
        )
        val rows = PromptSize.tools(listOf(privateTool), listOf(privateTool))
        val log = PromptSize.logLines("tool", rows).joinToString("\n")
        assertTrue(log.contains("OTHER"))
        assertFalse(log.contains("NOME-PRIVADO"))
        assertFalse(log.contains("CONTEUDO-PRIVADO"))
        assertFalse(log.contains("OUTRO-SEGREDO"))
    }
}
