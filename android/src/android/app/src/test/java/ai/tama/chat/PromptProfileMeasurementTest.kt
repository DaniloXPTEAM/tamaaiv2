package ai.tama.chat

import io.github.nanomuse.chat.*

import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulStore
import com.openminis.app.agent.SystemPromptBuilder
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.tools.AgentTools
import io.github.nanomuse.guard.RiskPolicy
import ai.tama.hands.HandsCapabilityPrompt
import ai.tama.media.MediaPrompt
import ai.tama.reach.ComputersPrompt
import ai.tama.router.Profile
import ai.tama.router.ToolGroup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A synthetic, count-only fixture: default identity, memory enabled with empty files, one
 * invented skill, Hands off, no configured media or other device, no onboarding addendum.
 * It uses the same templates, assembly and actual serialized tool definitions as the app.
 * It does not exercise Android, provider tokenizers, history, attachments or a paid service.
 */
class PromptProfileMeasurementTest {
    private val input: PromptProfiles.Input by lazy {
        val identity = SystemPromptBuilder.identitySectionFromFile(SoulMDParser.parse(SoulStore.DEFAULT_CONTENT))
        val hands = HandsCapabilityPrompt.build(HandsCapabilityPrompt.State(
            enabled = false, androidOk = true, serviceOn = false, overlayOk = false,
            modelLabel = null, deepLink = "minis://settings/hands",
        ))
        PromptProfiles.Input(
            identity = identity,
            base = AgentBasePrompt.build(identity),
            stable = listOf(
                PromptSize.Section.RISK_POLICY to RiskPolicy.promptParagraph(),
                PromptSize.Section.HANDS to hands,
            ),
            volatile = listOf(
                PromptSize.Section.SKILLS to """Skills:
Reusable instruction sets stored at /var/minis/skills/<name>/SKILL.md. Read the SKILL.md file to load full instructions before using a skill.

<available_skills>
  <skill>
    <name>example</name>
    <description>A synthetic skill for the host fixture.</description>
    <path>/var/minis/skills/example/SKILL.md</path>
  </skill>
</available_skills>""",
                PromptSize.Section.MCP to null,
                PromptSize.Section.NAMING to null,
                PromptSize.Section.MEDIA to MediaPrompt.build(null, null, "minis://settings/media"),
                PromptSize.Section.COMPUTERS to ComputersPrompt.build(emptyList(), false, "minis://settings/devices"),
                PromptSize.Section.SESSION to null,
                PromptSize.Section.GOALS to null,
                PromptSize.Section.FEED to null,
                PromptSize.Section.MEMORY_TOOLS to AgentMemoryPrompt.toolList(true),
                PromptSize.Section.MEMORY_GUIDANCE to AgentMemoryPrompt.guidance(true),
                PromptSize.Section.GLOBAL_MEMORY to null,
                PromptSize.Section.DAILY_MEMORY to null,
                PromptSize.Section.USER_FILE to null,
            ),
            runtime = "Runtime context:\n- Current date: 2026-01-01 (UTC)\n" +
                "- Device language: pt-BR\n- minis-model-use models available: 0",
        )
    }

