package io.github.nanomuse.chat

import com.openminis.app.data.model.AgentToolDefinition

/**
 * Count-only diagnostics for the system prompt and OpenAI-shaped tool schemas.
 * The report never retains text, user-defined names, credentials or conversation history.
 * A Kotlin String's length counts UTF-16 code units, not bytes or model tokens.
 */
object PromptSize {
    enum class Section {
        IDENTITY, INTRODUCTION, AVAILABLE_TOOLS, SANDBOX, URLS, FILES, TOOL_STYLE,
        TONE, ANDROID_TOOLS, ENVIRONMENT, SCHEDULED, BASE_UNCLASSIFIED,
        RISK_POLICY, HANDS, SKILLS, MCP, NAMING, MEDIA, COMPUTERS, SESSION,
        GOALS, FEED, MEMORY_TOOLS, MEMORY_GUIDANCE, GLOBAL_MEMORY, DAILY_MEMORY,
        USER_FILE, RUNTIME, SEPARATORS, TOTAL,
    }

    enum class Tool {
        SHELL_EXECUTE, FILE_READ, FILE_WRITE, FILE_EDIT, READ_IMAGE, BROWSER_USE,
        MEMORY_WRITE, MEMORY_GET, OTHER, ARRAY_SEPARATORS, TOTAL,
    }

    /** Only fixed enum labels and lengths survive the measurement. */
    data class Measure<T>(val label: T, val beforeChars: Int, val afterChars: Int) {
        val beforeTokens: Int get() = estimateTokens(beforeChars)
        val afterTokens: Int get() = estimateTokens(afterChars)
    }

