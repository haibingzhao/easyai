package com.easy.easyai.core.permission

/**
 * Lightweight wildcard matcher supporting `*` (any characters) and `?` (single character).
 * Zero external dependencies — kept intentionally simple for permission rule matching.
 */
internal object WildcardMatcher {

    /**
     * Check if the given [text] matches the [pattern] with wildcard support.
     *
     * - `*` matches zero or more characters
     * - `?` matches exactly one character
     *
     * Examples:
     * - `matches("*.kt", "Main.kt")` → true
     * - `matches("rm -rf *", "rm -rf /tmp")` → true
     * - `matches("tool.?", "tool.a")` → true
     * - `matches("tool.?", "tool.ab")` → false
     */
    fun matches(pattern: String, text: String): Boolean {
        return matchInternal(pattern, 0, text, 0)
    }

    private fun matchInternal(pattern: String, pi: Int, text: String, ti: Int): Boolean {
        var p = pi
        var t = ti

        while (p < pattern.length && t < text.length) {
            when (val pc = pattern[p]) {
                '?' -> {
                    p++
                    t++
                }
                '*' -> {
                    // Skip consecutive *
                    while (p < pattern.length && pattern[p] == '*') p++
                    // Trailing * matches everything
                    if (p == pattern.length) return true
                    // Try matching rest of pattern at every position in text
                    for (i in t..text.length) {
                        if (matchInternal(pattern, p, text, i)) return true
                    }
                    return false
                }
                else -> {
                    if (pc != text[t]) return false
                    p++
                    t++
                }
            }
        }

        // Skip trailing * in pattern
        while (p < pattern.length && pattern[p] == '*') p++

        return p == pattern.length && t == text.length
    }
}
