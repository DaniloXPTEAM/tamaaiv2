package ai.tama.net

import org.junit.Assert.*
import org.junit.Test

class GeminiStreamTerminationTest {
    @Test fun normalBuildKeepsLegacyDefaultButParallelNeverInventsSuccess() {
        assertEquals("end_turn", GeminiStreamTermination.reason(null, parallelTest = false))
        assertNull(GeminiStreamTermination.reason(null, parallelTest = true))
        for (reason in listOf("end_turn", "max_tokens", "safety")) {
            assertEquals(reason, GeminiStreamTermination.reason(reason, parallelTest = false))
            assertEquals(reason, GeminiStreamTermination.reason(reason, parallelTest = true))
        }
    }
}
