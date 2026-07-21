package com.easy.easyai.tools

import java.nio.file.Path

/**
 * Resolve [pathStr] against this working directory safely.
 *
 * - Absolute paths are used as-is.
 * - Relative paths are resolved against this directory and normalised.
 * - A [SecurityException] is thrown if the result escapes this directory (path traversal guard).
 *
 * The receiver (this) **must** already be an absolute, normalised path
 * (ensured by [SpringToolFactory] via ToolBuilder at construction time).
 */
internal fun Path.resolveSafe(pathStr: String): Path {
    val resolved = Path.of(pathStr).takeIf { it.isAbsolute } ?: this.resolve(pathStr).normalize()
    if (!resolved.startsWith(this)) throw SecurityException("Path traversal attempt: $pathStr")
    return resolved
}
