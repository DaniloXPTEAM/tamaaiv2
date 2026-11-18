package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic chat colors mirroring iOS ChatColors (AIChatView.swift).
// Resolved from LocalChatPalette, which is provided by MinisTheme.
//
// iOS reference:
//   systemBackground        -> background
//   secondarySystemBackground -> secondaryBg
//   tertiarySystemFill      -> userBubble
//   tertiarySystemGroupedBackground -> toolBg
//   label                   -> primaryText
//   secondaryLabel          -> secondaryText
//   tertiaryLabel           -> tertiaryText
//   quaternaryLabel         -> sendButtonDisabled
//   separator               -> border
//   systemGray6             -> inlineCodeBg / toolCapsuleBg
@Immutable
data class ChatPalette(
    val isDark: Boolean,
    val background: Color,
    val secondaryBg: Color,
    val inputBg: Color,
    val inputIconBg: Color,
    val inputIconBorder: Color,
    val inputBorder: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val disabledText: Color,
    val userBubble: Color,
    // nanoMuse: the text ink on the user bubble, and the assistant's own pill
    // (a touch off the background, both themes).
    val onUserBubble: Color,
    val assistantBubble: Color,
    val toolBg: Color,
    val toolBorder: Color,
    val toolCapsuleBg: Color,
    val separator: Color,
    val sendButton: Color,
    val sendButtonDisabled: Color,
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val link: Color,
    val blockquoteBar: Color,
    val thinking: Color,
    val warningBg: Color,
    val warningText: Color,
    val tableBorder: Color,
    val inputShadow: Color,
    val toastBg: Color,
    val thumbnailBorder: Color,
    val sheetHeaderBg: Color,
    val sheetHeaderBorder: Color,
    val fabAccent: Color,
)

// nanoMuse: the TAMA chat canvas and bubbles use the same warm palette as the home shell.
// scripts/tama_delta.py reapplies the named properties after rebrand.py.
val LightChatPalette = ChatPalette(
    isDark = false,
    background = Color(0xFFFFF8E9),
    secondaryBg = Color(0xFFF8EBD7),
    inputBg = Color(0xFFFFFCF5),
    inputIconBg = Color(0xFFF5E5CA),
    inputIconBorder = Color.Transparent,
    inputBorder = Color(0xFFD8BFA0),
    primaryText = Color(0xFF281B12),
    secondaryText = Color(0xFF705943),
    tertiaryText = Color(0xFF8C755E),
    disabledText = Color(0xFFAD9A87),
    // nanoMuse: an amber-brown user bubble with white ink.
    userBubble = Color(0xFF87410B),
    onUserBubble = Color(0xFFFFFFFF),
    assistantBubble = Color(0xFFF7E7C9),
    toolBg = Color(0xFFF8EBD7),
    toolBorder = Color(0x14000000),
    toolCapsuleBg = Color(0xFFF5E5CA),
    separator = Color(0xFFE6D5B8),
    sendButton = Color(0xFF87410B),
    sendButtonDisabled = Color(0x2E3C3C43),
    codeBlockBg = Color(0xFF30251D),
    codeBlockText = Color(0xFFE5E5EA),
    inlineCodeBg = Color(0xFFF3E2C8),
    inlineCodeText = Color(0xFF87410B),
    link = Color(0xFF87410B),
    blockquoteBar = Color(0xFFB56B27),
    thinking = Color(0xFF87410B),
    warningBg = Color(0x14FF9500),
    warningText = Color(0x73000000),
    tableBorder = Color(0x1F000000),
    inputShadow = Color.Transparent,
    toastBg = Color(0xFFF4D8A8),
    thumbnailBorder = Color(0x33808080),
    sheetHeaderBg = Color(0xFFFFFCF5),
    sheetHeaderBorder = Color(0x1A000000),
    fabAccent = Color(0xFF9B4508),
)

// nanoMuse: warm dark surfaces are layered so the composer and messages stay distinct.
val DarkChatPalette = ChatPalette(
    isDark = true,
    background = Color(0xFF19130F),
    secondaryBg = Color(0xFF30251D),
    inputBg = Color(0xFF392C22),
    inputIconBg = Color(0xFF49372A),
    inputIconBorder = Color(0xFF705744),
    inputBorder = Color(0xFF705744),
    primaryText = Color(0xFFF3E4D6),
    secondaryText = Color(0xFFD7C5B2),
    tertiaryText = Color(0xFFB5A28D),
    disabledText = Color(0xFF8D7D6D),
    // nanoMuse: honey with dark ink keeps the user bubble legible in dark mode.
    userBubble = Color(0xFFFFCA74),
    onUserBubble = Color(0xFF4B2500),
    assistantBubble = Color(0xFF3A2A1E),
    toolBg = Color(0xFF49372A),
    toolBorder = Color(0xFF705744),
    toolCapsuleBg = Color(0xFF392C22),
    separator = Color(0xFF705744),
    sendButton = Color(0xFFFFCA74),
    sendButtonDisabled = Color(0x2EEBEBF5),
    codeBlockBg = Color(0xFF30251D),
    codeBlockText = Color(0xFFE5E5EA),
    // nanoMuse: inline code remains a separate layer against both the canvas and code blocks.
    inlineCodeBg = Color(0xFF49372A),
    inlineCodeText = Color(0xFFFFCA74),
    link = Color(0xFFFFCA74),
    blockquoteBar = Color(0xFFC99040),
    thinking = Color(0xFFFFCA74),
    warningBg = Color(0x14FF9F0A),
    warningText = Color(0x73FFFFFF),
    tableBorder = Color(0xFF38383A),
    inputShadow = Color(0x80000000),
    toastBg = Color(0xFF594024),
    thumbnailBorder = Color(0x20545458),
    sheetHeaderBg = Color(0xFF392C22),
    sheetHeaderBorder = Color(0x33FFFFFF),
    fabAccent = Color(0xFFFFCA74),
)

val LocalChatPalette = compositionLocalOf { LightChatPalette }

// Short accessor: ChatColors.primaryText instead of LocalChatPalette.current.primaryText
val ChatColors: ChatPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalChatPalette.current
