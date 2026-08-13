package com.easy.easyai.tools.file

import com.easy.easyai.core.permission.PermissionAction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asserts that file tool builders default-ALLOW the system temp dir (java.io.tmpdir):
 * the spill directory used by ToolResultGuard lives under it, so read/write tools must
 * access it without a permission prompt. User DENY rules can still override these defaults
 * (defaults are prepended to user rules, findLast wins).
 */
class FileToolBuilderDefaultRulesTest {

    private val tmpDir: String = System.getProperty("java.io.tmpdir") ?: "/tmp"

    @Test
    fun `read tool builders default-allow the system temp dir`() {
        val rules = ReadToolBuilder().defaultPermissionRules
        val tmpRule = rules.find { it.permission == "file.read.other" && it.action == PermissionAction.ALLOW }
        assertNotNull(tmpRule, "expected a file.read.other ALLOW rule, got: $rules")
        assertEquals(tmpDir, tmpRule.pattern, "tmp pattern must be the runtime java.io.tmpdir value")
        assertTrue(
            rules.any { it.permission == "file.read.project" && it.pattern == "*" && it.action == PermissionAction.ALLOW },
            "project read default must stay intact, got: $rules"
        )
    }

    @Test
    fun `write tool builders default-allow the system temp dir`() {
        for (builder in listOf(WriteToolBuilder(), EditToolBuilder())) {
            val rules = builder.defaultPermissionRules
            val tmpRule = rules.find { it.permission == "file.write.other" && it.action == PermissionAction.ALLOW }
            assertNotNull(tmpRule, "expected a file.write.other ALLOW rule, got: $rules")
            assertEquals(tmpDir, tmpRule.pattern, "tmp pattern must be the runtime java.io.tmpdir value")
            assertTrue(
                rules.any { it.permission == "file.write.project" && it.pattern == "*" && it.action == PermissionAction.ALLOW },
                "project write default must stay intact, got: $rules"
            )
        }
    }
}
