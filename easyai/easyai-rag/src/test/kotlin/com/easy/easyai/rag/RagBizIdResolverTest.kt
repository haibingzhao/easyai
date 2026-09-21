package com.easy.easyai.rag

import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RagBizIdResolver]: charset sanitization, length bounds,
 * and hash-based disambiguation of same-named project folders.
 */
class RagBizIdResolverTest {

    /** EasyRAG scope charset: `^[a-zA-Z0-9_\-][a-zA-Z0-9_\-\.]{0,63}$`. */
    private val scopeCharset = Regex("^[a-zA-Z0-9_\\-][a-zA-Z0-9_\\-\\.]{0,63}$")

    // ── globalBizId ────────────────────────────────────────────────────

    @Test
    fun `globalBizId prefixes sanitized user id with content type`() {
        assertEquals("u_alice_m", RagBizIdResolver.globalBizId("alice", RagBizIdResolver.MEMORY_TYPE))
        assertEquals("u_alice_k", RagBizIdResolver.globalBizId("alice", RagBizIdResolver.KNOWLEDGE_TYPE))
    }

    @Test
    fun `globalBizId falls back to system tenant when user is absent`() {
        assertEquals("u_system_m", RagBizIdResolver.globalBizId(null, RagBizIdResolver.MEMORY_TYPE))
        assertEquals("u_system_k", RagBizIdResolver.globalBizId("   ", RagBizIdResolver.KNOWLEDGE_TYPE))
    }

    @Test
    fun `globalBizId caps base length at 26 plus content type suffix`() {
        val bizId = RagBizIdResolver.globalBizId("a".repeat(100), RagBizIdResolver.MEMORY_TYPE)
        // base = u_ + 24 chars = 26, + _m = 28
        assertEquals(28, bizId.length)
        assertTrue(scopeCharset.matches(bizId))
    }

    // ── projectBizId ───────────────────────────────────────────────────

    @Test
    fun `projectBizId returns null without project path`() {
        assertNull(RagBizIdResolver.projectBizId("alice", null, RagBizIdResolver.MEMORY_TYPE))
    }

    @Test
    fun `projectBizId combines user, last path segment, hash and content type`() {
        val bizId = RagBizIdResolver.projectBizId("alice", Path.of("/tmp/demo-project"), RagBizIdResolver.MEMORY_TYPE)
        assertTrue(bizId!!.startsWith("u_alice-demo-project-"), bizId)
        assertTrue(bizId.endsWith("_m"), bizId)
        // 8-char hex hash suffix + _m
        val beforeSuffix = bizId.substringBeforeLast("_m").substringAfterLast('-')
        assertEquals(8, beforeSuffix.length)
        assertTrue(beforeSuffix.all { it in '0'..'9' || it in 'a'..'f' }, beforeSuffix)
        assertTrue(scopeCharset.matches(bizId))
    }

    @Test
    fun `projectBizId is deterministic for the same absolute path`() {
        val a = RagBizIdResolver.projectBizId("alice", Path.of("/tmp/demo-project"), RagBizIdResolver.KNOWLEDGE_TYPE)
        val b = RagBizIdResolver.projectBizId("alice", Path.of("/tmp/demo-project/"), RagBizIdResolver.KNOWLEDGE_TYPE)
        assertEquals(a, b)
    }

    @Test
    fun `projectBizId distinguishes same-named folders at different paths`() {
        val a = RagBizIdResolver.projectBizId("alice", Path.of("/home/alice/work/demo"), RagBizIdResolver.MEMORY_TYPE)
        val b = RagBizIdResolver.projectBizId("alice", Path.of("/home/bob/playground/demo"), RagBizIdResolver.MEMORY_TYPE)
        // Same user and last segment, but different hash suffix
        assertTrue(a!!.startsWith("u_alice-demo-"), a)
        assertTrue(b!!.startsWith("u_alice-demo-"), b)
        assertNotEquals(a, b)
    }

    @Test
    fun `projectBizId distinguishes memory and knowledge for same scope`() {
        val m = RagBizIdResolver.projectBizId("alice", Path.of("/tmp/demo"), RagBizIdResolver.MEMORY_TYPE)
        val k = RagBizIdResolver.projectBizId("alice", Path.of("/tmp/demo"), RagBizIdResolver.KNOWLEDGE_TYPE)
        assertTrue(m!!.endsWith("_m"))
        assertTrue(k!!.endsWith("_k"))
        assertNotEquals(m, k)
    }

