package com.easy.easyai.core.permission

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Tests for the normalized-path fallback in [PermissionService.evaluateFilePermission]:
 * paths under the system temp dir (java.io.tmpdir) are ALLOWed even when no string-prefix
 * rule matches, covering Windows backslash / trailing-slash / doubled-separator / dot-segment
 * mismatches that WildcardMatcher.matchesFilePath cannot handle. User DENY rules stay honored.
 */
class PermissionServiceTest {

    private val tmpDir: String = System.getProperty("java.io.tmpdir") ?: "/tmp"

    private val service = PermissionService(FakeRuleStore(), null)

    @Nested
    inner class `System temp dir fallback` {

        @Test
        fun `allows a path under the temp dir without any rule`() {
            val result = service.evaluateFilePermission(
                rules = emptyList(),
                projectPath = Path.of("/home/user/project"),
                arguments = mapOf("path" to Path.of(tmpDir, "easyai-tool-output", "call_x_1a2b3c4d.txt").toString()),
                read = true
            )
            assertEquals(PermissionAction.ALLOW, result.action)
            assertEquals("file.read.other", result.permission)
        }

        @Test
        fun `allows a write path under the temp dir without any rule`() {
            val result = service.evaluateFilePermission(
                rules = emptyList(),
                projectPath = Path.of("/home/user/project"),
                arguments = mapOf("path" to Path.of(tmpDir, "easyai-tool-output", "out.txt").toString()),
                read = false
            )
            assertEquals(PermissionAction.ALLOW, result.action)
            assertEquals("file.write.other", result.permission)
        }

        @Test
        fun `allows paths with trailing slash and doubled separators`() {
            // macOS java.io.tmpdir ends with '/'; naive concatenation can produce '//'.
            // On Windows the same shape arises with '\' — the fallback must normalize both.
            val base = tmpDir.trimEnd('/', '\\')
            val path = base + File.separator + File.separator + "easyai-tool-output" + File.separator + "data.txt"
            val result = service.evaluateFilePermission(
                rules = emptyList(),
                projectPath = Path.of("/home/user/project"),
                arguments = mapOf("path" to path),
                read = true
            )
            assertEquals(PermissionAction.ALLOW, result.action)
        }

        @Test
        fun `allows paths with redundant dot segments under the temp dir`() {
            val path = Path.of(tmpDir, "easyai-tool-output", "..", "easyai-tool-output", "data.txt").toString()
            val result = service.evaluateFilePermission(
                rules = emptyList(),
                projectPath = Path.of("/home/user/project"),
                arguments = mapOf("path" to path),
                read = true
            )
            assertEquals(PermissionAction.ALLOW, result.action)
        }

        @Test
        fun `keeps asking for paths outside the temp dir`() {
            val result = service.evaluateFilePermission(
                rules = emptyList(),
                projectPath = Path.of("/home/user/project"),
                arguments = mapOf("path" to Path.of(System.getProperty("user.home"), "documents", "notes.txt").toString()),
                read = true
            )
            assertEquals(PermissionAction.ASK, result.action)
            assertEquals("file.read.other", result.permission)
        }

        @Test
        fun `user deny rule on the temp dir stays honored`() {
            val rules = listOf(
                PermissionRule("file.read.other", tmpDir, PermissionAction.DENY)
            )
            val result = service.evaluateFilePermission(
                rules = rules,
                projectPath = Path.of("/home/user/project"),
                arguments = mapOf("path" to Path.of(tmpDir, "easyai-tool-output", "data.txt").toString()),
                read = true
            )
            assertEquals(PermissionAction.DENY, result.action)
            assertEquals("file.read.other", result.permission)
        }

        @Test
        fun `user allow rule still wins over the fallback`() {
            val rules = listOf(
                PermissionRule("file.read.other", tmpDir, PermissionAction.ALLOW)
            )
            val result = service.evaluateFilePermission(
                rules = rules,
                projectPath = Path.of("/home/user/project"),
                arguments = mapOf("path" to Path.of(tmpDir, "easyai-tool-output", "data.txt").toString()),
                read = true
            )
            assertEquals(PermissionAction.ALLOW, result.action)
        }
    }

    @Nested
    inner class `Project path regression` {

        @Test
        fun `project-scoped rule still applies inside the project`() {
            val projectPath = Path.of(System.getProperty("user.home"), "some-project")
            val rules = listOf(
                PermissionRule("file.read.project", "*", PermissionAction.ALLOW)
            )
            val result = service.evaluateFilePermission(
                rules = rules,
                projectPath = projectPath,
                arguments = mapOf("path" to projectPath.resolve("src/main.kt").toString()),
                read = true
            )
            assertEquals(PermissionAction.ALLOW, result.action)
            assertEquals("file.read.project", result.permission)
        }

        @Test
        fun `project path outside tmp dir is not affected by the fallback`() {
            val projectPath = Path.of(System.getProperty("user.home"), "some-project")
            val result = service.evaluateFilePermission(
                rules = emptyList(),
                projectPath = projectPath,
                arguments = mapOf("path" to projectPath.resolve("notes.txt").toString()),
                read = true
            )
            assertEquals(PermissionAction.ASK, result.action)
        }
    }

    /** Minimal fake RuleStore for constructing PermissionService in tests. */
    private class FakeRuleStore : PermissionRuleStore {
        override suspend fun loadRules(projectId: String): List<PermissionRule> = emptyList()
        override suspend fun saveRules(projectId: String, rules: List<PermissionRule>) {}
        override suspend fun addRule(projectId: String, rule: PermissionRule) {}
        override suspend fun deleteRule(projectId: String, permission: String, pattern: String) {}
    }
}
