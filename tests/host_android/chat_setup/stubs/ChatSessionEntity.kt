package com.openminis.app.data.db

// No Room behavior: fields here are just enough for the extracted createSession body.
data class ChatSessionEntity(
    val id: String = "",
    val title: String? = null,
    val modelId: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val memoryEnabled: Int = 1,
)
