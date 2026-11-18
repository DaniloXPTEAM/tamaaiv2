package ai.tama.media

import io.github.nanomuse.media.*

import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Executes one already-classified image shortcut, without a chat-model dependency. */
internal object ImageShortcutRunner {
    data class Reply(val text: String, val success: Boolean)

    suspend fun run(
        prompt: String,
        failureMessage: String,
        settingsLabel: String,
        safeFailureMessages: Map<String, String>? = null,
        execute: suspend (String) -> ToolExecutionResult,
    ): Reply {
        val result = try {
            execute(prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Reply(failureMessage, false)
        }
        val body = try { JSONObject(result.output) } catch (_: Exception) { return Reply(failureMessage, false) }
        if (result.success) {
            val markdown = body.optString("markdown", "").takeIf {
                it.startsWith("![") && it.contains("](minis://attachments/") && it.endsWith(")")
            } ?: return Reply(failureMessage, false)
            return Reply(markdown, true)
        }
        // Provider errors may echo the person's prompt or secrets. In the parallel
        // build even an unexpected error code receives only a locally authored text.
        val safeBody = if (safeFailureMessages == null) body else JSONObject(
            ImageToolPresentation.sanitizedFailure(result.output, safeFailureMessages, failureMessage),
        )
        val code = safeBody.optString("error", "")
        val message = if (code == "image_failed") failureMessage
            else safeBody.optString("message", "").takeIf { it.isNotBlank() } ?: failureMessage
        val settings = safeBody.optString("settings", "").takeIf {
            it in setOf("minis://settings/media", "minis://settings/models", "minis://settings/permissions")
        }
        return Reply(if (settings == null) message else "$message\n\n[$settingsLabel]($settings)", false)
    }
}
