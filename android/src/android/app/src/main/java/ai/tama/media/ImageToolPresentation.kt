package ai.tama.media

import org.json.JSONObject

/** Only an allowlisted outcome reaches the image detail UI, never raw tool arguments or JSON. */
internal object ImageToolPresentation {
    enum class Summary {
        PREPARING, GENERATED, NO_MODEL, NO_APPROVAL, UNVERIFIED_COST, MODEL_COST_UNVERIFIED,
        AUTHORIZATION_CHANGED, PERMISSION_DENIED, EDIT_UNAVAILABLE, MODEL_CHANGE_BLOCKED, AMBIGUOUS, ALREADY_HANDLED, INVALID, FAILED,
    }

    fun summary(
        raw: String,
        live: Boolean,
        success: Boolean,
        ambiguousMessage: String,
        repeatedMessage: String,
        invalidMessage: String,
    ): Summary {
        if (live) return Summary.PREPARING
        // These exact local strings come from the image guard, not an arbitrary provider reply.
        if (raw == ambiguousMessage) return Summary.AMBIGUOUS
        if (raw == repeatedMessage) return Summary.ALREADY_HANDLED
        if (raw == invalidMessage) return Summary.INVALID
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return Summary.FAILED
        if (success && json.optBoolean("ok", false)) return Summary.GENERATED
        return when (json.optString("error", "")) {
            "no_image_model" -> Summary.NO_MODEL
            "no_approved_image_model" -> Summary.NO_APPROVAL
            "automatic_image_cost_unverified" -> Summary.UNVERIFIED_COST
            "image_cost_unverified" -> Summary.MODEL_COST_UNVERIFIED
            "image_authorization_changed" -> Summary.AUTHORIZATION_CHANGED
            "permission_denied" -> Summary.PERMISSION_DENIED
            "automatic_image_edit_unavailable" -> Summary.EDIT_UNAVAILABLE
            "media_model_change_blocked" -> Summary.MODEL_CHANGE_BLOCKED
            "invalid_image_request" -> Summary.INVALID
            else -> Summary.FAILED
        }
    }

    /** Rebuild a failed tool result without copying any provider-supplied message or URL. */
    fun sanitizedFailure(raw: String, messages: Map<String, String>, fallback: String): String {
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        val code = parsed?.optString("error", "").orEmpty()
        val safeCode = when (code) {
            "no_image_model", "no_approved_image_model", "automatic_image_cost_unverified", "image_cost_unverified",
            "image_authorization_changed", "permission_denied", "automatic_image_edit_unavailable", "invalid_image_request",
            "media_model_change_blocked", "image_failed" -> code
            else -> if (raw in setOf(
                    "Invalid generate_image arguments; no image was requested.",
                    "Unsupported generate_image parameter; no image was requested.",
                    "A non-empty image prompt of at most 4000 characters is required.",
                    "Image size must be WIDTHxHEIGHT, each from 128 to 4096; no image was requested.",
                )) "invalid_image_request" else "image_failed"
        }
        val message = messages[safeCode] ?: fallback
        val settings = when (safeCode) {
            "no_image_model", "media_model_change_blocked" -> "minis://settings/media"
            "no_approved_image_model", "automatic_image_cost_unverified", "image_cost_unverified",
            "image_authorization_changed", "automatic_image_edit_unavailable" -> "minis://settings/models"
            "permission_denied" -> "minis://settings/permissions"
            else -> null
        }
        // The result is sent back to the agent, not just displayed. Reconstruct
        // the no-retry instruction locally instead of dropping it or trusting an
        // untrusted `tell_user` field supplied by a provider.
        val instruction = if (safeCode == "image_failed") {
            "Explain the message. The request may have been charged. Do not retry or use another image model without a new user request."
        } else {
            "Explain the message and any settings link. Do not retry automatically. Requests already sent may still be charged."
        }
        return JSONObject().put("ok", false).put("error", safeCode).put("message", message).apply {
            if (settings != null) put("settings", settings)
            put("tell_user", instruction)
        }.toString()
    }
}
