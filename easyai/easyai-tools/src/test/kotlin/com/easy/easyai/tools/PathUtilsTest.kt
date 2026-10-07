package com.easy.easyai.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Covers the scratch directory used when a tool context carries no project path. Session ids reach
 * the backend from the client, so only a whitelisted id may become a path segment.
 */
class PathUtilsTest {

    private val root: Path = Path.of(System.getProperty("java.io.tmpdir") ?: "/tmp", "easyai-workspace")

    @Test
    fun `a safe session id gets its own scratch directory`() {
        val sessionId = "s-${UUID.randomUUID()}"
        val workDir = fallbackWorkDir(sessionId)

        assertEquals(root.resolve(sessionId), workDir)
        assertTrue(Files.isDirectory(workDir), "the directory is created on demand")

        workDir.toFile().deleteRecursively()
    }

    @Test
    fun `an unusable session id falls back to the shared root`() {
        assertEquals(root, fallbackWorkDir("../../etc"))
        assertEquals(root, fallbackWorkDir("a/b"))
        assertEquals(root, fallbackWorkDir("x".repeat(65)))
        assertEquals(root, fallbackWorkDir(null))
        assertTrue(Files.isDirectory(root))
    }
}
