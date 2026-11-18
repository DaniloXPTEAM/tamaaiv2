package ai.tama.net

import com.openminis.app.BuildConfig
import com.openminis.app.debug.LLMRequestLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Only synthetic data; the parallel variant must not retain requests even in a debug build. */
class ParallelRequestLogTest {
    @Test fun `parallel debug request and response bodies are not retained`() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test")
        LLMRequestLog.clear()
        LLMRequestLog.add(LLMRequestLog.Entry(
            provider = "fake", requestURL = "https://fixture.invalid/chat",
            requestHeaders = mapOf("X-Custom" to "synthetic credential"),
            requestBody = "synthetic private prompt", responseBody = "synthetic private answer",
        ))
        assertTrue(LLMRequestLog.getAll().isEmpty())
        assertTrue(LLMRequestLog.getLast(1).isEmpty())
        assertEquals(0, LLMRequestLog.toJSON().getInt("count"))
        assertEquals(0, LLMRequestLog.toJSON().getJSONArray("requests").length())
    }
}
