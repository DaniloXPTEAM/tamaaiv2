package ai.tama.router

/** Selects a truthful, bounded reason for a system notice; never retains provider bodies. */
object AutoSwitchReason {
    sealed interface Cause {
        data class Failure(val reason: ModelHealth.Failure) : Cause
        data class Local(val explanation: String) : Cause
        data class Rejected(val reason: ModelRouter.Reason) : Cause
    }

    fun forSwitch(
        previousEntryId: String?,
        selectedEntryId: String,
        failures: List<AutoModelTurn.FailureRecord>,
        localBlocks: Map<String, String>,
        rejected: List<ModelRouter.Rejection>,
    ): Cause? {
        val previous = previousEntryId ?: return null
        if (previous == selectedEntryId) return null
        failures.lastOrNull { it.entryId == previous }?.let { return Cause.Failure(it.reason) }
        localBlocks[previous]?.let { return Cause.Local(it) }
        return rejected.firstOrNull { it.entryId == previous }?.let { Cause.Rejected(it.reason) }
    }
}
