package ai.tama.net

import org.json.JSONObject

/**
 * A raw HTTP error may echo request data, a bearer token or endpoint credentials.
 * Do not pass its body, URL, headers or file path to this presentation policy.
 * The parallel package withholds that untrusted response entirely. Successful
 * passthrough responses remain raw by design and require a separate audit.
 */
internal object RawModelUseFailure {
    fun response(parallel: Boolean, status: Int): String? {
        if (!parallel || status in 200..299) return null
        return JSONObject()
            .put("error", "passthrough_failed")
            .put("http_status", status)
            .put("message", "Provider request failed; details withheld.")
            .put("tell_user", "The request may have been charged. Do not retry without a new user request.")
            .toString() + "\n"
    }
}
