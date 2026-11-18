package ai.tama.net

/** Targeted shell diagnostics: commands, output and exception text can all contain private data. */
internal object SandboxLogPrivacy {
    fun dispatch(parallel: Boolean, session: String, command: String): String =
        if (parallel) "shell command dispatched" else
            "[diag] execute sessionId=$session cmd=${command.take(120).replace('\n', ' ')}"

    fun start(parallel: Boolean, command: String): String =
        if (parallel) "Executing shell command" else "Executing: $command"

    fun timeout(parallel: Boolean, timeoutMs: Long, command: String): String =
        if (parallel) "Command timed out after ${timeoutMs}ms" else
            "Command timed out after ${timeoutMs}ms: $command"

    fun failure(parallel: Boolean, command: String): String =
        if (parallel) "Shell command failed" else "Command failed: $command"

    fun failureOutput(parallel: Boolean, detail: String?): String =
        if (parallel) "[Error: shell operation failed]" else "[Error: $detail]"

    fun writeFailure(parallel: Boolean, detail: String?): String =
        if (parallel) "[Write error: shell operation failed]" else "[Write error: $detail]"

    fun spawnFailure(parallel: Boolean, kind: String, detail: String?): String =
        if (parallel) "shell spawn failed" else "spawn threw: $kind: $detail"

    fun readerFailure(parallel: Boolean, detail: String?): String =
        if (parallel) "Reader loop ended" else "Reader loop ended: $detail"

    /** Captured process output is safe for the command's caller, not for later logs or errors. */
    fun captured(parallel: Boolean, value: String): String = if (parallel) "" else value
}
