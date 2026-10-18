package com.easy.easyai.core.memory

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests [MemoryConsolidationAgent]'s pre-flight gates (entry-count short circuit, deployment kill
 * switch, curation-model resolution and protocol support), its batch budget, and what it makes of the
 * JSON each batch answers with. The write rules themselves live in `MemoryConsolidationWriterTest`.
 */
class MemoryConsolidationAgentTest {

    private val store = mockk<MemoryStore>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val agentService = mockk<AgentService>(relaxed = true)
    private val auxResolver = mockk<AuxModelResolver>()
    private val scope = MemoryScope.PROJECT
    private val owner = MemoryOwnerContext("alice", Path.of("/tmp/demo"))

    private fun entry(name: String) = MemoryEntry(
        id = "doc-$name",
        name = name,
        description = "desc $name",
        type = MemoryType.EXPERIENCE_LESSONS,
        content = "body $name",
        path = "experience_lessons/$name.md",
        created = LocalDate.of(2026, 1, 1),
        updated = LocalDate.of(2026, 1, 1)
    )

    private fun curationConfig() = ModelProviderConfig(
        id = "cfg-1",
        name = "curation",
        protocol = Protocol.OPENAI,
        isCustom = false,
        modelId = "gpt-test"
    )

    private fun agent(settings: MemoryConsolidationSettings = MemoryConsolidationSettings()) =
        MemoryConsolidationAgent(
            store = store,
            sessionManager = sessionManager,
            agentService = agentService,
            settings = settings,
            auxResolver = auxResolver
        )

    private fun givenSupportedModel() {
        coEvery { auxResolver.resolveConfig(any<Collection<String>>(), AuxModelTask.MEMORY_CURATION) } returns curationConfig()
        val factory = mockk<ChatModelFactory>()
        every { factory.supports(any()) } returns true
        every { agentService.chatModelFactories } returns listOf(factory)
    }

    @Nested
    inner class PreFlightGates {

        @Test
        fun `fewer than two active entries returns an empty report without touching the session`() = runTest {
            givenSupportedModel()
            coEvery { store.list(scope, owner, MemoryType.EXPERIENCE_LESSONS) } returns listOf(entry("only-one"))

            val report = agent().consolidate(scope, owner, MemoryType.EXPERIENCE_LESSONS)

            assertEquals(1, report.activeBefore)
            assertEquals(0, report.mergedWritten)
            assertEquals(0, report.archived)
            assertEquals(0, report.skippedOverCap)
            coVerify(exactly = 0) { sessionManager.getOrCreateSession(any<AgentContext>(), any(), any()) }
        }

        @Test
        fun `the deployment kill switch fails with an explicit reason`() = runTest {
            val error = assertFailsWith<IllegalStateException> {
                agent(MemoryConsolidationSettings(available = false))
                    .consolidate(scope, owner, MemoryType.EXPERIENCE_LESSONS)
            }

            assertTrue(error.message!!.contains("disabled by configuration"))
        }

        @Test
        fun `an unconfigured curation model points at the settings page`() = runTest {
            coEvery { auxResolver.resolveConfig(any<Collection<String>>(), AuxModelTask.MEMORY_CURATION) } returns null

            val error = assertFailsWith<IllegalStateException> {
                agent().consolidate(scope, owner, MemoryType.EXPERIENCE_LESSONS)
            }

            assertTrue(error.message!!.contains("Memory curation model is not configured"))
        }

        @Test
        fun `a model whose protocol no factory supports fails before building a session`() = runTest {
            coEvery { auxResolver.resolveConfig(any<Collection<String>>(), AuxModelTask.MEMORY_CURATION) } returns curationConfig()
            val factory = mockk<ChatModelFactory>()
            every { factory.supports(any()) } returns false
            every { agentService.chatModelFactories } returns listOf(factory)

            val error = assertFailsWith<IllegalStateException> {
                agent().consolidate(scope, owner, MemoryType.EXPERIENCE_LESSONS)
            }

            assertTrue(error.message!!.contains("No ChatModelFactory supports protocol"))
            coVerify(exactly = 0) { sessionManager.getOrCreateSession(any<AgentContext>(), any(), any()) }
        }

        @Test
        fun `an owner without a user id is rejected`() = runTest {
            val error = assertFailsWith<IllegalStateException> {
                agent().consolidate(scope, MemoryOwnerContext(), MemoryType.EXPERIENCE_LESSONS)
            }

            assertTrue(error.message!!.contains("owning user"))
        }

        @Test
        fun `a run that outlives its budget fails as a failure, not as a cancel`() = runTest {
            givenSupportedModel()
            coEvery { store.list(scope, owner, MemoryType.EXPERIENCE_LESSONS) } coAnswers {
                delay(500)
                listOf(entry("a"), entry("b"))
            }

            val error = assertFailsWith<IllegalStateException> {
                agent(MemoryConsolidationSettings(runTimeoutMs = 100))
                    .consolidate(scope, owner, MemoryType.EXPERIENCE_LESSONS)
            }

            assertTrue(error.message!!.contains("timed out"), error.message.orEmpty())
            coVerify(exactly = 0) { sessionManager.getOrCreateSession(any<AgentContext>(), any(), any()) }
        }
    }

