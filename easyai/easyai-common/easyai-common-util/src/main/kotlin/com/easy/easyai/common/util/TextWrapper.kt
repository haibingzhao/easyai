package com.easy.easyai.common.util

/**
 * Terminal text wrapping utilities inspired by pi-mono TUI.
 * Handles ANSI escape codes, Unicode grapheme clusters, and wide characters.
 */
object TextWrapper {

    /**
     * Wrap text into display lines based on terminal width.
     * Each returned line will have visible width <= maxWidth.
     *
     * @param text Input text (may contain \n and ANSI codes)
     * @param maxWidth Maximum visible width per line
     * @return List of wrapped display lines
     */
    fun wrapText(text: String, maxWidth: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        if (maxWidth <= 0) return listOf("")

        // Split by literal newlines, process each line separately
        val inputLines = text.split('\n')
        val result = mutableListOf<String>()
        val ansiTracker = AnsiStateTracker()

        inputLines.forEachIndexed { index, inputLine ->
            // Prepend active ANSI codes from previous lines (except first line)
            val prefix = if (result.isNotEmpty()) ansiTracker.getActiveCodes() else ""
            val wrappedLines = wrapSingleLine(prefix + inputLine, maxWidth, ansiTracker)
            result.addAll(wrappedLines)
            // Update tracker with codes from this line for next iteration
            updateTrackerFromText(inputLine, ansiTracker)
        }

        return result.ifEmpty { listOf("") }
    }

    /**
     * Calculate the number of display lines for text at given width.
     * This accounts for both literal newlines and automatic line wrapping.
     *
     * @param text Input text
     * @param width Terminal width
     * @return Number of display lines
     */
    fun calculateDisplayLines(text: String, width: Int): Int {
        return wrapText(text, width).size
    }

    /**
     * Wrap a single logical line (no \n) into multiple display lines.
     */
    private fun wrapSingleLine(line: String, width: Int, tracker: AnsiStateTracker): List<String> {
        if (line.isEmpty()) return listOf("")

        val visibleLength = visibleWidthWithAnsi(line)
        if (visibleLength <= width) {
            return listOf(line)
        }

        val wrapped = mutableListOf<String>()
        val tokens = splitIntoTokensWithAnsi(line)

        var currentLine = ""
        var currentVisibleLength = 0

        for (token in tokens) {
            val tokenVisibleLength = visibleWidthWithAnsi(token)
            val isWhitespace = token.trim().isEmpty()

            // Token itself is too long - break it character by character
            if (tokenVisibleLength > width && !isWhitespace) {
                if (currentLine.isNotEmpty()) {
                    val lineEndReset = tracker.getLineEndReset()
                    if (lineEndReset.isNotEmpty()) {
                        currentLine += lineEndReset
                    }
                    wrapped.add(currentLine)
                }

                // Break long token
                val broken = breakLongToken(token, width, tracker)
                wrapped.addAll(broken.dropLast(1))
                currentLine = broken.last()
                currentVisibleLength = visibleWidthWithAnsi(currentLine)
                continue
            }

            // Check if adding this token would exceed width
            val totalNeeded = currentVisibleLength + tokenVisibleLength

            if (totalNeeded > width && currentVisibleLength > 0) {
                // Trim trailing whitespace, then add reset if needed
                var lineToWrap = currentLine.trimEnd()
                val lineEndReset = tracker.getLineEndReset()
                if (lineEndReset.isNotEmpty()) {
                    lineToWrap += lineEndReset
                }
                wrapped.add(lineToWrap)

                if (isWhitespace) {
                    // Don't start new line with whitespace
                    currentLine = tracker.getActiveCodes()
                    currentVisibleLength = 0
                } else {
                    currentLine = tracker.getActiveCodes() + token
                    currentVisibleLength = tokenVisibleLength
                }
            } else {
                // Add to current line
                currentLine += token
                currentVisibleLength += tokenVisibleLength
            }

            updateTrackerFromText(token, tracker)
        }

        if (currentLine.isNotEmpty()) {
            wrapped.add(currentLine)
        }

        // Trim trailing whitespace from all lines
        return wrapped.map { it.trimEnd() }.ifEmpty { listOf("") }
    }

    /**
     * Split text into tokens (words + whitespace) while keeping ANSI codes attached to their content.
     */
    private fun splitIntoTokensWithAnsi(text: String): List<String> {
        val tokens = mutableListOf<String>()
        var current = ""
        var pendingAnsi = ""
        var inWhitespace = false
        var i = 0

        while (i < text.length) {
            val ansiCode = extractAnsiCode(text, i)
            if (ansiCode != null) {
                // Hold ANSI codes separately - they'll be attached to the next visible char
                pendingAnsi += ansiCode
                i += ansiCode.length
                continue
            }

            val char = text[i]
            val charIsSpace = char == ' '

            if (charIsSpace != inWhitespace && current.isNotEmpty()) {
                // Switching between whitespace and non-whitespace, push current token
                tokens.add(current)
                current = ""
            }

            // Attach any pending ANSI codes to this visible character
            if (pendingAnsi.isNotEmpty()) {
                current += pendingAnsi
                pendingAnsi = ""
            }

            inWhitespace = charIsSpace
            current += char
            i++
        }

        // Handle any remaining pending ANSI codes
        if (pendingAnsi.isNotEmpty()) {
            current += pendingAnsi
        }

        if (current.isNotEmpty()) {
            tokens.add(current)
        }

        return tokens
    }

