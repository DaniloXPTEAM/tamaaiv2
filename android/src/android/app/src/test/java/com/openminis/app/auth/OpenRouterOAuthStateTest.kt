package com.openminis.app.auth

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** No credentials, external services or browser required. */
class OpenRouterOAuthStateTest {
    @Test fun eachCallbackStateIsUnpaddedUrlSafeAndIndependent() {
        val states = (1..32).map { OpenRouterOAuthManager.nmNewState() }
        assertEquals(states.size, states.toSet().size)
        for (state in states) {
            assertTrue(state.matches(Regex("[A-Za-z0-9_-]{43}")))
            assertEquals(32, Base64.getUrlDecoder().decode(state).size)
        }
    }
}
