package com.easy.easyai.core.permission

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionEvaluatorTest {

    @Nested
    inner class `WildcardMatcher matchesFilePath` {

        @Test
        fun `exact match works`() {
            assertTrue(WildcardMatcher.matchesFilePath("/tmp", "/tmp"))
            assertTrue(WildcardMatcher.matchesFilePath("/tmp/foo.txt", "/tmp/foo.txt"))
        }

        @Test
        fun `directory prefix matches child paths`() {
            assertTrue(WildcardMatcher.matchesFilePath("/tmp", "/tmp/backtest-iter-abc/train/config.json"))
            assertTrue(WildcardMatcher.matchesFilePath("/tmp", "/tmp/a"))
            assertTrue(WildcardMatcher.matchesFilePath("/data/models", "/data/models/weights.bin"))
        }

        @Test
        fun `directory prefix with trailing slash matches child paths`() {
            assertTrue(WildcardMatcher.matchesFilePath("/tmp/", "/tmp/foo/bar.json"))
        }

        @Test
        fun `directory prefix does not match sibling paths`() {
            assertFalse(WildcardMatcher.matchesFilePath("/tmp", "/tmpfoo"))
            assertFalse(WildcardMatcher.matchesFilePath("/tmp", "/tmp2/bar"))
            assertFalse(WildcardMatcher.matchesFilePath("/data", "/database/db.sqlite"))
        }

        @Test
        fun `wildcard patterns still work`() {
            assertTrue(WildcardMatcher.matchesFilePath("/tmp/*", "/tmp/foo/bar.json"))
            assertTrue(WildcardMatcher.matchesFilePath("*.json", "/tmp/config.json"))
            assertFalse(WildcardMatcher.matchesFilePath("/var/*", "/tmp/foo"))
        }

        @Test
        fun `wildcard patterns do not apply directory prefix logic`() {
            // Pattern contains wildcard — only wildcard matching applies, no prefix fallback
            assertFalse(WildcardMatcher.matchesFilePath("/data/*", "/tmp/foo"))
            assertFalse(WildcardMatcher.matchesFilePath("/tmp?", "/tmp/foo"))
        }
    }

    @Nested
    inner class `evaluateFilePath` {

        @Test
        fun `directory rule allows child path`() {
            val rules = listOf(
                PermissionRule("file.write.other", "/tmp", PermissionAction.ALLOW)
            )
            val result = PermissionEvaluator.evaluateFilePath(
                "file.write.other", "/tmp/backtest-iter-b8cf30f3/train/config.json", rules
            )
            assertEquals(PermissionAction.ALLOW, result.action)
        }

        @Test
        fun `directory rule allows exact directory path`() {
            val rules = listOf(
                PermissionRule("file.read.other", "/tmp", PermissionAction.ALLOW)
            )
            val result = PermissionEvaluator.evaluateFilePath("file.read.other", "/tmp", rules)
            assertEquals(PermissionAction.ALLOW, result.action)
        }

        @Test
        fun `directory rule does not match sibling path`() {
            val rules = listOf(
                PermissionRule("file.write.other", "/tmp", PermissionAction.ALLOW)
            )
            val result = PermissionEvaluator.evaluateFilePath("file.write.other", "/tmpdata/file.txt", rules)
            assertEquals(PermissionAction.ASK, result.action)
        }

        @Test
        fun `later deny rule overrides earlier allow`() {
            val rules = listOf(
                PermissionRule("file.write.other", "/tmp", PermissionAction.ALLOW),
                PermissionRule("file.write.other", "/tmp/secret/*", PermissionAction.DENY)
            )
            val denied = PermissionEvaluator.evaluateFilePath("file.write.other", "/tmp/secret/key.pem", rules)
            assertEquals(PermissionAction.DENY, denied.action)

            val allowed = PermissionEvaluator.evaluateFilePath("file.write.other", "/tmp/normal/file.txt", rules)
            assertEquals(PermissionAction.ALLOW, allowed.action)
        }

        @Test
        fun `no matching rule returns ASK default`() {
            val rules = listOf(
                PermissionRule("file.write.other", "/data", PermissionAction.ALLOW)
            )
            val result = PermissionEvaluator.evaluateFilePath("file.write.other", "/tmp/file.txt", rules)
            assertEquals(PermissionAction.ASK, result.action)
        }

        @Test
        fun `permission type must match`() {
            val rules = listOf(
                PermissionRule("file.read.other", "/tmp", PermissionAction.ALLOW)
            )
            val result = PermissionEvaluator.evaluateFilePath("file.write.other", "/tmp/file.txt", rules)
            assertEquals(PermissionAction.ASK, result.action)
        }
    }

    @Nested
    inner class `evaluateFilePermission with directory rules` {

        private val service = PermissionService(FakeRuleStore(), null)

        @Test
        fun `write to tmp subdirectory is allowed by tmp rule`() {
            val rules = listOf(
                PermissionRule("file.write.other", "/tmp", PermissionAction.ALLOW)
            )
            val result = service.evaluateFilePermission(
                rules,
                projectPath = java.nio.file.Path.of("/home/user/project"),
                arguments = mapOf("path" to "/tmp/backtest-iter-b8cf30f3-5feb-4e96-b48c-e154430df23f14258988934834001191/train/config.json"),
                read = false
            )
            assertEquals(PermissionAction.ALLOW, result.action)
            assertEquals("file.write.other", result.permission)
        }

        @Test
        fun `read from tmp subdirectory is allowed by tmp rule`() {
            val rules = listOf(
                PermissionRule("file.read.other", "/tmp", PermissionAction.ALLOW)
            )
            val result = service.evaluateFilePermission(
                rules,
                projectPath = java.nio.file.Path.of("/home/user/project"),
                arguments = mapOf("path" to "/tmp/some-dir/output.csv"),
                read = true
            )
            assertEquals(PermissionAction.ALLOW, result.action)
        }

        @Test
        fun `write outside allowed directory still asks`() {
            val rules = listOf(
                PermissionRule("file.write.other", "/tmp", PermissionAction.ALLOW)
            )
            val result = service.evaluateFilePermission(
                rules,
                projectPath = java.nio.file.Path.of("/home/user/project"),
                arguments = mapOf("path" to "/etc/passwd"),
                read = false
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
