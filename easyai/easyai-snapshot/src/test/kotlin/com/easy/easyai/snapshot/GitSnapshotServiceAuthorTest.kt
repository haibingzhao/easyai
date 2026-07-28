package com.easy.easyai.snapshot

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the agent-ID encoding/decoding helpers in [GitSnapshotService].
 *
 * These helpers encode the agent ID into the git author name (`"EasyAI Agent::<agentId>"`)
 * so the per-commit view can attribute LLM commits to a specific agent, while keeping the
 * author email (and thus the email-based llm/user determination) unchanged.
 */
class GitSnapshotServiceAuthorTest {

    private val baseName = ChangeAuthor.LLM_AGENT.gitAuthorName

    @Nested
    inner class `llmAuthorName encoding` {

        @Test
        fun `encodes agent id with separator`() {
            assertEquals("$baseName::agent-x", GitSnapshotService.llmAuthorName("agent-x"))
        }

        @Test
        fun `returns base name when agent id is null`() {
            assertEquals(baseName, GitSnapshotService.llmAuthorName(null))
        }

        @Test
        fun `returns base name when agent id is blank`() {
            assertEquals(baseName, GitSnapshotService.llmAuthorName("   "))
        }

        @Test
        fun `sanitizes characters unsafe for git log parsing`() {
            val name = GitSnapshotService.llmAuthorName("a|b<c>d\ne\rf")
            // None of the unsafe characters may survive (would break `|` split / line parsing)
            assertTrue("|" !in name, "pipe must be sanitized")
            assertTrue("<" !in name, "'<' must be sanitized")
            assertTrue(">" !in name, "'>' must be sanitized")
            assertTrue("\n" !in name, "newline must be sanitized")
            assertTrue("\r" !in name, "carriage return must be sanitized")
            assertEquals("$baseName::a-b-c-d-e-f", name)
        }
    }

    @Nested
    inner class `parseAgentId decoding` {

        @Test
        fun `round-trips a plain agent id`() {
            assertEquals("agent-x", GitSnapshotService.parseAgentId(GitSnapshotService.llmAuthorName("agent-x")))
        }

        @Test
        fun `returns null for legacy name without separator`() {
            assertNull(GitSnapshotService.parseAgentId(baseName))
        }

        @Test
        fun `returns null for user commit name`() {
            assertNull(GitSnapshotService.parseAgentId(ChangeAuthor.USER.gitAuthorName))
        }

        @Test
        fun `reconstructs agent id that itself contains the separator`() {
            // substringAfter takes everything after the FIRST separator, so an agent id
            // containing "::" is reconstructed intact.
            val encoded = GitSnapshotService.llmAuthorName("foo::bar")
            assertEquals("$baseName::foo::bar", encoded)
            assertEquals("foo::bar", GitSnapshotService.parseAgentId(encoded))
        }

        @Test
        fun `round-trips sanitized agent id`() {
            val original = "a|b<c>d"
            val encoded = GitSnapshotService.llmAuthorName(original)
            assertEquals("a-b-c-d", GitSnapshotService.parseAgentId(encoded))
        }
    }
}
