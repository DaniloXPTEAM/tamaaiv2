package ai.tama.net

import ai.tama.router.AutoModelFailure
import ai.tama.router.ModelHealth
import com.openminis.app.data.model.LLMError
import io.github.nanomuse.net.ProviderReach
import org.junit.Assert.*
import org.junit.Test

class OpenRouterSpendLimitTest {
    private fun reach(message: String, host: String = "openrouter.ai", status: Int = 403) =
        ProviderReach.Reach(ProviderReach.Kind.ACCESS_DENIED, host, httpStatus = status, originalMessage = message)

    @Test fun onlyExplicitZeroDollarKeyLimitOnOpenRouterIsRecognized() {
        assertTrue(OpenRouterSpendLimit.keyLimitIsZero(reach("Key limit exceeded: $0.00 of $0.00")))
        assertTrue(OpenRouterSpendLimit.keyLimitIsZero(reach("KEY LIMIT EXCEEDED $0 of $0", "api.openrouter.ai")))
        assertFalse(OpenRouterSpendLimit.keyLimitIsZero(reach("Access denied")))
        assertFalse(OpenRouterSpendLimit.keyLimitIsZero(reach("Key limit exceeded $0.01 of $0.00")))
        assertFalse(OpenRouterSpendLimit.keyLimitIsZero(reach("Key limit exceeded $0.00 of $0.01")))
        assertFalse(OpenRouterSpendLimit.keyLimitIsZero(reach("Key limit exceeded $0.00 of $0.00", "openrouter.ai.example.test")))
        assertFalse(OpenRouterSpendLimit.keyLimitIsZero(reach("Key limit exceeded $0.00 of $0.00", status = 400)))
        assertFalse(OpenRouterSpendLimit.keyLimitIsZero(null))
    }

    @Test fun isolatedFallbackRecognizesTheExplicitLimitWithoutChangingNormalBehavior() {
        val error = LLMError.ProviderError("refused").apply {
            nmProviderReach = reach("Key limit exceeded: $0.00 of $0.00")
        }
        val isolated = AutoModelFailure.classify(error, false, switchOnAmbiguousAccessDenied = false)
            as AutoModelFailure.Decision.Cooldown
        assertEquals(ModelHealth.Failure.KEY_SPEND_LIMIT, isolated.reason)
        val normal = AutoModelFailure.classify(error, false) as AutoModelFailure.Decision.Cooldown
        assertEquals(ModelHealth.Failure.AUTH, normal.reason)
    }
}
