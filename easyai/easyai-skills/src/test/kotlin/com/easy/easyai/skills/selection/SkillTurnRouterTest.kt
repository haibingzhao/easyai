package com.easy.easyai.skills.selection

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.skills.SkillPromptSource
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkillTurnRouterTest {

    private val promptSource = mockk<SkillPromptSource>()
    private val auxResolver = mockk<AuxModelResolver>()
    private val client = mockk<SkillSelectionClient>()

    private fun router() = SkillTurnRouter(promptSource, auxResolver, client, minConfidence = 0.5, timeoutMs = 5_000)

    private val poster = mapOf("name" to "poster", "description" to "海报生成")

    private fun config(apiKey: String? = "sk-test", baseUrl: String? = "https://host/apps/anthropic") =
        ModelProviderConfig(
            id = "cfg-1", name = "decision", protocol = Protocol.ANTHROPIC, isCustom = true,
            baseUrl = baseUrl, apiKey = apiKey, modelId = "decision-model-preview"
        )

    private fun stubConfig() {
        coEvery { auxResolver.resolveConfig(listOf("alice"), AuxModelTask.SKILL_SELECTION) } returns config()
    }

    private fun stubCandidates(vararg skills: Map<String, Any?>) {
        coEvery { promptSource.candidatesForSelectionOwners(listOf("alice"), listOf("poster")) } returns skills.toList()
    }

    @Nested
    inner class Gating {

        @Test
        fun `unconfigured aux model means zero HTTP calls`() = runTest {
            stubCandidates(poster)
            coEvery { auxResolver.resolveConfig(listOf("alice"), AuxModelTask.SKILL_SELECTION) } returns null
            assertNull(router().route("alice", listOf("poster"), "做一张海报"))
            coVerify(exactly = 0) { client.decide(any()) }
        }

        @Test
        fun `blank query and empty whitelist short-circuit before the resolver`() = runTest {
            assertNull(router().route("alice", listOf("poster"), "  "))
            assertNull(router().route("alice", emptyList(), "做一张海报"))
            coVerify(exactly = 0) { auxResolver.resolveConfig(any<List<String>>(), any()) }
            coVerify(exactly = 0) { client.decide(any()) }
        }

        @Test
        fun `config without apiKey or baseUrl degrades`() = runTest {
            stubCandidates(poster)
            coEvery { auxResolver.resolveConfig(listOf("alice"), AuxModelTask.SKILL_SELECTION) } returns config(apiKey = null)
            assertNull(router().route("alice", listOf("poster"), "做一张海报"))
            coEvery { auxResolver.resolveConfig(listOf("alice"), AuxModelTask.SKILL_SELECTION) } returns config(baseUrl = "")
            assertNull(router().route("alice", listOf("poster"), "做一张海报"))
            coVerify(exactly = 0) { client.decide(any()) }
        }

        @Test
        fun `empty candidates skip the call`() = runTest {
            stubConfig()
            stubCandidates()
            assertNull(router().route("alice", listOf("poster"), "做一张海报"))
            coVerify(exactly = 0) { client.decide(any()) }
        }

        @Test
        fun `catalog above the choice cap keeps the baseline`() = runTest {
            stubConfig()
            val many = (1..255).map { mapOf("name" to "s$it", "description" to "d") }
            coEvery { promptSource.candidatesForSelectionOwners(listOf("alice"), listOf("poster")) } returns many
            assertNull(router().route("alice", listOf("poster"), "做一张海报"))
            coVerify(exactly = 0) { client.decide(any()) }
        }
    }

    @Nested
    inner class Decision {

        private val requests = mutableListOf<SystemOneSelectionRequest>()

        private fun stubDecision(decision: SystemOneDecision?) {
            stubConfig()
            stubCandidates(poster, mapOf("name" to "review", "description" to "代码评审"))
            requests.clear()
            coEvery { client.decide(capture(requests)) } returns decision
        }

        @Test
        fun `high-confidence hit returns exactly the chosen skill`() = runTest {
            stubDecision(SystemOneDecision("poster", 0.9))
            val routed = router().route("alice", listOf("poster"), "帮我生成一张海报")
            assertEquals(listOf(poster), routed)
            val sent = requests.single()
            assertEquals("decision-model-preview", sent.modelId)
            assertEquals(5_000, sent.timeoutMs)
            assertEquals("帮我生成一张海报", sent.query)
            assertEquals(setOf("poster", "review", SkillTurnRouter.UNSELECTED), sent.criteria.keys)
            assertEquals("海报生成", sent.criteria["poster"])
        }

        @Test
        fun `other, unknown label, low confidence and transport failure keep the baseline`() = runTest {
            stubDecision(SystemOneDecision(SkillTurnRouter.UNSELECTED, 0.99))
            assertNull(router().route("alice", listOf("poster"), "今天天气如何"))

            stubDecision(SystemOneDecision("ghost", 0.99))
            assertNull(router().route("alice", listOf("poster"), "帮我生成一张海报"))

            stubDecision(SystemOneDecision("poster", 0.4))
            assertNull(router().route("alice", listOf("poster"), "帮我生成一张海报"))

            stubDecision(SystemOneDecision("poster", null))
            assertNull(router().route("alice", listOf("poster"), "帮我生成一张海报"))

            stubDecision(null)
            assertNull(router().route("alice", listOf("poster"), "帮我生成一张海报"))
        }

        @Test
        fun `confidence exactly at the threshold routes`() = runTest {
            stubDecision(SystemOneDecision("review", 0.5))
            val routed = router().route("alice", listOf("poster"), "帮我做代码评审")
            assertEquals("review", routed?.single()?.get("name"))
        }

        @Test
        fun `request carries no extra recent context in v1`() = runTest {
            stubDecision(SystemOneDecision("poster", 0.9))
            router().route("alice", listOf("poster"), "做一张海报")
            val captured: CapturingSlot<SystemOneSelectionRequest> = slot()
            coVerify { client.decide(capture(captured)) }
            assertNull(captured.captured.recent)
        }
    }
}
