package ai.tama.router

import android.content.Context
import com.openminis.app.BuildConfig

/**
 * Temporary automatic-routing restriction for the isolated test build. A route's
 * approval and a catalogue's advertised zero prices are not proof that a specific
 * request and every alternate endpoint are free. Until the request transport can
 * enforce that proof, automatic calls at guarded sites require a separate opt-in.
 * This is not a global spending limit and does not cover all manual calls or services.
 */
internal object AutomaticCostGuard {
    fun permits(parallelTest: Boolean, allowUnverified: Boolean): Boolean =
        !parallelTest || allowUnverified

    fun permits(context: Context): Boolean {
        val parallel = BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"
        return permits(parallel, parallel && AutoModelStore.allowUnverifiedAutomaticCost(context))
    }
}
