package ai.tama.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageShortcutEligibilityTest {
    @Test fun `only a single standalone person turn can use the shortcut`() {
        val text = "gere uma imagem de um pássaro"
        val clean = ImageShortcutContext(person = true)
        assertEquals(Shortcut.Image("um pássaro"), ImageShortcutEligibility.select(text, clean))
        listOf(
            clean.copy(person = false), clean.copy(batchSize = 2), clean.copy(hasAttachments = true),
            clean.copy(hasPastedContent = true), clean.copy(editing = true), clean.copy(sharedContent = true),
            clean.copy(busy = true),
        ).forEach { assertNull(ImageShortcutEligibility.select(text, it)) }
        assertNull(ImageShortcutEligibility.select("gere uma imagem de [Pasted#1]", clean))
    }

    @Test fun `human send branch invokes the fake image action and never the fake chat client`() {
        var imageCalls = 0
        var chatCalls = 0
        var description = ""
        ImageShortcutEligibility.dispatch(
            "gere uma imagem de um gato", ImageShortcutContext(person = true),
            image = { imageCalls++; description = it.prompt },
            chat = { chatCalls++ },
        )
        assertEquals("um gato", description)
        assertEquals(1, imageCalls)
        assertEquals(0, chatCalls)
    }

    @Test fun `ambiguous automated and media sends fall through to the fake chat client`() {
        val cases = listOf(
            "gere uma imagem de um gato e abra o banco" to ImageShortcutContext(person = true),
            "gere uma imagem de um gato" to ImageShortcutContext(person = false),
            "gere uma imagem de um gato" to ImageShortcutContext(person = true, hasAttachments = true),
            "gere uma imagem de um gato" to ImageShortcutContext(person = true, busy = true),
        )
        var imageCalls = 0
        var chatCalls = 0
        cases.forEach { (text, context) ->
            ImageShortcutEligibility.dispatch(text, context, image = { imageCalls++ }, chat = { chatCalls++ })
        }
        assertEquals(0, imageCalls)
        assertEquals(cases.size, chatCalls)
    }
}
