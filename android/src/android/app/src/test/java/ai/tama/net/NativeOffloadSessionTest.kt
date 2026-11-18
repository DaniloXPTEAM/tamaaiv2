package ai.tama.net

import org.junit.Assert.*
import org.junit.Test

class NativeOffloadSessionTest {
    @Test fun `parallel accepts ordinary or absent ids but rejects path traversal and control characters`() {
        for (value in listOf(null, "session-123", "__new__123", "café")) {
            assertTrue("expected safe component: $value", NativeOffloadSession.valid(true, value))
        }
        for (value in listOf("", ".", "..", "../other", "a/b", "a\\b", "x\u0000y", "x\ny", "é".repeat(128))) {
            assertFalse("expected unsafe component: $value", NativeOffloadSession.valid(true, value))
        }
    }

    @Test fun `normal package preserves its previous handling`() {
        for (value in listOf(null, "", "..", "a/b", "x\u0000y")) {
            assertTrue(NativeOffloadSession.valid(false, value))
        }
    }

    @Test fun `a syntactically valid value is not an authenticated session`() {
        assertTrue(NativeOffloadSession.valid(true, "another-valid-session"))
    }
}
