package com.easy.easyai.core.permission

import com.easy.easyai.core.model.ProjectKind
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Nested
    inner class `Rule scope selection` {

        private val store = RecordingRuleStore()
        private val scopedService = PermissionService(store, null)

        @Test
        fun `group-backed temporary workspace reads the shared family bucket`() = runTest {
            store.rules[PermissionScope.groupDefault("g_family")] = listOf(
                PermissionRule("mcp.use", "*", PermissionAction.DENY)
            )

            val result = scopedService.evaluateWithContext(
                projectId = "tmp-session-1",
                projectPath = Path.of("/tmp/workspace"),
                toolName = "filesystem__read",
                arguments = emptyMap(),
                userId = "alice",
                projectKind = ProjectKind.TEMP,
                owners = setOf("alice", "g_family", "system")
            )

            assertEquals(PermissionAction.DENY, result.action)
            assertEquals(listOf(PermissionScope.groupDefault("g_family")), store.loadedScopes)
        }

        @Test
        fun `group-less temporary workspace reads the personal bucket`() = runTest {
            store.rules[PermissionScope.userDefault("alice")] = listOf(
                PermissionRule("mcp.use", "*", PermissionAction.DENY)
            )

            val result = scopedService.evaluateWithContext(
                projectId = "tmp-session-1",
                projectPath = Path.of("/tmp/workspace"),
                toolName = "filesystem__read",
                arguments = emptyMap(),
                userId = "alice",
                projectKind = ProjectKind.TEMP,
                owners = setOf("alice", "system")
            )

            assertEquals(PermissionAction.DENY, result.action)
            assertEquals(listOf(PermissionScope.userDefault("alice")), store.loadedScopes)
        }

        @Test
        fun `user project keeps its own bucket`() = runTest {
            store.rules["project-1"] = listOf(
                PermissionRule("mcp.use", "*", PermissionAction.DENY)
            )

            val result = scopedService.evaluateWithContext(
                projectId = "project-1",
                projectPath = Path.of("/home/alice/project"),
                toolName = "filesystem__read",
                arguments = emptyMap(),
                userId = "alice",
                projectKind = ProjectKind.USER
            )

            assertEquals(PermissionAction.DENY, result.action)
            assertEquals(listOf("project-1"), store.loadedScopes)
        }

        @Test
        fun `temporary workspace without defaults uses built-in rules`() = runTest {
            val result = scopedService.evaluateWithContext(
                projectId = "tmp-session-1",
                projectPath = Path.of("/tmp/workspace"),
                toolName = "filesystem__read",
                arguments = emptyMap(),
                userId = "alice",
                projectKind = ProjectKind.TEMP,
                owners = setOf("alice", "g_family", "system")
            )

            assertEquals(PermissionAction.ALLOW, result.action)
        }
    }

    @Nested
    inner class `Incremental setting update` {

        private val store = SavingRuleStore()
        private val settingsService = PermissionService(store, null)
        private val scope = "scope-1"

        @Test
        fun `a toggle preserves rules the settings UI cannot express`() = runTest {
            store.rules[scope] = listOf(
                PermissionRule("tool.execute.custom", "*", PermissionAction.ALLOW),
                PermissionRule("shell.other", "rm -rf /", PermissionAction.DENY),
                PermissionRule("mcp.use", "*", PermissionAction.ALLOW)
            )

            settingsService.updateSetting(scope, "useMcp", false)

            val saved = store.saved.getValue(scope)
            assertTrue(saved.any { it.permission == "tool.execute.custom" && it.action == PermissionAction.ALLOW })
            assertTrue(saved.any {
                it.permission == "shell.other" && it.pattern == "rm -rf /" && it.action == PermissionAction.DENY
            })
            assertEquals(1, saved.count { it.permission == "mcp.use" })
            assertTrue(saved.any { it.permission == "mcp.use" && it.pattern == "*" && it.action == PermissionAction.DENY })
        }

        @Test
        fun `a list update replaces its allow rules and preserves deny rules`() = runTest {
            store.rules[scope] = listOf(
                PermissionRule("file.read.other", "/old", PermissionAction.ALLOW),
                PermissionRule("file.read.other", "/secret", PermissionAction.DENY),
                PermissionRule("tool.execute.custom", "*", PermissionAction.ALLOW)
            )

            settingsService.updateSetting(scope, "readOtherPaths", listOf("/new"))

            val saved = store.saved.getValue(scope)
            assertEquals(
                listOf("/new"),
                saved.filter { it.permission == "file.read.other" && it.action == PermissionAction.ALLOW }
                    .map { it.pattern }
            )
            assertEquals(
                listOf("/secret"),
                saved.filter { it.permission == "file.read.other" && it.action == PermissionAction.DENY }
                    .map { it.pattern }
            )
            assertTrue(saved.any { it.permission == "tool.execute.custom" })
        }

        @Test
        fun `disabling a default-on toggle writes a wildcard deny and re-enabling removes it`() = runTest {
            settingsService.updateSetting(scope, "executeSafeCommands", false)
            assertTrue(store.saved.getValue(scope).any {
                it.permission == "shell.safe" && it.pattern == "*" && it.action == PermissionAction.DENY
            })

            settingsService.updateSetting(scope, "executeSafeCommands", true)
            assertFalse(store.saved.getValue(scope).any { it.permission == "shell.safe" })
        }

        @Test
        fun `other commands keep prefix matching semantics`() = runTest {
            settingsService.updateSetting(scope, "otherCommands", listOf("npm run", "git push*"))

            assertEquals(
                setOf("npm run*", "git push*"),
                store.saved.getValue(scope).map { it.pattern }.toSet()
            )
        }

        @Test
        fun `an unknown key does not rewrite the bucket`() = runTest {
            store.rules[scope] = listOf(PermissionRule("tool.execute.custom", "*", PermissionAction.ALLOW))

            settingsService.updateSetting(scope, "notARealSetting", true)

            assertTrue(store.saved.isEmpty())
        }
    }

    /** Rule store that records which bucket each evaluation read. */
    private class RecordingRuleStore : PermissionRuleStore {
        val rules = mutableMapOf<String, List<PermissionRule>>()
        val loadedScopes = mutableListOf<String>()

        override suspend fun loadRules(projectId: String): List<PermissionRule> {
            loadedScopes += projectId
            return rules[projectId].orEmpty()
        }

        override suspend fun saveRules(projectId: String, rules: List<PermissionRule>) {}
        override suspend fun addRule(projectId: String, rule: PermissionRule) {}
        override suspend fun deleteRule(projectId: String, permission: String, pattern: String) {}
        override suspend fun deleteProjectRules(projectId: String) {}
    }

    /** Rule store that keeps the saved rules so a follow-up update sees the previous write. */
    private class SavingRuleStore : PermissionRuleStore {
        val rules = mutableMapOf<String, List<PermissionRule>>()
        val saved = mutableMapOf<String, List<PermissionRule>>()

        override suspend fun loadRules(projectId: String): List<PermissionRule> =
            rules[projectId].orEmpty()

        override suspend fun saveRules(projectId: String, rules: List<PermissionRule>) {
            this.rules[projectId] = rules
            saved[projectId] = rules
        }

        override suspend fun addRule(projectId: String, rule: PermissionRule) {}
        override suspend fun deleteRule(projectId: String, permission: String, pattern: String) {}
        override suspend fun deleteProjectRules(projectId: String) {}
    }

    /** Minimal fake RuleStore for constructing PermissionService in tests. */
    private class FakeRuleStore : PermissionRuleStore {
        override suspend fun loadRules(projectId: String): List<PermissionRule> = emptyList()
        override suspend fun saveRules(projectId: String, rules: List<PermissionRule>) {}
        override suspend fun addRule(projectId: String, rule: PermissionRule) {}
        override suspend fun deleteRule(projectId: String, permission: String, pattern: String) {}
        override suspend fun deleteProjectRules(projectId: String) {}
    }
}
