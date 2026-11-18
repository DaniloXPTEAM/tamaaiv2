package ai.tama.media

import io.github.nanomuse.media.*

import android.content.Context
import com.openminis.app.R
import com.openminis.app.offload.OffloadPermissionManager
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.guard.GateOutcome
import io.github.nanomuse.guard.RiskGate
import io.github.nanomuse.net.ProviderReach
import ai.tama.router.AutoModelRuntime
import ai.tama.router.AutoModelStore
import java.security.MessageDigest
import kotlinx.coroutines.withTimeoutOrNull

/** A revocation witness, never a permission grant in its own right. */
internal interface MediaPermissionObservation : AutoCloseable {
    fun isCurrent(): Boolean
}

/** The same permission and risk checks apply to the CLI, the native tool and shortcuts. */
internal fun interface MediaImageGate {
    suspend fun allow(endpoint: ImageGen.Endpoint, prompt: String, size: String, sessionId: String?, freshApproval: Boolean): Boolean

    // Fail closed if another implementation forgets to support transport revalidation.
    fun observe(endpoint: ImageGen.Endpoint, sessionId: String?): MediaPermissionObservation =
        object : MediaPermissionObservation {
            override fun isCurrent() = false
            override fun close() = Unit
        }
}

internal class AndroidMediaImageGate(
    private val context: Context,
    private val checkRisk: suspend (String, String) -> GateOutcome = RiskGate::checkShell,
    private val permissionTimeoutMs: Long = PERMISSION_TIMEOUT_MS,
) : MediaImageGate {
    override fun observe(endpoint: ImageGen.Endpoint, sessionId: String?): MediaPermissionObservation {
        val accountVersion = AutoModelRuntime.accountVersion(endpoint.instanceId)
        val permission = OffloadPermissionManager.observePermission(
            IMAGE_PERMISSION, sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID,
        )
        return object : MediaPermissionObservation {
            override fun isCurrent(): Boolean = permission.isCurrent() &&
                accountVersion == AutoModelRuntime.accountVersion(endpoint.instanceId)
            override fun close() = permission.close()
        }
    }

    override suspend fun allow(endpoint: ImageGen.Endpoint, prompt: String, size: String, sessionId: String?, freshApproval: Boolean): Boolean {
        val sid = sessionId ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
        // This is assessed, not executed. Quoting makes the assessment match the
        // shell CLI without treating the user's description as command syntax.
        val command = "nanomuse-media image --prompt ${quote(prompt)} --size ${quote(size)}"
        if (checkRisk(sid, command) is GateOutcome.Denied) return false
        val model = ProviderReach.redactedText(endpoint.model, listOf(endpoint.apiKey), 128)
        val provider = ProviderReach.redactedText(endpoint.label, listOf(endpoint.apiKey), 128)
        val title = context.getString(R.string.nm_models_image_title) + ": $model ($provider)"
        // A session grant applies only to the shown model, provider, transport and
        // account generation, not to another potentially more expensive route.
        // The fingerprint contains no credential or raw endpoint and is kept in memory.
        val accountVersion = AutoModelRuntime.accountVersion(endpoint.instanceId)
        val identity = listOf(
            endpoint.instanceId, endpoint.model, AutoModelStore.routeFingerprint(endpoint.instance),
            endpoint.instance.imageEndpointResolved?.name.orEmpty(), accountVersion.toString(),
        ).joinToString("") { "${it.length}:$it" }
        val scope = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // The permission manager queues dialogs; a timeout is a refusal, not consent.
        val approved = withTimeoutOrNull(permissionTimeoutMs) {
            OffloadPermissionManager.checkPermission(
                IMAGE_PERMISSION, title, sid, requireFreshApproval = freshApproval, grantScope = scope,
            )
        } ?: false
        // A credential or route can change while a dialog is visible. An answer
        // to the previous account does not authorize the new one.
        return approved && accountVersion == AutoModelRuntime.accountVersion(endpoint.instanceId)
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val IMAGE_PERMISSION = "image_generation"
        private const val PERMISSION_TIMEOUT_MS = 120_000L
    }
}
