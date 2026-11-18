package ai.tama.router

/** Conservative, in-memory guard for native image tool calls within one user turn. */
class ImageTurnGuard(private var explicitPersonRequest: Boolean) {
    enum class Decision { ALLOW, NOT_REQUESTED, ALREADY_ATTEMPTED, TRUNCATED }
    private var attempted = false

    fun consume(truncated: Boolean = false): Decision {
        if (truncated) {
            attempted = true
            return Decision.TRUNCATED
        }
        if (!explicitPersonRequest) return Decision.NOT_REQUESTED
        if (attempted) return Decision.ALREADY_ATTEMPTED
        attempted = true
        return Decision.ALLOW
    }

    fun newTurn(explicitPersonRequest: Boolean) {
        this.explicitPersonRequest = explicitPersonRequest
        attempted = false
    }
}