    /**
     * Break a long token (word) that exceeds width into multiple lines.
     */
    private fun breakLongToken(token: String, width: Int, tracker: AnsiStateTracker): List<String> {
        val lines = mutableListOf<String>()
        var currentLine = tracker.getActiveCodes()
        var currentWidth = 0

        // Separate ANSI codes from visible content
        val segments = mutableListOf<Pair<Boolean, String>>() // (isAnsi, content)
        var i = 0

        while (i < token.length) {
            val ansiCode = extractAnsiCode(token, i)
            if (ansiCode != null) {
                segments.add(Pair(true, ansiCode))
                i += ansiCode.length
            } else {
                // Find the next ANSI code or end of string
                var end = i
                while (end < token.length && extractAnsiCode(token, end) == null) {
                    end++
                }
                // Add as single character segments for precise width control
                val textPortion = token.substring(i, end)
                for (char in textPortion) {
                    segments.add(Pair(false, char.toString()))
                }
                i = end
            }
        }

        // Process segments
        for ((isAnsi, content) in segments) {
            if (isAnsi) {
                currentLine += content
                tracker.processAnsiCode(content)
                continue
            }

            val charWidth = visibleWidthWithAnsi(content)

            if (currentWidth + charWidth > width) {
                // Add reset for underline only (preserves background)
                val lineEndReset = tracker.getLineEndReset()
                if (lineEndReset.isNotEmpty()) {
                    currentLine += lineEndReset
                }
                lines.add(currentLine)
                currentLine = tracker.getActiveCodes()
                currentWidth = 0
            }

            currentLine += content
            currentWidth += charWidth
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        return lines.ifEmpty { listOf("") }
    }

    /**
     * Extract ANSI escape sequence at given position.
     * Returns the ANSI code string or null if not an ANSI sequence.
     */
    private fun extractAnsiCode(str: String, pos: Int): String? {
        if (pos >= str.length || str[pos] != '\u001b') return null

        if (pos + 1 >= str.length) return null
        val next = str[pos + 1]

        // CSI sequence: ESC [ ... m/G/K/H/J
        if (next == '[') {
            var j = pos + 2
            while (j < str.length && !str[j].toString().matches(Regex("[mGKHJ]"))) j++
            if (j < str.length) {
                return str.substring(pos, j + 1)
            }
            return null
        }

        // OSC sequence: ESC ] ... BEL or ESC ] ... ST (ESC \)
        if (next == ']') {
            var j = pos + 2
            while (j < str.length) {
                if (str[j] == '\u0007') return str.substring(pos, j + 1)
                if (str[j] == '\u001b' && j + 1 < str.length && str[j + 1] == '\\') {
                    return str.substring(pos, j + 2)
                }
                j++
            }
            return null
        }

        // APC sequence: ESC _ ... BEL or ESC _ ... ST (ESC \)
        if (next == '_') {
            var j = pos + 2
            while (j < str.length) {
                if (str[j] == '\u0007') return str.substring(pos, j + 1)
                if (str[j] == '\u001b' && j + 1 < str.length && str[j + 1] == '\\') {
                    return str.substring(pos, j + 2)
                }
                j++
            }
            return null
        }

        return null
    }

    /**
     * Calculate visible width of text, stripping ANSI codes but counting wide chars.
     */
    fun visibleWidthWithAnsi(text: String): Int {
        if (text.isEmpty()) return 0

        // Fast path: pure ASCII printable without ANSI
        if (isPrintableAscii(text) && !text.contains('\u001b')) {
            return text.length
        }

        // Strip ANSI codes and calculate width
        var clean = text
        if (clean.contains('\t')) {
            clean = clean.replace("\t", "   ")
        }

        // Remove ANSI escape sequences
        if (clean.contains('\u001b')) {
            val stripped = buildString {
                var i = 0
                while (i < clean.length) {
                    val ansiCode = extractAnsiCode(clean, i)
                    if (ansiCode != null) {
                        i += ansiCode.length
                    } else {
                        append(clean[i])
                        i++
                    }
                }
            }
            clean = stripped
        }

        // Calculate width using existing visibleWidth function
        return visibleWidth(clean)
    }

    /**
     * Check if string contains only printable ASCII characters.
     */
    private fun isPrintableAscii(str: String): Boolean {
        for (char in str) {
            val code = char.code
            if (code < 0x20 || code > 0x7e) {
                return false
            }
        }
        return true
    }

    /**
     * Update ANSI state tracker by processing all ANSI codes in text.
     */
    private fun updateTrackerFromText(text: String, tracker: AnsiStateTracker) {
        var i = 0
        while (i < text.length) {
            val ansiCode = extractAnsiCode(text, i)
            if (ansiCode != null) {
                tracker.processAnsiCode(ansiCode)
                i += ansiCode.length
            } else {
                i++
            }
        }
    }

    /**
     * Track active ANSI SGR codes to preserve styling across line breaks.
     */
    private class AnsiStateTracker {
        private var bold = false
        private var dim = false
        private var italic = false
        private var underline = false
        private var blink = false
        private var inverse = false
        private var hidden = false
        private var strikethrough = false
        private var fgColor: String? = null
        private var bgColor: String? = null
        private var activeHyperlink: String? = null

        fun processAnsiCode(ansiCode: String) {
            // OSC 8 hyperlink: \x1b]8;;<url>\x1b\\ (open) or \x1b]8;;\x1b\\ (close)
            if (ansiCode.startsWith("\u001b]8;")) {
                val match = Regex("""^\u001b]8;[^;]*;([^\u001b\u0007]*)""").find(ansiCode)
                activeHyperlink = match?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
                return
            }

            if (!ansiCode.endsWith("m")) return

            // Extract parameters between \x1b[ and m
            val match = Regex("""\u001b\[([\d;]*)m""").find(ansiCode)
            if (match == null) return

            val params = match.groupValues[1]
            if (params.isEmpty() || params == "0") {
                reset()
                return
            }

            // Parse parameters
            val parts = params.split(";")
            var i = 0
            while (i < parts.size) {
                val codeStr = parts[i]
                val code = codeStr.toIntOrNull()
                if (code == null) {
                    i++
                    continue
                }

                // Handle 256-color and RGB codes
                if (code == 38 || code == 48) {
                    if (i + 1 < parts.size && parts[i + 1] == "5" && i + 2 < parts.size) {
                        // 256 color: 38;5;N or 48;5;N
                        val colorCode = "${parts[i]};${parts[i + 1]};${parts[i + 2]}"
                        if (code == 38) fgColor = colorCode else bgColor = colorCode
                        i += 3
                        continue
                    } else if (i + 1 < parts.size && parts[i + 1] == "2" && i + 4 < parts.size) {
                        // RGB color: 38;2;R;G;B or 48;2;R;G;B
                        val colorCode = "${parts[i]};${parts[i + 1]};${parts[i + 2]};${parts[i + 3]};${parts[i + 4]}"
                        if (code == 38) fgColor = colorCode else bgColor = colorCode
                        i += 5
                        continue
                    }
                }

                when (code) {
                    0 -> reset()
                    1 -> bold = true
                    2 -> dim = true
                    3 -> italic = true
                    4 -> underline = true
                    5 -> blink = true
                    7 -> inverse = true
                    8 -> hidden = true
                    9 -> strikethrough = true
                    21 -> bold = false
                    22 -> { bold = false; dim = false }
                    23 -> italic = false
                    24 -> underline = false
                    25 -> blink = false
                    27 -> inverse = false
                    28 -> hidden = false
                    29 -> strikethrough = false
                    39 -> fgColor = null
                    49 -> bgColor = null
                    else -> {
                        // Standard foreground colors 30-37, 90-97
                        if ((code in 30..37) || (code in 90..97)) {
                            fgColor = code.toString()
                        }
                        // Standard background colors 40-47, 100-107
                        else if ((code in 40..47) || (code in 100..107)) {
                            bgColor = code.toString()
                        }
                    }
                }
                i++
            }
        }

        private fun reset() {
            bold = false
            dim = false
            italic = false
            underline = false
            blink = false
            inverse = false
            hidden = false
            strikethrough = false
            fgColor = null
            bgColor = null
        }

        fun getActiveCodes(): String {
            val codes = mutableListOf<String>()
            if (bold) codes.add("1")
            if (dim) codes.add("2")
            if (italic) codes.add("3")
            if (underline) codes.add("4")
            if (blink) codes.add("5")
            if (inverse) codes.add("7")
            if (hidden) codes.add("8")
            if (strikethrough) codes.add("9")
            if (fgColor != null) codes.add(fgColor!!)
            if (bgColor != null) codes.add(bgColor!!)

            var result = if (codes.isNotEmpty()) "\u001b[${codes.joinToString(";")}m" else ""
            if (activeHyperlink != null) {
                result += "\u001b]8;;$activeHyperlink\u001b\\"
            }
            return result
        }

        fun hasActiveCodes(): Boolean {
            return bold || dim || italic || underline || blink || inverse || hidden ||
                   strikethrough || fgColor != null || bgColor != null || activeHyperlink != null
        }

        /**
         * Get reset codes for attributes that need to be turned off at line end.
         * Underline must be closed to prevent bleeding into padding.
         */
        fun getLineEndReset(): String {
            var result = ""
            if (underline) {
                result += "\u001b[24m" // Underline off only
            }
            if (activeHyperlink != null) {
                result += "\u001b]8;;\u001b\\" // Close hyperlink
            }
            return result
        }
    }
}