    @Test fun `measure the shared real templates and serialized tools by profile`() {
        val original = AgentTools.makeAgentTools(supportsImageInput = true, memoryEnabled = true)
        val cases = listOf(
            Triple("LEAN", Profile.LEAN, emptySet()),
            Triple("BASIC", Profile.BASIC, setOf(ToolGroup.FILES)),
            Triple("BASIC_WEB", Profile.BASIC, setOf(ToolGroup.WEB)),
            Triple("BASIC_ANDROID", Profile.BASIC, setOf(ToolGroup.ANDROID)),
            Triple("BASIC_IMAGE", Profile.BASIC, setOf(ToolGroup.IMAGE)),
            Triple("FULL", Profile.FULL, emptySet()),
        )
        for ((caseName, profile, hints) in cases) {
            val system = PromptProfiles.build(profile, input, hints)
            val tools = PromptProfiles.tools(profile, original, hints)
            val toolSizes = PromptSize.tools(if (profile == Profile.FULL) original else emptyList(), tools)
            val systemTotal = system.sizes.single { it.label == PromptSize.Section.TOTAL }
            val toolTotal = toolSizes.single { it.label == PromptSize.Tool.TOTAL }
            val toolsJson = if (tools.isEmpty()) "" else JSONArray().apply {
                tools.forEach { put(it.toOpenAIJson()) }
            }.toString()
            assertEquals(system.prompt.length, systemTotal.afterChars)
            assertEquals(toolsJson.length, toolTotal.afterChars)
            assertEquals(systemTotal.afterChars, system.sizes.filter { it.label != PromptSize.Section.TOTAL }.sumOf { it.afterChars })
            assertEquals(toolTotal.afterChars, toolSizes.filter { it.label != PromptSize.Tool.TOTAL }.sumOf { it.afterChars })
            if (profile == Profile.LEAN) assertTrue(tools.isEmpty())
            if (profile == Profile.FULL) {
                val expected = PromptCacheLayout.build(
                    listOf(CompactFullPrompt.base(input.identity, input.base)) + input.stable.mapNotNull { it.second },
                    input.volatile.map { it.second }, input.runtime,
                )
                assertEquals(expected.prompt, system.prompt)
                assertEquals(PromptDiet.tools(original), tools)
                assertTrue(system.prompt.contains(RiskPolicy.promptParagraph()))
                assertTrue(system.prompt.contains(input.stable.single { it.first == PromptSize.Section.HANDS }.second!!))
            }
            system.sizes.forEach { row ->
                println("TAMA_PROFILE $caseName SYSTEM ${row.label.name} " +
                    "${row.beforeChars} ${row.afterChars} ${row.beforeTokens} ${row.afterTokens}")
            }
            toolSizes.forEach { row ->
                println("TAMA_PROFILE $caseName TOOL ${row.label.name} " +
                    "${row.beforeChars} ${row.afterChars} ${row.beforeTokens} ${row.afterTokens}")
            }

            // Regression ceilings for this fixture, not a promise about a real account or turn.
            val ceiling = mapOf(
                "LEAN" to 400, "BASIC" to 3_400, "BASIC_WEB" to 4_800,
                "BASIC_ANDROID" to 3_700, "BASIC_IMAGE" to 4_500, "FULL" to 8_000,
            ).getValue(caseName)
            val estimate = PromptSize.estimateTokens(systemTotal.afterChars + toolTotal.afterChars)
            assertTrue("$caseName synthetic fixed prompt + tools: $estimate exceeds $ceiling", estimate <= ceiling)
        }
    }

    @Test fun `full keeps every schema safety block and mutable fragment`() {
        val original = AgentTools.makeAgentTools(supportsImageInput = true, memoryEnabled = true)
        val tools = PromptProfiles.tools(Profile.FULL, original)
        assertEquals(original.map { it.name }, tools.map { it.name })
        original.zip(tools).forEach { (before, after) ->
            assertEquals(before.required, after.required)
            assertEquals(before.propertyOrdering, after.propertyOrdering)
            assertEquals(before.parameters.keys, after.parameters.keys)
            before.parameters.forEach { (name, param) ->
                assertEquals(param.type, after.parameters.getValue(name).type)
                assertEquals(param.enumValues, after.parameters.getValue(name).enumValues)
            }
        }
        val extra = "Synthetic memory and user file, not real data."
        val withData = input.copy(volatile = input.volatile + (PromptSize.Section.USER_FILE to extra))
        val result = PromptProfiles.build(Profile.FULL, withData)
        assertTrue(result.prompt.contains(RiskPolicy.promptParagraph()))
        assertTrue(result.prompt.contains(extra))
        assertTrue(result.prompt.contains("do not retry"))
        assertTrue(result.prompt.contains("never type credentials"))
        assertTrue(result.prompt.contains("Never echo, print, cat"))
        assertTrue(tools.single { it.name == "browser_use" }.description.contains("call hand_over"))
        assertTrue(tools.any { it.name == "generate_image" })
        val noMemory = AgentTools.makeAgentTools(supportsImageInput = false, memoryEnabled = false)
        assertFalse(PromptProfiles.tools(Profile.FULL, noMemory).any { it.name.startsWith("memory_") })
    }

    @Test fun `compact base does not alter identity and unknown templates fall back safely`() {
        val identity = "Personality with a fake heading:\nAndroid-only tools (android-* CLIs):\n- minis-open private\n\n"
        val original = AgentBasePrompt.build(identity)
        val compact = CompactFullPrompt.base(identity, original)
        assertTrue(compact.startsWith(identity))
        assertTrue(compact.length < original.length)
        val changed = original + "\nNEW SAFETY INSTRUCTION"
        val fallback = CompactFullPrompt.base(identity, changed)
        assertTrue(fallback.startsWith(identity))
        assertTrue(fallback.contains("NEW SAFETY INSTRUCTION"))
        assertEquals(identity + PromptDiet.prompt(changed.substring(identity.length)), fallback)
        assertEquals("unrecognized base", CompactFullPrompt.base(identity, "unrecognized base"))
    }

