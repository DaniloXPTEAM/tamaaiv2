package ai.tama.media

import io.github.nanomuse.media.*

import com.openminis.app.tools.ToolExecutionResult
import ai.tama.router.Shortcut
import ai.tama.router.ShortcutParser
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImageShortcutRunnerTest {
    private val failure = "May have been charged. Do not retry without a new request."

    @Test fun `a classified shortcut invokes only the image executor once`() = runBlocking {
        val image = ShortcutParser.parse("gera imagem de um pássaro") as Shortcut.Image
        var imageCalls = 0
        val answer = ImageShortcutRunner.run(image.prompt, failure, "Settings") { prompt ->
            imageCalls++
            assertEquals("um pássaro", prompt)
            ToolExecutionResult(JSONObject().put("markdown", "![bird](minis://attachments/bird.png)").toString(), true)
        }
        assertEquals("![bird](minis://attachments/bird.png)", answer.text)
        assertEquals(true, answer.success)
        assertEquals(1, imageCalls)
    }

    @Test fun `missing provider keeps the exact message and a settings link`() = runBlocking {
        val message = "Nenhum provedor de imagem configurado — abra Configurações → Modelos de imagem e vídeo"
        val answer = ImageShortcutRunner.run("bird", failure, "Configurações") {
            ToolExecutionResult(JSONObject().put("error", "no_image_model").put("message", message)
                .put("settings", "minis://settings/media").toString(), false)
        }
        assertEquals("$message\n\n[Configurações](minis://settings/media)", answer.text)
        assertEquals(false, answer.success)
    }

    @Test fun `provider errors never echo text and never trigger a retry`() = runBlocking {
        var calls = 0
        val answer = ImageShortcutRunner.run("bird", failure, "Settings") {
            calls++
            ToolExecutionResult(JSONObject().put("error", "image_failed").put("message", "secret and prompt")
                .put("settings", "https://attacker.invalid").toString(), false)
        }
        assertEquals(failure, answer.text)
        assertEquals(false, answer.success)
        assertFalse(answer.text.contains("secret"))
        assertEquals(1, calls)
    }

    @Test fun `parallel shortcut cannot display arbitrary failure content or links`() = runBlocking {
        val messages = mapOf("no_image_model" to "Sem modelo", "image_failed" to failure)
        for (code in listOf("unexpected", "no_image_model", "image_failed")) {
            val answer = ImageShortcutRunner.run("bird", failure, "Configurações", safeFailureMessages = messages) {
                ToolExecutionResult(JSONObject().put("error", code).put("message", "private prompt or token")
                    .put("settings", "https://attacker.invalid").toString(), false)
            }
            assertFalse(answer.text.contains("private"))
            assertFalse(answer.text.contains("attacker"))
            assertEquals(if (code == "no_image_model") "Sem modelo\n\n[Configurações](minis://settings/media)" else failure, answer.text)
        }
    }

    @Test fun `invalid success does not pretend an image was generated`() = runBlocking {
        val answer = ImageShortcutRunner.run("bird", failure, "Settings") {
            ToolExecutionResult("{\"markdown\":\"[fake](https://example.com)\"}", true)
        }
        assertEquals(failure, answer.text)
        assertEquals(false, answer.success)
    }
}
