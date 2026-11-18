package ai.tama.sandbox

import com.openminis.app.sandbox.SeccompFallbackPolicy

/** A silent, quick signal death does not prove that a command had no effects. */
internal object ShellRetrySafety {
    fun shouldRetry(
        parallel: Boolean,
        exitCode: Int?,
        durationMs: Long,
        producedOutput: Boolean,
    ): Boolean = !parallel && SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
        exitCode = exitCode,
        durationMs = durationMs,
        producedOutput = producedOutput,
        alreadyRetried = false,
    )
}
