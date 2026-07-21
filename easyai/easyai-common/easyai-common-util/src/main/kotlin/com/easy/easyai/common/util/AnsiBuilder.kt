package com.easy.easyai.common.util

/**
 * Convert hex color value (Int) to RGB components.
 */
fun hexToRgb(hexValue: Int): Triple<Int, Int, Int> {
    val r = ((hexValue shr 16) and 0xFF)
    val g = ((hexValue shr 8) and 0xFF)
    val b = (hexValue and 0xFF)
    return Triple(r, g, b)
}

/**
 * ANSI color codes for foreground text.
 */
object AnsiColor {
    const val BLACK = "30"
    const val RED = "31"
    const val GREEN = "32"
    const val YELLOW = "33"
    const val BLUE = "34"
    const val MAGENTA = "35"
    const val CYAN = "36"
    const val WHITE = "37"

    // Bright variants
    const val BRIGHT_BLACK = "90"
    const val BRIGHT_RED = "91"
    const val BRIGHT_GREEN = "92"
    const val BRIGHT_YELLOW = "93"
    const val BRIGHT_BLUE = "94"
    const val BRIGHT_MAGENTA = "95"
    const val BRIGHT_CYAN = "96"
    const val BRIGHT_WHITE = "97"
}

/**
 * ANSI style codes.
 */
object AnsiStyle {
    const val BOLD = "1"
    const val DIM = "2"
    const val ITALIC = "3"
    const val UNDERLINE = "4"
    const val BLINK = "5"
    const val REVERSE = "7"
    const val HIDDEN = "8"
}

/**
 * Background colors.
 */
object AnsiBgColor {
    const val BG_BLACK = "40"
    const val BG_RED = "41"
    const val BG_GREEN = "42"
    const val BG_YELLOW = "43"
    const val BG_BLUE = "44"
    const val BG_MAGENTA = "45"
    const val BG_CYAN = "46"
    const val BG_WHITE = "47"

    // Bright variants
    const val BG_BRIGHT_BLACK = "100"
    const val BG_BRIGHT_RED = "101"
    const val BG_BRIGHT_GREEN = "102"
    const val BG_BRIGHT_YELLOW = "103"
    const val BG_BRIGHT_BLUE = "104"
    const val BG_BRIGHT_MAGENTA = "105"
    const val BG_BRIGHT_CYAN = "106"
    const val BG_BRIGHT_WHITE = "107"
}

/**
 * Applies ANSI formatting with the specified style codes.
 * @param text The text to format
 * @param styles ANSI style/color codes to apply
 * @return The formatted text with ANSI escape sequences
 */
fun ansi(text: String, vararg styles: String): String {
    val start = "\u001B[${styles.joinToString(";")}m"
    val reset = "\u001B[0m"
    return "$start$text$reset"
}

/**
 * Class for building nested ANSI formatting without reset issues.
 */
class AnsiBuilder {
    private val styles = mutableListOf<String>()

    /**
     * Adds styles to the current style stack.
     */
    fun withStyle(vararg newStyles: String): AnsiBuilder {
        styles.addAll(newStyles)
        return this
    }

    /**
     * Applies all current styles to the text.
     */
    fun format(text: String): String {
        if (styles.isEmpty()) return text
        val styleStr = styles.joinToString(";")
        return "\u001B[${styleStr}m$text\u001B[0m"
    }

    /**
     * Creates a copy of the current builder.
     */
    fun copy(): AnsiBuilder {
        val newBuilder = AnsiBuilder()
        newBuilder.styles.addAll(this.styles)
        return newBuilder
    }

    /**
     * Combine multiple formatted strings while preserving each one's formatting.
     */
    companion object {
        fun combine(vararg parts: String): String {
            return parts.joinToString("")
        }
    }
}

/**
 * Extension functions for String for more fluent formatting.
 */
fun String.color(color: String): String = ansi(this, color)

fun String.color(rgb: Triple<Int, Int, Int>): String {
    val (r, g, b) = rgb
    return "\u001B[38;2;$r;$g;${b}m$this\u001B[0m"
}

fun String.color(rgb: Int?): String = if(rgb != null) color(hexToRgb(rgb)) else this

fun String.bgColor(color: String): String = ansi(this, color)
fun String.bold(): String = ansi(this, AnsiStyle.BOLD)
fun String.italic(): String = ansi(this, AnsiStyle.ITALIC)
fun String.underline(): String = ansi(this, AnsiStyle.UNDERLINE)

/**
 * Safe concatenation function for formatted text.
 */
fun concatFormatted(vararg parts: String): String {
    return parts.joinToString("")
}

/**
 * Extension function for nested styles.
 */
fun String.styled(setup: AnsiBuilder.() -> AnsiBuilder): String {
    return setup(AnsiBuilder()).format(this)
}

