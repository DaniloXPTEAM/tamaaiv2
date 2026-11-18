package ai.tama.net

import org.junit.Assert.*
import org.junit.Test

class NativeOffloadPrivacyTest {
    @Test fun `parallel socket diagnostics omit caller controlled data and exception detail`() {
        val secret = "fixture-private-prompt-and-key"
        val request = NativeOffloadPrivacy.incoming(true, 4, secret, 2, listOf("video", secret), "/$secret", 3)
        val missing = NativeOffloadPrivacy.missingLog(true, secret, setOf(secret))
        val missingOutput = NativeOffloadPrivacy.missingOutput(true, secret)
        val failure = NativeOffloadPrivacy.failureLog(true, secret, secret)
        val output = NativeOffloadPrivacy.failureOutput(true, secret)
        val reply = NativeOffloadPrivacy.reply(true, secret, 1, 12, "/$secret", 1)
        for (value in listOf(request, missing, missingOutput, failure, output, reply)) {
            assertFalse(value.contains(secret))
        }
        assertEquals("native_offload: operation failed\n", output)
    }

    @Test fun `normal socket diagnostics retain the existing details`() {
        assertEquals("recv pid=4 name='tool' argc=1 argv=[hello] cwd=/tmp envc=2",
            NativeOffloadPrivacy.incoming(false, 4, "tool", 1, listOf("hello"), "/tmp", 2))
        assertEquals("no handler registered for 'tool' (known=[other])",
            NativeOffloadPrivacy.missingLog(false, "tool", setOf("other")))
        assertEquals("native_offload: no handler for 'tool'\n", NativeOffloadPrivacy.missingOutput(false, "tool"))
        assertEquals("handler 'tool' threw: detail", NativeOffloadPrivacy.failureLog(false, "tool", "detail"))
        assertEquals("native_offload: detail\n", NativeOffloadPrivacy.failureOutput(false, "detail"))
        assertEquals("reply name='tool' exit=1 outBytes=12 tmpGuest=/tmp/reply elapsed=9ms",
            NativeOffloadPrivacy.reply(false, "tool", 1, 12, "/tmp/reply", 9))
    }
}
