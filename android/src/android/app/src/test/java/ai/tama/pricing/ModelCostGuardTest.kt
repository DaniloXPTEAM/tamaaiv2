package ai.tama.pricing

import org.junit.Assert.*
import org.junit.Test

class ModelCostGuardTest {
    @Test fun `normal package remains unchanged and parallel package denies unverified cost`() {
        assertFalse(ModelCostGuard.permits(parallelTest = true, allowUnverified = false))
        assertTrue(ModelCostGuard.permits(parallelTest = true, allowUnverified = true))
        assertTrue(ModelCostGuard.permits(parallelTest = false, allowUnverified = false))
        assertTrue(ModelCostGuard.permits(parallelTest = false, allowUnverified = true))
    }

    @Test fun `possible charges need an explicit process local opt in`() {
        if (com.openminis.app.BuildConfig.APPLICATION_ID != "ai.tama.assistant.tamav2test") {
            assertTrue(ModelCostGuard.permits())
            assertFalse(ModelCostGuard.allowedForCurrentProcess())
            return
        }
        ModelCostGuard.setAllowedForCurrentProcess(false)
        try {
            assertFalse(ModelCostGuard.permits())
            try {
                ModelCostGuard.requireAllowed()
                fail("an unverified route must not be dispatched")
            } catch (_: ModelCostGuard.Blocked) {
                // No route, model, endpoint, body or account can grant permission here.
            }
            ModelCostGuard.setAllowedForCurrentProcess(true)
            assertTrue(ModelCostGuard.allowedForCurrentProcess())
            ModelCostGuard.requireAllowed()
            ModelCostGuard.setAllowedForCurrentProcess(false)
            assertFalse(ModelCostGuard.permits())
        } finally {
            ModelCostGuard.setAllowedForCurrentProcess(false)
        }
    }

    @Test fun `a cost observation does not survive a revoke and re-enable`() {
        val parallel = com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"
        ModelCostGuard.setAllowedForCurrentProcess(true)
        try {
            val first = ModelCostGuard.observe()
            assertTrue(first.isCurrent())
            ModelCostGuard.setAllowedForCurrentProcess(false)
            if (parallel) assertFalse(first.isCurrent()) else assertTrue(first.isCurrent())
            ModelCostGuard.setAllowedForCurrentProcess(true)
            if (parallel) assertFalse(first.isCurrent()) else assertTrue(first.isCurrent())
            assertTrue(ModelCostGuard.observe().isCurrent())
        } finally {
            ModelCostGuard.setAllowedForCurrentProcess(false)
        }
    }

    @Test fun `wrapped local cost refusal is still recognizable and not a network error`() {
        val refusal = ModelCostGuard.Blocked()
        assertTrue(refusal === ModelCostGuard.blockedCause(IllegalStateException("wrapper", refusal)))
        assertTrue(ModelCostGuard.causedByBlock(java.io.IOException("wrapper", refusal)))
        assertFalse(ModelCostGuard.causedByBlock(java.io.IOException("network")))
        val error: Throwable = refusal
        assertFalse(error is java.io.IOException)
    }
}
