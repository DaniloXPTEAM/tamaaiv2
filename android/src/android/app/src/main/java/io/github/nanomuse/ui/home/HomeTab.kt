package io.github.nanomuse.ui.home

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.automirrored.filled.Feed
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.automirrored.outlined.Feed
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.ui.graphics.vector.ImageVector
import com.openminis.app.R

/**
 * The unchanged destinations: chat, feed, ideas, goals and library.
 */
enum class HomeTab(
    @StringRes val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    CHAT(R.string.nm_tab_chat, Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble),
    FEED(R.string.nm_tab_feed, Icons.AutoMirrored.Outlined.Feed, Icons.AutoMirrored.Filled.Feed),
    IDEAS(R.string.nm_tab_ideas, Icons.Outlined.Lightbulb, Icons.Filled.Lightbulb),
    GOALS(R.string.nm_tab_goals, Icons.Outlined.CheckBox, Icons.Filled.CheckBox),
    LIBRARY(R.string.nm_tab_library, Icons.Outlined.Category, Icons.Filled.Category),
}