// ============================================================================
// Terminal cursor and line control utilities
// ============================================================================

/**
 * ANSI escape sequences for terminal control.
 */
object AnsiTerminal {
    private const val ESC = "\u001b["

    /**
     * Move cursor up n lines.
     */
    fun cursorUp(n: Int = 1): String = "${ESC}${n}A"

    /**
     * Move cursor down n lines.
     */
    fun cursorDown(n: Int = 1): String = "${ESC}${n}B"

    /**
     * Move cursor to beginning of current line.
     */
    fun cursorToStartOfLine(): String = "${ESC}G"

    /**
     * Move cursor to specific position (1-based).
     */
    fun moveCursor(row: Int, col: Int): String = "${ESC}${row};${col}H"

    /**
     * Clear from cursor to end of line.
     */
    fun clearLine(): String = "${ESC}K"

    /**
     * Clear entire line and move cursor to start.
     */
    fun clearEntireLine(): String = "${ESC}2K${ESC}G"

    /**
     * Clear screen.
     */
    fun clearScreen(): String = "${ESC}2J${ESC}H"

    /**
     * Clear from cursor to end of screen.
     */
    fun clearScreenFromCursor(): String = "${ESC}J"

    /**
     * Clear from cursor to beginning of screen.
     */
    fun clearScreenToCursor(): String = "${ESC}1J"

    /**
     * Hide cursor.
     */
    fun hideCursor(): String = "${ESC}?25l"

    /**
     * Show cursor.
     */
    fun showCursor(): String = "${ESC}?25h"

    /**
     * Save cursor position.
     */
    fun saveCursor(): String = "${ESC}7"

    /**
     * Restore cursor position.
     */
    fun restoreCursor(): String = "${ESC}8"

    /**
     * Reset all formatting.
     */
    fun reset(): String = "${ESC}0m"

    /**
     * Request cursor position (terminal will respond with ESC[row;colR).
     */
    fun requestCursorPos(): String = "${ESC}6n"

    /**
     * Alternative cursor save (SCO compatible).
     */
    fun saveCursorAlt(): String = "${ESC}s"

    /**
     * Alternative cursor restore (SCO compatible).
     */
    fun restoreCursorAlt(): String = "${ESC}u"

    /**
     * Clear entire current line.
     */
    fun clearCurrentLine(): String = "${ESC}2K"

}


/**
 * Update current line with new content, clearing previous content.
 */
fun updateLine(content: String): String =
    "${AnsiTerminal.cursorToStartOfLine()}${AnsiTerminal.clearLine()}$content"

/**
 * Update current line with colored content, clearing previous content.
 */
fun updateLine(content: String, color: Int): String =
    "${AnsiTerminal.cursorToStartOfLine()}${AnsiTerminal.clearLine()}${content.color(color)}"

/**
 * Move cursor up n lines, then update that line.
 */
fun updateLineAbove(content: String, linesUp: Int = 1): String =
    "${AnsiTerminal.cursorUp(linesUp)}${AnsiTerminal.cursorToStartOfLine()}${AnsiTerminal.clearLine()}$content"

/**
 * Move cursor up n lines, then update that line with color.
 */
fun updateLineAbove(content: String, linesUp: Int, color: Int): String =
    "${AnsiTerminal.cursorUp(linesUp)}${AnsiTerminal.cursorToStartOfLine()}${AnsiTerminal.clearLine()}${content.color(color)}"

/**
 * Move cursor to start of line and clear to end, ready for updating.
 */
fun startUpdate(): String = "${AnsiTerminal.cursorToStartOfLine()}${AnsiTerminal.clearLine()}"

/**
 * Calculate the visible width of a string, accounting for wide characters (CJK, emojis, etc.).
 * This is essential for correct cursor positioning in terminals where some characters occupy 2 columns.
 */
fun visibleWidth(text: String): Int {
    var width = 0
    text.codePoints().forEach { codePoint ->
        when {
            // Ignore control characters and null
            codePoint == 0 || codePoint < 32 || codePoint == 127 -> {}
            // East Asian Wide / Fullwidth / CJK / Hangul / etc.
            codePoint in 0x1100..0x115F || // Hangul Jamo
            codePoint in 0x2E80..0x9FFF || // CJK Radicals Supplement .. CJK Unified Ideographs Extension A
            codePoint in 0xAC00..0xD7AF || // Hangul Syllables
            codePoint in 0xF900..0xFAFF || // CJK Compatibility Ideographs
            codePoint in 0xFE00..0xFE0F || // Variation Selectors
            codePoint in 0xFF00..0xFF60 || // Fullwidth Forms
            codePoint in 0xFFE0..0xFFE6 -> width += 2
            else -> width += 1
        }
    }
    return width
}
