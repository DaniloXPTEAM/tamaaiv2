package io.github.nanomuse.chat

import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulStore
import com.openminis.app.agent.SystemPromptBuilder
import com.openminis.app.tools.AgentTools
import io.github.nanomuse.guard.RiskPolicy
import io.github.nanomuse.hands.HandsCapabilityPrompt
import io.github.nanomuse.media.MediaPrompt
import io.github.nanomuse.reach.ComputersPrompt
import io.github.nanomuse.router.Profile
import io.github.nanomuse.router.ToolGroup
import org.json.JSONArray
import org.junit.Assert.assertEquals
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
                val oldLayout = PromptCacheLayout.build(
                    listOf(PromptDiet.prompt(input.base)) + input.stable.mapNotNull { it.second },
                    input.volatile.map { it.second }, input.runtime,
                )
                assertEquals(oldLayout.prompt, system.prompt)
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
                "BASIC_ANDROID" to 3_700, "FULL" to 11_500,
            ).getValue(caseName)
            val estimate = PromptSize.estimateTokens(systemTotal.afterChars + toolTotal.afterChars)
            assertTrue("$caseName synthetic fixed prompt + tools: $estimate exceeds $ceiling", estimate <= ceiling)
        }
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
}
