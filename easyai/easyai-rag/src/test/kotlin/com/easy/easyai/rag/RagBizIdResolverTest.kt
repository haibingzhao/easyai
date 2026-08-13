package com.easy.easyai.rag

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
    fun `globalBizId prefixes sanitized user id`() {
        assertEquals("u_alice", RagBizIdResolver.globalBizId("alice"))
    }

    @Test
    fun `globalBizId falls back to system tenant when user is absent`() {
        assertEquals("u_system", RagBizIdResolver.globalBizId(null))
        assertEquals("u_system", RagBizIdResolver.globalBizId("   "))
    }

    @Test
    fun `globalBizId caps total length at 26`() {
        val bizId = RagBizIdResolver.globalBizId("a".repeat(100))
        assertEquals(26, bizId.length)
        assertTrue(scopeCharset.matches(bizId))
    }

    // ── projectBizId ───────────────────────────────────────────────────

    @Test
    fun `projectBizId returns null without project path`() {
        assertNull(RagBizIdResolver.projectBizId("alice", null))
    }

    @Test
    fun `projectBizId combines user, last path segment and hash`() {
        val bizId = RagBizIdResolver.projectBizId("alice", Path.of("/tmp/demo-project"))
        assertTrue(bizId!!.startsWith("u_alice-demo-project-"), bizId)
        // 8-char hex hash suffix
        val hash = bizId.substringAfterLast('-')
        assertEquals(8, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' }, hash)
        assertTrue(scopeCharset.matches(bizId))
    }

    @Test
    fun `projectBizId is deterministic for the same absolute path`() {
        val a = RagBizIdResolver.projectBizId("alice", Path.of("/tmp/demo-project"))
        val b = RagBizIdResolver.projectBizId("alice", Path.of("/tmp/demo-project/"))
        assertEquals(a, b)
    }

    @Test
    fun `projectBizId distinguishes same-named folders at different paths`() {
        val a = RagBizIdResolver.projectBizId("alice", Path.of("/home/alice/work/demo"))
        val b = RagBizIdResolver.projectBizId("alice", Path.of("/home/bob/playground/demo"))
        // Same user and last segment, but different hash suffix
        assertTrue(a!!.startsWith("u_alice-demo-"), a)
        assertTrue(b!!.startsWith("u_alice-demo-"), b)
        assertNotEquals(a, b)
    }

    @Test
    fun `projectBizId sanitizes user and segment and stays within 64 chars`() {
        val bizId = RagBizIdResolver.projectBizId(
            "alice@corp.com",
            Path.of("/tmp/My Project (2026)/very-long-project-name-exceeding-limits")
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
}
