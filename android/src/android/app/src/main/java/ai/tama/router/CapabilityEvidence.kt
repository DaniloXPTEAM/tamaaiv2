package ai.tama.router

/** Conservative evidence from an explicit provider refusal; generic 400s prove nothing. */
object CapabilityEvidence {
    private val modelsDoNotSupport = Regex(
        """\b(?:this\s+)?model\b.{0,80}\b(?:does not|doesn't|cannot|can't|does not currently)\s+support\s+(?:using\s+)?(?:tools|tool calls|function calling|function calls)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val unsupportedForModel = Regex(
        """\b(?:tools|tool calls|function calling|function calls)\s+(?:are|is)\s+(?:not supported|unsupported)\s+(?:by|for)\s+(?:this\s+|the\s+|your\s+)?model\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Do not mistake a tool_choice/parallel-calls parameter error for lack of all tools. */
    fun explicitlyUnsupportedTools(status: Int?, providerMessage: String?): Boolean {
        if (status !in setOf(400, 422) || providerMessage.isNullOrBlank()) return false
        val message = providerMessage.take(4096)
        return modelsDoNotSupport.containsMatchIn(message) || unsupportedForModel.containsMatchIn(message)
    }

    fun effective(declared: Set<Need>, denied: Set<Need>): Set<Need> = declared - denied
}
