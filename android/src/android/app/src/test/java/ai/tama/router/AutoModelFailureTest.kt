package ai.tama.router

import com.openminis.app.data.model.LLMError
import io.github.nanomuse.net.ProviderReach
import org.junit.Assert.*
import org.junit.Test

class AutoModelFailureTest {
    private fun error(kind: ProviderReach.Kind, status: Int, message: String = "", retry: Int? = null) =
        LLMError.ProviderError(message).apply {
            nmProviderReach = ProviderReach.Reach(kind, "example.test", httpStatus = status,
                originalMessage = message, retryAfterS = retry, retryAfterPresent = retry != null)
        }

    @Test fun onlyRecoverableErrorsMaySwitchAndRetryAfterIsPreserved() {
        val rate = AutoModelFailure.classify(error(ProviderReach.Kind.RATE_LIMITED, 429, retry = 7), false)
            as AutoModelFailure.Decision.Cooldown
        assertEquals(ModelHealth.Failure.RATE_LIMIT, rate.reason)
        assertEquals(7, rate.retryAfterSeconds)
        assertTrue(rate.retryAfterPresent)
        assertEquals(ModelHealth.Failure.QUOTA,
            (AutoModelFailure.classify(error(ProviderReach.Kind.QUOTA, 429), false) as AutoModelFailure.Decision.Cooldown).reason)
        assertEquals(ModelHealth.Failure.AUTH,
            (AutoModelFailure.classify(LLMError.InvalidApiKey(), false) as AutoModelFailure.Decision.Cooldown).reason)
        assertEquals(ModelHealth.Failure.NETWORK,
            (AutoModelFailure.classify(LLMError.NetworkError(Exception()), false) as AutoModelFailure.Decision.Cooldown).reason)
        assertEquals(ModelHealth.Failure.REQUEST_TOO_LARGE,
            (AutoModelFailure.classify(error(ProviderReach.Kind.REQUEST_TOO_LARGE, 413), false) as AutoModelFailure.Decision.Cooldown).reason)
    }

    @Test fun arbitraryBadRequestsCancellationAndUnsupportedOutputDoNotSwitch() {
        assertNull(AutoModelFailure.classify(error(ProviderReach.Kind.PROVIDER_ERROR, 400, "invalid parameter"), true))
        assertNull(AutoModelFailure.classify(error(ProviderReach.Kind.RESPONSE_LIMIT, 400), true))
        assertNull(AutoModelFailure.classify(error(ProviderReach.Kind.REQUEST_TOO_LARGE, 400), true))
        assertNull(AutoModelFailure.classify(LLMError.Cancelled(), true))
        assertNull(AutoModelFailure.classify(IllegalStateException("bug"), true))
    }

    @Test fun toolsAreLearnedOnlyFromAnExplicitRefusalWhenToolsWereSent() {
        val error = error(ProviderReach.Kind.PROVIDER_ERROR, 400, "This model does not support tools")
        assertEquals(AutoModelFailure.Decision.UnsupportedTools, AutoModelFailure.classify(error, true))
        assertNull(AutoModelFailure.classify(error, false))
        val ambiguous = error(ProviderReach.Kind.PROVIDER_ERROR, 400, "tool_choice is not supported")
        assertNull(AutoModelFailure.classify(ambiguous, true))
    }
}
