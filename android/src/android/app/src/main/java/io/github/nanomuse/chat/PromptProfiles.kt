package io.github.nanomuse.chat

import com.openminis.app.data.model.AgentToolDefinition
import io.github.nanomuse.router.Profile
import io.github.nanomuse.router.ToolGroup

/**
 * Builds the same system prompt and tool schemas for measurement and for an outbound request.
 * Input fragments can contain private data; only [PromptSize.Measure] counts leave this class.
 * FULL preserves the existing cache layout and PromptDiet exactly.
 */
object PromptProfiles {
    data class Input(
        val identity: String,
        val base: String,
        val stable: List<Pair<PromptSize.Section, String?>>,
        val volatile: List<Pair<PromptSize.Section, String?>>,
        val runtime: String?,
    )

    data class Result(
        val prompt: String,
        val sizes: List<PromptSize.Measure<PromptSize.Section>>,
    )

    private const val LEAN_TONE = "Tone and style: Reply in the user's language and be concise. " +
        "No tools are available in this request. Do not claim to have performed actions or seen data " +
        "you have not received. Refuse unlawful or harmful requests."

    private const val BASIC_GUIDANCE = "Tool call style: Use the available tools for requested actions; " +
        "do not claim an action succeeded before its result. Use file_write to create files, " +
        "file_read then file_edit to change them, and shell_execute to run commands. " +
        "The sandbox uses /var/minis/ for session files. Use the returned minis:// link for files. " +
        "Use --help for command details. Never print secret values."

    private val BASIC_TOOLS = setOf(
        "shell_execute", "file_read", "file_write", "file_edit", "memory_write", "memory_get",
    )

    fun tools(
        profile: Profile,
        originals: List<AgentToolDefinition>,
        hints: Set<ToolGroup> = emptySet(),
    ): List<AgentToolDefinition> {
        val chosen = when (profile) {
            Profile.LEAN -> emptyList()
            Profile.FULL -> originals
            Profile.BASIC -> originals.filter { tool ->
                tool.name in BASIC_TOOLS ||
                    (tool.name == "browser_use" && (ToolGroup.WEB in hints || ToolGroup.WEB_ADVANCED in hints)) ||
                    (tool.name == "read_image" && ToolGroup.IMAGE in hints)
            }
        }
        return PromptDiet.tools(chosen)
    }

    fun build(profile: Profile, input: Input, hints: Set<ToolGroup> = emptySet()): Result {
        val dietedBase = PromptDiet.prompt(input.base)
        if (profile == Profile.FULL) {
            val prompt = PromptCacheLayout.build(
                stableSections = listOf(dietedBase) + input.stable.mapNotNull { it.second },
                volatileSections = input.volatile.map { it.second },
                runtimeContext = input.runtime,
            ).prompt
            return Result(prompt, PromptSize.system(
                beforeBase = input.base,
                afterBase = dietedBase,
                identityLength = input.identity.length,
                stable = input.stable,
                volatile = input.volatile,
                runtime = input.runtime,
            ))
        }

        val stable = mutableListOf<Pair<PromptSize.Section, String?>>(
            PromptSize.Section.IDENTITY to input.identity,
            PromptSize.Section.TONE to if (profile == Profile.LEAN) LEAN_TONE else
                "Tone and style: Reply in the user's language and be concise.",
        )
        val volatile = mutableListOf<Pair<PromptSize.Section, String?>>()
        if (profile == Profile.LEAN) {
            // The first-conversation protocol needs its marker even without agent tools.
            volatile += input.volatile.filter { it.first == PromptSize.Section.NAMING }
        } else {
            stable += PromptSize.Section.TOOL_STYLE to BASIC_GUIDANCE
            stable += input.stable.filter { it.first == PromptSize.Section.RISK_POLICY }
            if (ToolGroup.SCREEN in hints || ToolGroup.ANDROID in hints) {
                stable += input.stable.filter { it.first == PromptSize.Section.HANDS }
            }
            androidCommands(dietedBase, hints)?.let { stable += PromptSize.Section.ANDROID_TOOLS to it }
            val excluded = setOf(
                PromptSize.Section.MEMORY_TOOLS, PromptSize.Section.MEDIA, PromptSize.Section.COMPUTERS,
            )
            for ((label, value) in input.volatile) {
                val selected = when (label) {
                    PromptSize.Section.SKILLS -> compactSkills(value)
                    PromptSize.Section.MCP -> compactMcp(value)
                    PromptSize.Section.MEDIA -> value.takeIf { ToolGroup.MEDIA in hints || ToolGroup.IMAGE in hints }
                    PromptSize.Section.COMPUTERS -> value.takeIf { ToolGroup.SCREEN in hints }
                    in excluded -> null
                    else -> value
                }
                volatile += label to selected
            }
        }
        val prompt = PromptCacheLayout.build(
            stableSections = stable.mapNotNull { it.second },
            volatileSections = volatile.map { it.second },
            runtimeContext = input.runtime,
        ).prompt
        return Result(prompt, PromptSize.fragments(stable, volatile, input.runtime))
    }

    private fun androidCommands(base: String, hints: Set<ToolGroup>): String? {
        if (hints.none { it in setOf(ToolGroup.ANDROID, ToolGroup.SCREEN, ToolGroup.SCHEDULE, ToolGroup.CONFIG, ToolGroup.MEDIA, ToolGroup.IMAGE) }) return null
        val section = base.substringAfter("\nAndroid-only tools (android-* CLIs):\n", "")
            .substringBefore("\n\nEnvironment variables:")
        val names = Regex("(?m)^- (android-[a-z0-9-]+)").findAll(section)
            .map { it.groupValues[1] }.distinct().toList()
        val extras = buildList {
            if (ToolGroup.CONFIG in hints) add("minis-config")
            if (ToolGroup.SCHEDULE in hints) add("minis-scheduled")
            if (ToolGroup.MEDIA in hints || ToolGroup.IMAGE in hints) add("minis-model-use")
        }
        return "Android commands: " + (names + extras).joinToString(", ") + ". " +
            "Run a command with --help for details; permissions may be required. " +
            "Never use android-a11y-cli to bypass the Hands setting or approval."
    }

    /** Keep complete XML blocks, not the first ten arbitrary lines or half a tag. */
    private fun compactSkills(value: String?): String? {
        if (value.isNullOrBlank()) return value
        val blocks = Regex("<skill>.*?</skill>", RegexOption.DOT_MATCHES_ALL)
            .findAll(value).take(10).map { it.value }.toList()
        if (blocks.isEmpty()) return null
        return "Skills: read the corresponding SKILL.md before using a skill.\n<available_skills>\n" +
            blocks.joinToString("\n") + "\n</available_skills>\n" +
            "List /var/minis/skills/ to discover other skills."
    }

    private fun compactMcp(value: String?): String? {
        if (value.isNullOrBlank()) return value
        val entries = value.lineSequence().filter { it.startsWith("- ") }.take(10).toList()
        if (entries.isEmpty()) return null
        return "Available MCP servers (use minis-mcp-cli tools <server> and call):\n" +
            entries.joinToString("\n") + "\nUse environment placeholders for secrets, never hardcode them."
    }
}
