package ai.tama.net

/** Provider diagnostics can contain custom URLs, response headers and untrusted stream fields. */
internal object ProviderLogPrivacy {
    // These upstream categories include raw provider responses, user titles, tool
    // arguments, custom model/endpoint fields or exception messages. This is a
    // targeted mitigation, not a guarantee about every other logger or crash report.
    private val unsafeCategories = setOf(
        "OpenAIProvider", "OkHttpNetTrace", "ToolPreflight", "Thinking",
        "ChatViewModel", "ChatVMStream", "TitleGen", "VideoGen", "QuickTest", "OpenAIModelsApi", "CodexOAuth",
    )

    fun allow(category: String, parallelTest: Boolean): Boolean =
        !parallelTest || category !in unsafeCategories

    /** Do not expose an untrusted exception or response in a direct log message. */
    fun failureMessage(parallelTest: Boolean, safe: String, detail: String?): String =
        if (parallelTest) safe else "$safe: $detail"

    /** An error returned to another model can echo a prompt, URL or credential too. */
    fun failureDetail(parallelTest: Boolean, detail: String?, fallback: String): String =
        if (parallelTest) fallback else detail ?: fallback
}
