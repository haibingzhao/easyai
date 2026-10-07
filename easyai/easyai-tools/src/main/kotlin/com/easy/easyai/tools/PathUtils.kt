package com.easy.easyai.tools

import com.easy.easyai.common.util.isSafePathSegment
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("com.easy.easyai.tools.PathUtils")

/**
 * Working directory for tools whose context carries no project path (swarm runs, CLI one-shots,
 * requests without a session). Resolves to a scratch directory under the JVM temp root so an
 * unscoped agent can never land in the backend process directory.
 *
 * [sessionId] narrows the scratch directory to a single session. Session ids reach the backend from
 * the client, so only a whitelisted id becomes a path segment; anything else falls back to the
 * shared root.
 */
internal fun fallbackWorkDir(sessionId: String? = null): Path {
    val root = Path.of(System.getProperty("java.io.tmpdir") ?: "/tmp", "easyai-workspace")
    val workDir = sessionId?.takeIf { isSafePathSegment(it) }?.let { root.resolve(it) } ?: root
    try {
        Files.createDirectories(workDir)
    } catch (e: IOException) {
        // Return the path anyway: the tool reports the missing directory instead of silently
        // writing somewhere else.
        logger.warn("Failed to create fallback work directory {}: {}", workDir, e.message)
    }
    return workDir
}

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
