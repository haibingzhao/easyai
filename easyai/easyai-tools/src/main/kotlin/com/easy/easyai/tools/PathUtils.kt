package com.easy.easyai.tools

import java.nio.file.Path

/**
 * Resolve [pathStr] against this working directory safely.
 *
 * - Absolute paths are normalised and returned directly (authorisation is handled
 *   by the permission system before tool execution).
 * - Relative paths are resolved against this directory, normalised, and checked
 *   to prevent `../` traversal outside the project boundary.
 *
 * The receiver (this) should be an absolute path; it is normalised defensively.
 */
internal fun Path.resolveSafe(pathStr: String): Path {
    val input = Path.of(pathStr)
    if (input.isAbsolute) {
        return input.normalize()
    }
    val normalizedRoot = this.toAbsolutePath().normalize()
    val resolved = normalizedRoot.resolve(pathStr).normalize()
    if (!resolved.startsWith(normalizedRoot)) throw SecurityException("Path traversal attempt: $pathStr")
    return resolved
}
