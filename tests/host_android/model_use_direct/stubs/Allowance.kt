package io.github.nanomuse.cloud
object AllowanceSignal {
    class HttpError
    fun fromHttpError(code: Int, body: String, fromRelay: Boolean, hasHttpStatus: Boolean): HttpError? = null
}
object ClaudeSignIn { const val identifier = ""; const val MISSING = "OAuth unavailable in host test" }