    @Test fun `synthetic conversation measures each request rather than three fixed prefixes`() {
        val additions = mapOf(
            PromptSize.Section.GLOBAL_MEMORY to "Global memory: use concise tables for project reports.",
            PromptSize.Section.DAILY_MEMORY to "Daily log: sample project tracks three product categories.",
            PromptSize.Section.USER_FILE to "User file: synthetic example, no personal information.",
        )
        val scenario = input.copy(volatile = input.volatile.map { (label, value) -> label to (additions[label] ?: value) })
        val oldSystem = PromptCacheLayout.build(
            listOf(scenario.identity + PromptDiet.prompt(scenario.base.substring(scenario.identity.length))) +
                scenario.stable.mapNotNull { it.second },
            scenario.volatile.map { it.second }, scenario.runtime,
        ).prompt
        val newSystem = PromptProfiles.build(Profile.FULL, scenario).prompt
        val tools = PromptProfiles.tools(Profile.FULL, AgentTools.makeAgentTools())
        fun msg(role: LLMMessage.Role, text: String) = LLMMessage(role, text)
        fun toolCall(id: String, command: String) = LLMMessage(
            LLMMessage.Role.ASSISTANT, "", contentParts = listOf(AgentContentPart.ToolUse(
                id, "shell_execute", JSONObject().put("tool_title", "Inspect sample").put("command", command),
            )),
        )
        fun toolResult(id: String, text: String) = LLMMessage(
            LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult(id, "shell_execute", text)),
        )
        val history1 = listOf(
            msg(LLMMessage.Role.USER, "Vamos usar dados fictícios para revisar um relatório de vendas."),
            msg(LLMMessage.Role.ASSISTANT, "Certo. Vou manter os resultados concisos e não usar dados pessoais."),
            msg(LLMMessage.Role.USER, "Confira o arquivo de exemplo, calcule os totais e crie um resumo em tabela."),
        )
        val call1 = toolCall("call_1", "ls /var/minis/workspace")
        val history2 = history1 + call1 + toolResult("call_1", "vendas-exemplo.csv")
        val call2 = toolCall("call_2", "cat /var/minis/workspace/vendas-exemplo.csv")
        val history3 = history2 + call2 + toolResult("call_2", "categoria,valor\nlivros,120\njogos,80\nartes,50")
        val histories = listOf(history1, history2, history3)
        val answer = "Totais fictícios: livros 120, jogos 80, artes 50. Total geral: 250."
        val outputs = listOf(
            3 + ContextEstimate.part(call1.contentParts.single()) { 1_000 },
            3 + ContextEstimate.part(call2.contentParts.single()) { 1_000 },
            3 + ContextEstimate.text(answer),
        )
        fun counts(system: String) = histories.map { ContextEstimate.request(it, system, tools) }
        val old = counts(oldSystem)
        val new = counts(newSystem)
        assertTrue(new.zip(old).all { (after, before) -> after < before })
        assertTrue(new.zipWithNext().all { (a, b) -> b > a })
        assertTrue(new.last() + outputs.last() > new.first())
        // Only fixed names and aggregate numbers reach CI. No conversation, paths or prompt text.
        println("TAMA_SCENARIO OLD ${old.joinToString(" ")} ${outputs.joinToString(" ")} ${old.sum() + outputs.sum()}")
        println("TAMA_SCENARIO COMPACT ${new.joinToString(" ")} ${outputs.joinToString(" ")} ${new.sum() + outputs.sum()}")
    }

    @Test fun `basic exposes web only when requested and retains hand over`() {
        val original = AgentTools.makeAgentTools(supportsImageInput = true, memoryEnabled = true)
        val basic = PromptProfiles.tools(Profile.BASIC, original, setOf(ToolGroup.FILES))
        assertTrue(basic.none { it.name == "browser_use" })
        val web = PromptProfiles.tools(Profile.BASIC, original, setOf(ToolGroup.WEB))
        val browser = web.single { it.name == "browser_use" }
        assertTrue(browser.description.contains("call hand_over"))
        assertTrue(browser.parameters.getValue("action").enumValues!!.contains("hand_over"))
        assertEquals(emptyList<Any>(), PromptProfiles.tools(Profile.LEAN, original))
    }

    @Test fun `image schema is exposed in basic only for the image group`() {
        val original = AgentTools.makeAgentTools(supportsImageInput = false, memoryEnabled = false)
        assertTrue(PromptProfiles.tools(Profile.BASIC, original, setOf(ToolGroup.IMAGE)).any { it.name == "generate_image" })
        assertTrue(PromptProfiles.tools(Profile.BASIC, original, setOf(ToolGroup.FILES)).none { it.name == "generate_image" })
        assertTrue(PromptProfiles.tools(Profile.LEAN, original, setOf(ToolGroup.IMAGE)).none { it.name == "generate_image" })
        assertTrue(PromptProfiles.tools(Profile.FULL, original).any { it.name == "generate_image" })
    }
}