    @Nested
    inner class BatchBudget {

        private fun bigEntry(name: String, contentChars: Int) = entry(name).copy(content = "x".repeat(contentChars))

        @Test
        fun `the character budget cuts a batch before the entry cap does`() {
            val settings = MemoryConsolidationSettings(maxEntriesPerBatch = 200, maxPromptCharsPerBatch = 8_000)
            val entries = listOf(
                bigEntry("a", 3_000), bigEntry("b", 3_000), bigEntry("c", 3_000)
            )

            val batches = agent(settings).batchByBudget(entries)

            assertEquals(listOf(2, 1), batches.map { it.size })
        }

        @Test
        fun `the entry cap still binds when the budget is roomy`() {
            val settings = MemoryConsolidationSettings(maxEntriesPerBatch = 2, maxPromptCharsPerBatch = 1_000_000)
            val entries = (1..5).map { entry("e$it") }

            val batches = agent(settings).batchByBudget(entries)

            assertEquals(listOf(2, 2, 1), batches.map { it.size })
        }

        @Test
        fun `an entry larger than the whole budget travels alone instead of being dropped`() {
            val settings = MemoryConsolidationSettings(maxEntriesPerBatch = 200, maxPromptCharsPerBatch = 8_000)
            val entries = listOf(bigEntry("huge", 20_000), entry("small"))

            val batches = agent(settings).batchByBudget(entries)

            assertEquals(listOf(1, 1), batches.map { it.size })
            assertEquals(entries.map { it.name }, batches.flatten().map { it.name })
        }
    }

    /** What the agent makes of each batch's answer: the whole point of moving writes out of the tool. */
    @Nested
    inner class BatchAnswers {

        private fun jsonArrayOf(values: List<String>): String =
            values.joinToString(",", "[", "]") { "\"$it\"" }

        private fun answer(clusters: List<List<String>>, leftovers: List<String>): String {
            val rendered = clusters.joinToString(",", "[", "]") { ids ->
                "{\"mergedTitle\":\"FRP setup\",\"mergedDescription\":\"desc\"," +
                    "\"mergedContent\":\"body\",\"sectionRefs\":[]," +
                    "\"sourceDocIds\":${jsonArrayOf(ids)}}"
            }
            return "{\"clusters\":$rendered,\"leftoverDocIds\":${jsonArrayOf(leftovers)}}"
        }

        /** Wire one run whose session answers with [answers] in order, and whose store resolves [pool]. */
        private fun givenRun(pool: List<MemoryEntry>, vararg answers: String): ChatSession {
            givenSupportedModel()
            coEvery { store.list(scope, owner, MemoryType.EXPERIENCE_LESSONS) } returns pool
            coEvery { store.findByIds(any(), scope, owner) } answers {
                pool.filter { it.id in firstArg<List<String>>() }
            }
            val written = slot<MemoryEntry>()
            coEvery { store.writeEntry(capture(written), scope, owner) } answers {
                written.captured.copy(id = "merged-1")
            }
            coEvery { store.archive(any(), any(), scope, owner) } returns 2
            val streams = answers.map { text ->
                val stream = mockk<EventStream<AgentEvent, List<AssistantMessage>>>()
                coEvery { stream.result() } returns listOf(
                    AssistantMessage(content = listOf(TextContent(text)))
                )
                stream
            }
            val session = mockk<ChatSession>(relaxed = true)
            every { session.prompt(any(), any()) } returnsMany streams
            coEvery { sessionManager.getOrCreateSession(any<AgentContext>(), any(), any()) } returns session
            return session
        }

        @Test
        fun `an answer without JSON loses that batch and writes nothing`() = runTest {
            val session = givenRun(listOf(entry("a"), entry("b")), "These entries look related, I would merge them.")

            val report = agent().consolidate(scope, owner, MemoryType.EXPERIENCE_LESSONS)

            assertEquals(1, report.rejectedBatches)
            assertEquals(0, report.mergedWritten)
            assertEquals(2, report.uncoveredDocIds, "nothing was declared, so nothing was accounted for")
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
            verify(exactly = 1) { session.prompt(any(), any()) }
        }

        @Test
        fun `a schema-conforming answer is written and archived through the store`() = runTest {
            givenRun(
                listOf(entry("a"), entry("b")),
                answer(clusters = listOf(listOf("doc-a", "doc-b")), leftovers = emptyList())
            )

            val report = agent().consolidate(scope, owner, MemoryType.EXPERIENCE_LESSONS)

            assertEquals(1, report.mergedWritten)
            assertEquals(2, report.archived)
            assertEquals(0, report.rejectedBatches)
            assertEquals(0, report.uncoveredDocIds)
        }

        @Test
        fun `an entry the answer did not account for is asked about once more`() = runTest {
            val session = givenRun(
                listOf(entry("a"), entry("b"), entry("c")),
                answer(clusters = listOf(listOf("doc-a", "doc-b")), leftovers = emptyList()),
                answer(clusters = emptyList(), leftovers = listOf("doc-c"))
            )

            val report = agent().consolidate(scope, owner, MemoryType.EXPERIENCE_LESSONS)

            verify(exactly = 2) { session.prompt(any(), any()) }
            assertEquals(1, report.mergedWritten)
            assertEquals(0, report.uncoveredDocIds, "the re-ask closed the gap, so the report must not keep it")
            assertEquals(0, report.rejectedBatches)
        }

        @Test
        fun `a re-ask that is unusable too stops there`() = runTest {
            val session = givenRun(
                listOf(entry("a"), entry("b"), entry("c")),
                answer(clusters = listOf(listOf("doc-a", "doc-b")), leftovers = emptyList()),
                "Sorry, I cannot decide about the remaining entry."
            )

            val report = agent().consolidate(scope, owner, MemoryType.EXPERIENCE_LESSONS)

            verify(exactly = 2) { session.prompt(any(), any()) }
            assertEquals(1, report.rejectedBatches)
            assertEquals(1, report.uncoveredDocIds)
            assertEquals(1, report.mergedWritten, "the first answer still counts")
        }
    }
}