    @Test
    fun `projectBizId sanitizes user and segment and stays within 64 chars`() {
        val bizId = RagBizIdResolver.projectBizId(
            "alice@corp.com",
            Path.of("/tmp/My Project (2026)/very-long-project-name-exceeding-limits"),
            RagBizIdResolver.MEMORY_TYPE
        )
        assertTrue(bizId!!.length <= 64, "length=${bizId.length}")
        assertTrue(scopeCharset.matches(bizId), bizId)
    }

    // ── sanitize ───────────────────────────────────────────────────────

    @Test
    fun `sanitize replaces characters outside the scope charset`() {
        assertEquals("a_b-c.d", RagBizIdResolver.sanitize("a/b-c.d"))
        assertEquals("user_corp_", RagBizIdResolver.sanitize("user@corp#"))
        assertNull(RagBizIdResolver.sanitize(null))
        assertNull(RagBizIdResolver.sanitize(""))
    }

    // ── skill slices ───────────────────────────────────────────────────

    @Test
    fun `skill slices live in the same family as memory and knowledge`() {
        assertEquals("u_alice_s", RagBizIdResolver.globalBizId("alice", RagBizIdResolver.SKILL_TYPE))
    }

    @Test
    fun `an absent user degrades to the system tenant slice`() {
        // Single-user desktop/dev deployments degrade to this slice; it must stay addressable.
        assertEquals("u_system_s", RagBizIdResolver.globalBizId(null, RagBizIdResolver.SKILL_TYPE))
    }

    @Test
    fun `skillBizIds returns both slices when a project path exists`() {
        val owner = SkillOwnerContext(userId = "alice", projectPath = Path.of("/tmp/demo-project"))

        val bizIds = RagBizIdResolver.skillBizIds(listOf(SkillScope.GLOBAL, SkillScope.PROJECT), owner)

        assertEquals(2, bizIds.size)
        assertEquals("u_alice_s", bizIds.first())
        assertTrue(bizIds[1].startsWith("u_alice-demo-project-"), bizIds[1])
        assertTrue(bizIds.all { scopeCharset.matches(it) }, bizIds.toString())
    }

    @Test
    fun `skillBizIds drops the project slice when there is no project path`() {
        val owner = SkillOwnerContext(userId = "alice")

        assertEquals(
            listOf("u_alice_s"),
            RagBizIdResolver.skillBizIds(listOf(SkillScope.GLOBAL, SkillScope.PROJECT), owner)
        )
    }

    @Test
    fun `skillScopeOf maps a slice back to its granularity`() {
        val projectPath = Path.of("/tmp/demo-project")
        val owner = SkillOwnerContext(userId = "alice", projectPath = projectPath)

        assertEquals(
            SkillScope.GLOBAL,
            RagBizIdResolver.skillScopeOf(RagBizIdResolver.globalBizId("alice", RagBizIdResolver.SKILL_TYPE), owner)
        )
        assertEquals(
            SkillScope.PROJECT,
            RagBizIdResolver.skillScopeOf(
                RagBizIdResolver.projectBizId("alice", projectPath, RagBizIdResolver.SKILL_TYPE), owner
            )
        )
    }

    @Test
    fun `skillScopeOf refuses to label a foreign slice as one of ours`() {
        val owner = SkillOwnerContext(userId = "alice", projectPath = Path.of("/tmp/demo-project"))

        assertNull(RagBizIdResolver.skillScopeOf(null, owner))
        // another tenant's slice must never resolve to a scope for this owner
        assertNull(RagBizIdResolver.skillScopeOf("u_bob_s", owner))
        // the same path owned by somebody else implies a different slice
        assertNull(RagBizIdResolver.skillScopeOf("u_alice-demo-project-00000000_s", owner))
    }

    @Test
    fun `project skill slices of different users never collide`() {
        val path = Path.of("/shared/team-project")
        val alice = RagBizIdResolver.projectBizId("alice", path, RagBizIdResolver.SKILL_TYPE)
        val bob = RagBizIdResolver.projectBizId("bob", path, RagBizIdResolver.SKILL_TYPE)

        assertNotEquals(alice, bob)
        assertTrue(scopeCharset.matches(alice!!))
        assertTrue(scopeCharset.matches(bob!!))
    }
}
