package com.easy.easyai.common.util

const val DEFAULT_ELLIPSIS: String = "..."

/**
 * Trim a string to a maximum length, keeping the rightmost keepRight characters. Use
 * default ellipsis
 * @param s string to trim
 * @param max max length to return
 * @param keepRight number of characters to keep from the right
 * @return trimmed string
 */
fun trim(s: String?, max: Int, keepRight: Int, ellipsis: String = DEFAULT_ELLIPSIS): String? {
    require(max >= ellipsis.length + keepRight) { "max must be >= ellipsis.length() + keepRight" }
    if (s == null) {
        return null
    }
    if (s.length <= max) {
        return s
    }
    return s.substring(0, max - keepRight - ellipsis.length) + ellipsis + s.substring(s.length - keepRight)
}

fun removeWhitespace(s: String?): String? {
    return if (s == null) s else s.replace("\\s".toRegex(), "")
}
