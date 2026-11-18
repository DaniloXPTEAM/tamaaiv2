package io.github.nanomuse.hub

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real HubErrors mapping with fake resource IDs; not an Android UI test. */
class HubErrorsHostTest {
    private val context = Context()
    private fun resource(id: Int) = "resource-$id"

    @After fun restoreIdentity() { BuildConfig.APPLICATION_ID = "ai.tama.assistant.tamav2test" }

    @Test fun witnessedAdmissionFailuresHaveSpecificExplanationsOnlyInTheParallelPackage() {
        for (identity in listOf("ai.tama.assistant.tamav2test", "ai.tama.assistant")) {
            BuildConfig.APPLICATION_ID = identity
            for ((code, id) in listOf(
                "duplicate_call" to R.string.nm_hub_err_id_reused,
                "bad_call_id" to R.string.nm_hub_err_bad_call_id,
                "call_id_limit" to R.string.nm_hub_err_id_limit,
            )) {
                val explained = if (identity.endsWith(".tamav2test")) id else R.string.nm_hub_err_generic
                assertEquals(resource(explained), HubErrors.describe(context, HubException(code, "ignored", notAdmitted = true)))
                assertEquals(resource(R.string.nm_hub_err_generic), HubErrors.describe(context, HubException(code, "ignored")))
                assertEquals(resource(R.string.nm_hub_err_generic), HubErrors.describe(context, code))
            }
            assertEquals(resource(R.string.nm_hub_err_busy), HubErrors.describe(context, HubException("busy", "ignored")))
        }
    }
}
