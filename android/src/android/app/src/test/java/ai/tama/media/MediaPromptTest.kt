package ai.tama.media

import io.github.nanomuse.media.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPromptTest {
    @Test fun `automatic mode explains the missing image requirements without claiming no provider exists`() {
        val text = MediaPrompt.build(null, null, "minis://settings/media", automaticImages = true)
        assertTrue(text.contains("No eligible automatic image model"))
        assertTrue(text.contains("supported image route"))
        assertTrue(text.contains("separate permission"))
        assertTrue(text.contains("[Models](minis://settings/models)"))
        assertTrue(text.contains("do not retry or pretend"))
        assertFalse(text.contains("No image model is set"))
    }

    @Test fun `automatic image prompt recommends the native tool and forbids reference edits`() {
        val text = MediaPrompt.build("image model via provider", "video model", "minis://settings/media", true)
        assertTrue(text.contains("call `generate_image`"))
        assertTrue(text.contains("do not retry after a failed image request"))
        assertTrue(text.contains("Image editing with --from is unavailable"))
        assertTrue(text.contains("minis-model-use run` is unavailable"))
        assertTrue(text.contains("Image model: image model via provider"))
    }

    @Test fun `manual image prompt retains its previous edit and missing model guidance`() {
        val text = MediaPrompt.build(null, null, "minis://settings/media")
        assertTrue(text.contains("`nanomuse-media image --prompt \"...\" [--from <image path>]`"))
        assertTrue(text.contains("No image model is set"))
        assertFalse(text.contains("minis-model-use run` is unavailable"))
    }
}
