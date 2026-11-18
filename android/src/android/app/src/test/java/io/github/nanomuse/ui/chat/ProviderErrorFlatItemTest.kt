package io.github.nanomuse.ui.chat

import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.FlatChatItem
import com.openminis.app.ui.chat.buildFlatChatItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The retry identity must not depend on parsing a defensive lazy-list key suffix. */
class ProviderErrorFlatItemTest {
    @Test fun `deduped error retains the original id even when it contains a hash`() {
        val originalId = "message#real"
        val rows = buildFlatChatItems(
            messages = listOf(ChatMessage(id = originalId, role = "assistant", content = "", error = "error")),
            seedKeys = setOf("error:$originalId"),
        )
        val row = rows.filterIsInstance<FlatChatItem.AssistantError>().single()
        assertEquals("message#real#2", row.messageId)
        assertEquals(originalId, row.sourceMessageId)
        assertEquals("error:message#real#2", row.key)
    }

    @Test fun `colliding literal suffixes do not replace source identities`() {
        val rows = buildFlatChatItems(
            messages = listOf(
                ChatMessage(id = "m", role = "assistant", content = "", error = "first"),
                ChatMessage(id = "m", role = "assistant", content = "", error = "second"),
                ChatMessage(id = "m#2", role = "assistant", content = "", error = "third"),
            ),
        ).filterIsInstance<FlatChatItem.AssistantError>()
        assertEquals(listOf("m", "m", "m#2"), rows.map { it.sourceMessageId })
        assertEquals(listOf("error:m", "error:m#2", "error:m#2#2"), rows.map { it.key })
        assertTrue(rows.map { it.key }.toSet().size == rows.size)
    }
}
