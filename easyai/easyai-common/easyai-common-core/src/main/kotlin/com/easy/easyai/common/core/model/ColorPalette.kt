package com.easy.easyai.common.core.model

/**
 * Color palette for terminal output.
 * Used to consistently style different types of terminal messages.
 */
interface ColorPalette {
    /**
     * Primary highlight color — used for system messages, prompts, welcome text.
     */
    val highlight: Int

    /**
     * Secondary color — used for assistant response content.
     */
    val color2: Int
}

/**
 * Default color palette with a sandy khaki highlight and muted green for content.
 */
data class DefaultColorPalette(
    override val highlight: Int = 0xbeb780,
    override val color2: Int = 0x7da17e,
) : ColorPalette
