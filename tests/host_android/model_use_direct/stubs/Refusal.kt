package ai.tama.cloud
import io.github.nanomuse.cloud.AllowanceSignal
object ProviderRefusalSanitizer {
    fun sanitize(error: AllowanceSignal.HttpError, credentials: Collection<String>) = error
}
