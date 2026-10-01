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
    private val shared = f.register("review", SkillModelFixture.SYSTEM)
    private val own = f.register("review", "alice")

    private fun source(
        rag: Boolean = false,
        inject: Boolean = true,
        ready: Boolean = rag,
        firstAccessSync: (suspend (String?) -> Unit)? = null,
        directMax: Int = 0
    ) = SkillPromptSource(f.registry, f.catalog, inject, rag, ready, firstAccessSync, directMax)

    @Nested
    inner class EffectiveView {
        @Test
        fun `personal override is selected before enablement filtering`() = runTest {
            f.rows = listOf(f.row(shared), f.row(own, enabled = false))
            val prompt = source()
            assertTrue(prompt.skillsForPrompt("alice", listOf("review")).isEmpty())
            assertEquals(
                "Does review tasks",
                prompt.skillsForPrompt("bob", listOf("review")).single()["description"]
            )
        }

        @Test
        fun `whitelist is required and unbound skills never appear`() = runTest {
            val other = f.register("private", "alice")
            f.rows = listOf(f.row(shared), f.row(own), f.row(other))
            val prompt = source()
            assertTrue(prompt.skillsForPrompt("alice", emptyList()).isEmpty())
            assertEquals(
                listOf("private", "review"),
                prompt.skillsForPrompt("alice", listOf("review", "private")).map { it["name"] }
            )
            // Without alice's own row her registry entry is unbound, and the shared namesake does not
            // substitute for an install path the winning row never pinned.
            f.rows = listOf(f.row(shared), f.row(other))
            assertEquals(listOf("private"), prompt.skillsForPrompt("alice", listOf("review", "private")).map { it["name"] })
        }

        @Test
        fun `toggle and owner changes are visible without refresh`() = runTest {
            f.rows = listOf(f.row(shared), f.row(own))
            val prompt = source()
            assertEquals(1, prompt.skillsForPrompt("alice", listOf("review")).size)
            f.rows = listOf(f.row(shared), f.row(own, enabled = false))
            assertTrue(prompt.skillsForPrompt("alice", listOf("review")).isEmpty())
            assertEquals(listOf("review"), prompt.effectiveNames("bob"))
        }

        @Test
        fun `catalog failure propagates on first and subsequent reads`() = runTest {
            f.rows = listOf(f.row(shared))
            val prompt = source()
            prompt.skillsForPrompt("alice", listOf("review"))
            coEvery { f.catalog.listByUser("alice") } throws IllegalStateException("catalog down")
            assertFailsWith<IllegalStateException> { prompt.skillsForPrompt("alice", listOf("review")) }
            assertFailsWith<IllegalStateException> { source().skillsForPrompt("alice", listOf("review")) }
        }

        @Test
        fun `cancellation propagates`() = runTest {
            coEvery { f.catalog.listByUser(any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { source().skillsForPrompt("alice", listOf("review")) }
        }

        @Test
        fun `without catalog registry skills stay unbound and never suppress`() = runTest {
            val prompt = SkillPromptSource(f.registry, null, true, true)
            assertEquals(
                listOf("review"),
                prompt.skillsForPrompt("alice", listOf("review", "private"), true).map { it["name"] }
            )
        }

        @Test
        fun `lazy sync gate runs before every read`() = runTest {
            f.rows = listOf(f.row(shared))
            var gates = 0
            val prompt = source(firstAccessSync = { gates++ })
            prompt.skillsForPrompt("alice", listOf("review"))
            prompt.effectiveNames("alice")
            assertEquals(2, gates)
        }
    }

    @Nested
    inner class DiscoveryReadiness {
        @Test
        fun `unindexed or drifted skills are never suppressed`() = runTest {
            val prompt = source(rag = true)
            assertTrue(prompt.fullInjectionActive)
            for (state in listOf(SkillSyncState.PENDING_INDEX, SkillSyncState.SUBMITTED, SkillSyncState.ABSENT)) {
                f.rows = listOf(f.row(shared, state = state))
                assertEquals(1, prompt.skillsForPrompt("bob", listOf("review"), true).size)
            }
            f.rows = listOf(f.row(shared).copy(indexedChecksum = "old"))
            assertEquals(1, prompt.skillsForPrompt("bob", listOf("review"), true).size)
        }

        @Test
        fun `suppression requires ready effective view and agent search tool`() = runTest {
            f.rows = listOf(f.row(shared), f.row(own, state = SkillSyncState.PENDING_INDEX))
            val prompt = source(rag = true)
            // Alice's winner is her own still-pending row: the name must stay in the prompt.
            assertEquals(1, prompt.skillsForPrompt("alice", listOf("review")).size)
            assertEquals(1, prompt.skillsForPrompt("alice", listOf("review"), true).size)
            // Bob's whole effective view is the ready shared row, so the search tool replaces it.
            assertTrue(prompt.skillsForPrompt("bob", listOf("review"), true).isEmpty())
            assertEquals(1, prompt.skillsForPrompt("bob", listOf("review")).size)
            assertEquals(1, source(rag = true, ready = false).skillsForPrompt("bob", listOf("review"), true).size)
        }

        @Test
        fun `disabled injection and absent registry return empty`() = runTest {
            assertFalse(source(inject = false).fullInjectionActive)
            assertTrue(source(inject = false).skillsForPrompt("alice", listOf("review")).isEmpty())
            val absent = SkillPromptSource(null, f.catalog, true, false)
            assertFalse(absent.fullInjectionActive)
            assertTrue(absent.skillsForPrompt("alice", listOf("review")).isEmpty())
        }

        @Test
        fun `blank descriptions are not advertised`() = runTest {
            val named = f.register("explicit", "alice")
            f.rows = listOf(f.row(named))
            assertEquals(1, source().skillsForPrompt("alice", listOf("explicit")).size, "a described skill is advertised")
            val blank = f.register("sparse", "alice", description = "   ")
            f.rows = listOf(f.row(blank))
            assertTrue(source().skillsForPrompt("alice", listOf("sparse")).isEmpty())
            val bare = f.register("wordless", "alice", description = "")
            f.rows = listOf(f.row(bare))
            assertTrue(source().skillsForPrompt("alice", listOf("wordless")).isEmpty())
        }
    }

    @Nested
    inner class ScaleThreshold {
        @Test
        fun `catalogs at or below the threshold stay injected with the index ready`() = runTest {
            val second = f.register("deploy", "alice")
            f.rows = listOf(f.row(own), f.row(second))
            val prompt = source(rag = true, directMax = 2)
            assertEquals(
                listOf("deploy", "review"),
                prompt.skillsForPrompt("alice", listOf("review", "deploy"), true).map { it["name"] }
            )
        }

        @Test
        fun `catalogs above the threshold are suppressed`() = runTest {
            val second = f.register("deploy", "alice")
            val third = f.register("triage", "alice")
            f.rows = listOf(f.row(own), f.row(second), f.row(third))
            assertTrue(source(rag = true, directMax = 2).skillsForPrompt("alice", listOf("review", "deploy", "triage"), true).isEmpty())
        }

        @Test
        fun `threshold counts the owner catalog, not the whitelist subset`() = runTest {
            val second = f.register("deploy", "alice")
            val third = f.register("triage", "alice")
            f.rows = listOf(f.row(own), f.row(second), f.row(third))
            // One whitelisted skill but three effective ones: the owner is above the threshold.
            assertTrue(source(rag = true, directMax = 2).skillsForPrompt("alice", listOf("review"), true).isEmpty())
        }

        @Test
        fun `rag off never suppresses whatever the size`() = runTest {
            val second = f.register("deploy", "alice")
            val third = f.register("triage", "alice")
            f.rows = listOf(f.row(own), f.row(second), f.row(third))
            assertEquals(3, source(directMax = 0).skillsForPrompt("alice", listOf("review", "deploy", "triage"), true).size)
        }

        @Test
        fun `zero threshold keeps legacy suppress-when-ready`() = runTest {
            f.rows = listOf(f.row(own))
            assertTrue(source(rag = true, directMax = 0).skillsForPrompt("alice", listOf("review"), true).isEmpty())
        }
    }

    @Nested
    inner class SelectionCandidates {
        @Test
        fun `candidates are whitelist intersect effective, described only, suppression-blind`() = runTest {
            val blank = f.register("sparse", "alice", description = "")
            val deploy = f.register("deploy", "alice")
            f.rows = listOf(f.row(own), f.row(deploy), f.row(blank))
            // Baseline listing is suppressed (rag ready, threshold 0) but routing candidates remain.
            assertTrue(source(rag = true, directMax = 0).skillsForPrompt("alice", listOf("review", "deploy", "sparse"), true).isEmpty())
            assertEquals(
                listOf("deploy", "review"),
                source(rag = true, directMax = 0).candidatesForSelection("alice", listOf("review", "deploy", "sparse")).map { it["name"] }
            )
        }

        @Test
        fun `empty whitelist and injection-off yield no candidates`() = runTest {
            f.rows = listOf(f.row(own))
            assertTrue(source().candidatesForSelection("alice", emptyList()).isEmpty())
            assertTrue(source(inject = false).candidatesForSelection("alice", listOf("review")).isEmpty())
        }
    }
}
