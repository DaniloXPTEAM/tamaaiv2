package ai.tama.router

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTurnGuardTest {
    @Test fun `one explicit turn permits only one native attempt even after failure`() {
        val guard = ImageTurnGuard(true)
        assertEquals(ImageTurnGuard.Decision.ALLOW, guard.consume())
        assertEquals(ImageTurnGuard.Decision.ALREADY_ATTEMPTED, guard.consume())
        guard.newTurn(false)
        assertEquals(ImageTurnGuard.Decision.NOT_REQUESTED, guard.consume())
        guard.newTurn(true)
        assertEquals(ImageTurnGuard.Decision.ALLOW, guard.consume())
    }

    @Test fun `a truncated tool call blocks even a later complete call in the same turn`() {
        val guard = ImageTurnGuard(true)
        assertEquals(ImageTurnGuard.Decision.TRUNCATED, guard.consume(truncated = true))
        assertEquals(ImageTurnGuard.Decision.ALREADY_ATTEMPTED, guard.consume())
    }
}
