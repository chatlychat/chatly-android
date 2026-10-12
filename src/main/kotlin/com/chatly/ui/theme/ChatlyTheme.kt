package com.chatly.ui.theme

/**
 * Mutable theme palette applied to all Chatly UI surfaces.
 *
 * Colors are stored as `@ColorInt` integers (ARGB) so they can be assigned
 * directly to [android.view.View.setBackgroundColor] etc. Defaults loosely
 * match the brand's blue + slate text palette.
 */
public data class ChatlyTheme(
    val primary: Int = 0xFF2563EB.toInt(),
    val bg: Int = 0xFFFFFFFF.toInt(),
    val fg: Int = 0xFF0F172A.toInt(),
    val mutedFg: Int = 0xFF64748B.toInt(),
    val border: Int = 0x14_0F_172A,
)
