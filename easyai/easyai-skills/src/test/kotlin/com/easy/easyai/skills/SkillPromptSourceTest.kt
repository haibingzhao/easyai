package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillSyncState
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillPromptSourceTest {
    private val f = SkillModelFixture()
    private val global = f.skill("review")
    private val local = f.skill("review", f.project)

    private fun source(rag: Boolean = false, inject: Boolean = true, ready: Boolean = rag) = SkillPromptSource(
        f.registry, f.catalog, inject, rag, ready, f.config
    )

    @Nested
    inner class EffectiveView {
        @Test
        fun `project override is selected before disabled filtering`() = runTest {
            f.skills = listOf(global, local)
            f.rows = listOf(f.row(global, "system"), f.row(local, enabled = false))
            val prompt = source()
            assertTrue(prompt.skillsForPrompt("alice", f.project, listOf("review")).isEmpty())
            assertEquals("Does review tasks", prompt.skillsForPrompt("alice", null, listOf("review")).single()["description"])
        }

        @Test
        fun `whitelist is required and unrelated projects never appear`() = runTest {
            val other = f.skill("private", f.project.resolve("child"))
            f.skills = listOf(global, other)
            f.rows = listOf(f.row(global, "system"), f.row(other))
            val prompt = source()
            assertTrue(prompt.skillsForPrompt("alice", f.project).isEmpty())
            coVerify(exactly = 0) { f.catalog.listByUser(any()) }
            assertEquals(listOf("review"), prompt.skillsForPrompt("alice", f.project, listOf("review", "private")).map { it["name"] })
        }

        @Test
        fun `toggle and owner changes are visible without refresh`() = runTest {
            f.skills = listOf(global)
            f.rows = listOf(f.row(global, "system"))
            val prompt = source()
            assertEquals(1, prompt.skillsForPrompt("alice", null, listOf("review")).size)
            f.rows = f.rows + f.row(global, enabled = false)
            assertTrue(prompt.skillsForPrompt("alice", null, listOf("review")).isEmpty())
            assertEquals(1, prompt.skillsForPrompt("bob", null, listOf("review")).size)
        }

        @Test
        fun `catalog failure propagates on first and subsequent reads`() = runTest {
            f.skills = listOf(global)
            f.rows = listOf(f.row(global))
            val prompt = source()
            prompt.skillsForPrompt("alice", null, listOf("review"))
            coEvery { f.catalog.listByUser("alice") } throws IllegalStateException("catalog down")
            assertFailsWith<IllegalStateException> { prompt.skillsForPrompt("alice", null, listOf("review")) }
            assertFailsWith<IllegalStateException> { source().skillsForPrompt("alice", null, listOf("review")) }
        }

        @Test
        fun `cancellation propagates`() = runTest {
            coEvery { f.catalog.listByUser(any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { source().skillsForPrompt("alice", null, listOf("review")) }
        }

        @Test
        fun `without catalog only explicit globals are advertised`() = runTest {
            f.skills = listOf(global, local, f.skill("private", f.project))
            val prompt = SkillPromptSource(f.registry, null, true, false, config = f.config)
            assertEquals(listOf("review"), prompt.skillsForPrompt("alice", f.project, listOf("review", "private")).map { it["name"] })
        }
    }

    @Nested
    inner class DiscoveryReadiness {
        @Test
        fun `store availability alone never suppresses pending or stale skills`() = runTest {
            f.skills = listOf(global)
            val prompt = source(rag = true)
            assertTrue(prompt.fullInjectionActive)
            for (state in listOf(SkillSyncState.PENDING_INDEX, SkillSyncState.SUBMITTED, SkillSyncState.ABSENT)) {
                f.rows = listOf(f.row(global, state = state))
                assertEquals(1, prompt.skillsForPrompt("alice", null, listOf("review"), true).size)
            }
            f.rows = listOf(f.row(global).copy(indexedChecksum = "old"))
            assertEquals(1, prompt.skillsForPrompt("alice", null, listOf("review"), true).size)
        }

        @Test
        fun `suppression requires ready effective view and agent search tool`() = runTest {
            f.skills = listOf(global, local)
            f.rows = listOf(f.row(global, "system"), f.row(local, state = SkillSyncState.PENDING_INDEX))
            val prompt = source(rag = true)
            assertEquals(1, prompt.skillsForPrompt("alice", null, listOf("review")).size)
            assertTrue(prompt.skillsForPrompt("alice", null, listOf("review"), true).isEmpty())
            assertEquals(1, prompt.skillsForPrompt("alice", f.project, listOf("review"), true).size)
            assertEquals(1, source(rag = true, ready = false).skillsForPrompt("alice", null, listOf("review"), true).size)
        }

        @Test
        fun `disabled injection and absent registry return empty`() = runTest {
            assertFalse(source(inject = false).fullInjectionActive)
            assertTrue(source(inject = false).skillsForPrompt("alice", null, listOf("review")).isEmpty())
            val absent = SkillPromptSource(null, f.catalog, true, false)
            assertFalse(absent.fullInjectionActive)
            assertTrue(absent.skillsForPrompt("alice", null, listOf("review")).isEmpty())
        }

        @Test
        fun `blank descriptions are not advertised`() = runTest {
            f.skills = listOf(global.copy(description = "  "))
            f.rows = listOf(f.row(global))
            assertTrue(source().skillsForPrompt("alice", null, listOf("review")).isEmpty())
        }
    }
}
