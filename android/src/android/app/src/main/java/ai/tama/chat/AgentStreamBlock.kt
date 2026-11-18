package ai.tama.chat

/** Runs a stream block in the caller's coroutine without adding a job or dispatcher. */
internal object AgentStreamBlock {
    suspend fun run(block: suspend () -> Boolean): Boolean = block()
}
