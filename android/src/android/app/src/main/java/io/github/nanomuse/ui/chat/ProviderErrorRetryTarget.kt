package io.github.nanomuse.ui.chat

/** A detail sheet may outlive its row. Never retry a different or already resolved turn. */
internal data class ProviderErrorRetryTarget(
    val sessionId: String,
    val messageId: String,
    val error: String,
    // The same id and text can fail again after a retry. Keep the immutable message object
    // seen when the card was built so an old sheet cannot act on that later failure (ABA).
    val failureSnapshot: Any?,
) {
    fun matches(
        currentSession: String,
        lastConversationRole: String?,
        lastMessageId: String?,
        lastError: String?,
        lastMessageSnapshot: Any?,
        isStreaming: Boolean,
        matchingIdCount: Int,
    ): Boolean =
        !isStreaming && failureSnapshot != null && lastMessageSnapshot === failureSnapshot &&
            sessionId == currentSession && lastConversationRole == "assistant" &&
            messageId == lastMessageId && error == lastError && matchingIdCount == 1
}