    /** 1 token per 3.7 UTF-16 units, rounded up. It is not a tokenizer or a bill. */
    fun estimateTokens(chars: Int): Int {
        require(chars >= 0)
        return ((chars.toLong() * 10L + 36L) / 37L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private val headings = listOf(
        Section.AVAILABLE_TOOLS to "\n\nAvailable tools:\n",
        Section.SANDBOX to "\n\nSandbox directory /var/minis/ (writeability is stated per path):\n",
        Section.URLS to "\n\nThe minis:// URL scheme:\n",
        Section.FILES to "\n\nFile creation guidelines:\n",
        Section.TOOL_STYLE to "\n\nTool call style:\n",
        Section.TONE to "\n\nTone and style:\n",
        Section.ANDROID_TOOLS to "\n\nAndroid-only tools (android-* CLIs):\n",
        Section.ENVIRONMENT to "\n\nEnvironment variables:\n",
        Section.SCHEDULED to "\n\nScheduled tasks:",
    )

    /**
     * Splits at fixed application-owned headings, never at a heading from the user's identity.
     * A changed template falls back to one base section rather than inventing a breakdown.
     */
    private fun baseSections(base: String, identityLength: Int): Map<Section, Int> {
        val trimmed = base.trim()
        val leftTrim = base.length - base.trimStart().length
        val identityEnd = (identityLength - leftTrim).coerceIn(0, trimmed.length)
        val starts = mutableListOf<Int>()
        var cursor = identityEnd
        for ((_, marker) in headings) {
            val index = trimmed.indexOf(marker, cursor)
            if (index < 0) return mapOf(Section.BASE_UNCLASSIFIED to trimmed.length)
            starts.add(index)
            cursor = index + marker.length
        }
        val result = linkedMapOf(
            Section.IDENTITY to identityEnd,
            Section.INTRODUCTION to starts.first() - identityEnd,
        )
        headings.forEachIndexed { index, (label, _) ->
            val end = starts.getOrElse(index + 1) { trimmed.length }
            result[label] = end - starts[index]
        }
        return result
    }

    /**
     * [stable] and [volatile] are in exactly the order passed to [PromptCacheLayout].
     * Only the lengths of their trimmed content are returned. Separators are counted separately
     * so TOTAL equals the assembled prompt even for empty sections.
     */
    fun system(
        beforeBase: String,
        afterBase: String,
        identityLength: Int,
        stable: List<Pair<Section, String?>>,
        volatile: List<Pair<Section, String?>>,
        runtime: String?,
    ): List<Measure<Section>> {
        val beforeBaseSections = baseSections(beforeBase, identityLength)
        val afterBaseSections = baseSections(afterBase, identityLength)
        val labels = (beforeBaseSections.keys + afterBaseSections.keys).distinct()
        val result = labels.map { label ->
            Measure(label, beforeBaseSections[label] ?: 0, afterBaseSections[label] ?: 0)
        }.toMutableList()
        for ((label, value) in stable + volatile + (Section.RUNTIME to runtime)) {
            val chars = value?.trim()?.length ?: 0
            result += Measure(label, chars, chars)
        }
        fun total(base: String): Int = PromptCacheLayout.build(
            stableSections = listOf(base) + stable.mapNotNull { it.second },
            volatileSections = volatile.map { it.second },
            runtimeContext = runtime,
        ).prompt.length
        val beforeTotal = total(beforeBase)
        val afterTotal = total(afterBase)
        val beforeContent = result.sumOf { it.beforeChars }
        val afterContent = result.sumOf { it.afterChars }
        result += Measure(Section.SEPARATORS, beforeTotal - beforeContent, afterTotal - afterContent)
        result += Measure(Section.TOTAL, beforeTotal, afterTotal)
        return result
    }

    /** Measure a profiled layout by its fixed labels; never retain the section contents. */
    fun fragments(
        stable: List<Pair<Section, String?>>,
        volatile: List<Pair<Section, String?>>,
        runtime: String?,
    ): List<Measure<Section>> {
        val result = (stable + volatile + (Section.RUNTIME to runtime)).map { (label, value) ->
            val chars = value?.trim()?.length ?: 0
            Measure(label, chars, chars)
        }.toMutableList()
        val total = PromptCacheLayout.build(
            stableSections = stable.mapNotNull { it.second },
            volatileSections = volatile.map { it.second },
            runtimeContext = runtime,
        ).prompt.length
        val separators = total - result.sumOf { it.afterChars }
        result += Measure(Section.SEPARATORS, separators, separators)
        result += Measure(Section.TOTAL, total, total)
        return result
    }

    private fun toolLabel(name: String): Tool = when (name) {
        "shell_execute" -> Tool.SHELL_EXECUTE
        "file_read" -> Tool.FILE_READ
        "file_write" -> Tool.FILE_WRITE
        "file_edit" -> Tool.FILE_EDIT
        "read_image" -> Tool.READ_IMAGE
        "browser_use" -> Tool.BROWSER_USE
        "memory_write" -> Tool.MEMORY_WRITE
        "memory_get" -> Tool.MEMORY_GET
        else -> Tool.OTHER
    }

    /** Counts the serialized OpenAI schemas and the array's brackets and commas, not request JSON. */
    fun tools(before: List<AgentToolDefinition>, after: List<AgentToolDefinition>): List<Measure<Tool>> {
        fun sizes(tools: List<AgentToolDefinition>): Map<Tool, Int> {
            val result = linkedMapOf<Tool, Int>()
            for (tool in tools) {
                val label = toolLabel(tool.name)
                result[label] = (result[label] ?: 0) + tool.toOpenAIJson().toString().length
            }
            return result
        }
        val old = sizes(before)
        val new = sizes(after)
        val result = (old.keys + new.keys).distinct().map { label ->
            Measure(label, old[label] ?: 0, new[label] ?: 0)
        }.toMutableList()
        fun separators(tools: List<AgentToolDefinition>): Int =
            if (tools.isEmpty()) 0 else 2 + tools.size - 1
        result += Measure(Tool.ARRAY_SEPARATORS, separators(before), separators(after))
        result += Measure(
            Tool.TOTAL,
            old.values.sum() + separators(before),
            new.values.sum() + separators(after),
        )
        return result
    }

    /** Fixed labels and integers only: safe to log even when a tool has an arbitrary name. */
    fun <T : Enum<T>> logLines(scope: String, measures: List<Measure<T>>): List<String> {
        require(scope == "system" || scope == "tool")
        return measures.map { m ->
            "v1 $scope ${m.label.name} ${m.beforeChars} ${m.afterChars} ${m.beforeTokens} ${m.afterTokens}"
        }
    }
}
