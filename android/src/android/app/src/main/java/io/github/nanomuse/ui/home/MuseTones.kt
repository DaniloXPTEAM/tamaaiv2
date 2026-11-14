package io.github.nanomuse.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.openminis.app.ui.theme.ChatColors

/** The TAMA home shell's warm surfaces, following the app's own theme override. */
object MuseTones {
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = ChatColors.isDark

    /** Cards, pills, round buttons, sheets and the drawer. */
    val surface: Color
        @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF30251D) else Color(0xFFFFFCF5)

    /** A selected row, the active segment and the disc under the face. */
    val fill: Color
        @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF49372A) else Color(0xFFF7E7C9)

    val bubble: Color
        @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF3A2A1E) else Color(0xFFF7E7C9)

    val disc: Color
        @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF594024) else Color(0xFFFFE5AD)

    val hairline: Color
        @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF705744) else Color(0xFFE6D5B8)

    val canvas: Color
        @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFF19130F) else Color(0xFFFFF8E9)

    /** Filled actions use white text. Accent is for text/icons directly on a themed surface. */
    val action: Color = Color(0xFF87410B)
    val accent: Color
        @Composable @ReadOnlyComposable get() = if (isDark) Color(0xFFFFCA74) else action
}
