package io.github.nanomuse.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.openminis.app.ui.theme.DarkChatPalette
import com.openminis.app.ui.theme.LightChatPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat colours are shared by the real theme, including an in-app light/dark override. */
class TamaPaletteTest {
    private fun contrast(a: Color, b: Color): Float {
        val low = minOf(a.luminance(), b.luminance())
        val high = maxOf(a.luminance(), b.luminance())
        return (high + 0.05f) / (low + 0.05f)
    }

    @Test fun lightAndDarkKeepReadableMessageInk() {
        for (palette in listOf(LightChatPalette, DarkChatPalette)) {
            assertTrue("user message", contrast(palette.userBubble, palette.onUserBubble) >= 4.5f)
            assertTrue("assistant message", contrast(palette.assistantBubble, palette.primaryText) >= 4.5f)
            assertTrue("body on canvas", contrast(palette.background, palette.primaryText) >= 4.5f)
            assertTrue("secondary on canvas", contrast(palette.background, palette.secondaryText) >= 4.5f)
            assertTrue("link on canvas", contrast(palette.background, palette.link) >= 4.5f)
        }
    }

    @Test fun theTwoCanvasesUseTamaColoursRatherThanPureBlackAndWhite() {
        assertEquals(Color(0xFFFFF8E9), LightChatPalette.background)
        assertEquals(Color(0xFF19130F), DarkChatPalette.background)
        assertTrue(!LightChatPalette.isDark && DarkChatPalette.isDark)
    }
}
