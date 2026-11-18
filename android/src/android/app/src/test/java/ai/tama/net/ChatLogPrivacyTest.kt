package ai.tama.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatLogPrivacyTest {
    @Test fun `parallel log does not evaluate title prompt or provider detail`() {
        var evaluated = false
        val message = ChatLogPrivacy.message(true, "title request failed") {
            evaluated = true
            "fixture-private-title-prompt-credential"
        }
        assertEquals("title request failed", message)
        assertFalse(evaluated)
    }

    @Test fun `normal log keeps its existing detail`() {
        assertEquals("detailed message", ChatLogPrivacy.message(false, "safe") { "detailed message" })
    }
}
