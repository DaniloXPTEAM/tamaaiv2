package ai.tama.net

/** Local socket metadata and handler exceptions can contain prompts, paths or secrets. */
internal object NativeOffloadPrivacy {
    fun incoming(parallel: Boolean, pid: Int, name: String, argc: Int, argv: List<String>, cwd: String, envc: Int): String =
        if (parallel) "offload request received" else
            "recv pid=$pid name='$name' argc=$argc argv=$argv cwd=$cwd envc=$envc"

    fun missingLog(parallel: Boolean, name: String, known: Set<String>): String =
        if (parallel) "offload handler not registered" else "no handler registered for '$name' (known=$known)"

    fun missingOutput(parallel: Boolean, name: String): String =
        if (parallel) "native_offload: no handler registered\n" else "native_offload: no handler for '$name'\n"

    fun failureLog(parallel: Boolean, name: String, detail: String?): String =
        if (parallel) "offload handler failed" else "handler '$name' threw: $detail"

    fun failureOutput(parallel: Boolean, detail: String?): String =
        if (parallel) "native_offload: operation failed\n" else "native_offload: $detail\n"

    fun reply(parallel: Boolean, name: String, exit: Int, size: Int, path: String, elapsedMs: Long): String =
        if (parallel) "offload request completed" else
            "reply name='$name' exit=$exit outBytes=$size tmpGuest=$path elapsed=${elapsedMs}ms"
}
