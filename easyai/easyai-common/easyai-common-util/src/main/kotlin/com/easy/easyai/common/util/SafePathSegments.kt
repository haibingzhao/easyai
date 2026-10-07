package com.easy.easyai.common.util

private val SAFE_SEGMENT = Regex("[A-Za-z0-9_-]{1,64}")

/**
 * True when [value] can be used verbatim as a single filesystem path segment.
 *
 * Identifiers that reach a path (session ids, user ids) are often caller-supplied, so the check is
 * a whitelist rather than a normalisation step: anything containing a separator, a `..` sequence or
 * an unexpected character is rejected outright.
 */
fun isSafePathSegment(value: String?): Boolean = value != null && SAFE_SEGMENT.matches(value)
