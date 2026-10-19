package com.easy.easyai.core.memory

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.ErrorEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.memory.consolidation.CONSOLIDATION_GROUPING_SCHEMA
import com.easy.easyai.core.memory.consolidation.CONSOLIDATION_MERGE_SCHEMA
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests [MemoryConsolidationAgent]'s pre-flight gates, its two-pass orchestration over one session (what
 * each pass is shown, what contract each turn runs under, and what the transcript markers say), and what
 * it makes of the JSON each prompt answers with. The write and cluster-validation rules themselves live
 * in `MemoryConsolidationWriterTest`.
 */
class MemoryConsolidationAgentTest {

    private val store = mockk<MemoryStore>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val agentService = mockk<AgentService>(relaxed = true)
    private val auxResolver = mockk<AuxModelResolver>()
    private val scope = MemoryScope.PROJECT
    private val owner = MemoryOwnerContext("alice", Path.of("/tmp/demo"))
    private val type = MemoryType.EXPERIENCE_LESSONS

    private fun entry(name: String, level: Int = 0, contentChars: Int = 8) = MemoryEntry(
        id = "doc-$name",
        name = name,
        description = "desc $name",
        type = type,
        content = "body $name" + "x".repeat((contentChars - 8).coerceAtLeast(0)),
        path = "experience_lessons/$name.md",
        created = LocalDate.of(2026, 1, 1),
        updated = LocalDate.of(2026, 1, 1),
        level = level
    )

    private fun indexRow(name: String, level: Int = 0, chars: Int = 500, blurb: String = "desc $name") =
        MemoryIndexEntry(
            docId = "doc-$name",
            name = name,
            type = type,
            level = level,
            contentChars = chars,
            blurb = blurb
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

    /** Every turn this run sent, captured as sent; [sent] is its user message view. */
    private val sentTurns = mutableListOf<List<EasyAiMessage>>()

    /** The user messages behind those turns, in order, carrying the metadata the transcript splits on. */
    private val sent: List<UserMessage> get() = sentTurns.flatten().filterIsInstance<UserMessage>()

    private fun groupingAnswer(groups: List<List<String>>, leftovers: List<String>): String {
        val rendered = groups.joinToString(",", "[", "]") { ids ->
            "{\"title\":\"FRP setup\",\"sourceDocIds\":${jsonArrayOf(ids)}}"
        }
        return "{\"groups\":$rendered,\"leftoverDocIds\":${jsonArrayOf(leftovers)}}"
    }

    private fun mergeAnswer(clusters: List<List<String>>, unmerged: List<String> = emptyList()): String {
        val rendered = clusters.joinToString(",", "[", "]") { ids ->
            "{\"mergedTitle\":\"FRP setup\",\"mergedDescription\":\"desc\"," +
                "\"mergedContent\":\"body\",\"sectionRefs\":[]," +
                "\"sourceDocIds\":${jsonArrayOf(ids)}}"
        }
        return "{\"clusters\":$rendered,\"unmergedDocIds\":${jsonArrayOf(unmerged)}}"
    }

    private fun jsonArrayOf(values: List<String>): String =
        values.joinToString(",", "[", "]") { "\"$it\"" }

    private fun streamOf(text: String?): EventStream<AgentEvent, List<AssistantMessage>> {
        val stream = mockk<EventStream<AgentEvent, List<AssistantMessage>>>()
        coEvery { stream.result() } returns if (text == null) emptyList() else listOf(AssistantMessage(content = listOf(TextContent(text))))
        every { stream.asFlow() } returns flowOf(
            ErrorEvent(error = IllegalStateException("Cannot construct instance of `com.openai.core.JsonValue`"), sessionId = "s-1")
        )
        return stream
    }

    /**
     * Wire one run: the slice's index rows, the pool the merge pass can read bodies from, and one answer
     * per prompt in order. A null answer stands for a call that never reached the model.
     */
    private fun givenRun(rows: List<MemoryIndexEntry>, pool: List<MemoryEntry>, vararg answers: String?): ChatSession {
        givenSupportedModel()
        sentTurns.clear()
        coEvery { store.listActiveIndex(scope, owner, type) } returns rows
        coEvery { store.findByIds(any(), scope, owner) } answers { pool.filter { it.id in firstArg<List<String>>() } }
        val written = slot<MemoryEntry>()
        coEvery { store.writeEntry(capture(written), scope, owner) } answers { written.captured.copy(id = "merged-1") }
        coEvery { store.archive(any(), any(), scope, owner) } returns 2
        val streams = answers.map { streamOf(it) }
        val session = mockk<ChatSession>(relaxed = true)
        every { session.promptWithHistory(capture(sentTurns)) } returnsMany streams
        coEvery { sessionManager.getOrCreateSession(any<AgentContext>(), any(), any()) } returns session
        return session
    }

    @Nested
    inner class PreFlightGates {

        @Test
        fun `fewer than two active entries returns an empty report without touching the session`() = runTest {
            givenRun(listOf(indexRow("only-one")), listOf(entry("only-one")))

            val report = agent().consolidate(scope, owner, type)

            assertEquals(1, report.activeBefore)
            assertEquals(0, report.mergedWritten)
            assertEquals(0, report.archived)
            assertEquals(0, report.skippedOverCap)
            coVerify(exactly = 0) { sessionManager.getOrCreateSession(any<AgentContext>(), any(), any()) }
        }

        @Test
        fun `entries at the depth ceiling are not material, so a full slice can still be done`() = runTest {
            // Three level-3 digests are active and counted, yet nothing is left to merge them into: this
            // is the ceiling doing its job, which is what stops yesterday's digest being distilled again.
            val rows = listOf(indexRow("a", level = 3), indexRow("b", level = 3), indexRow("c", level = 3))
            givenRun(rows, rows.map { entry(it.name, level = it.level) })

            val report = agent(MemoryConsolidationSettings(maxLevel = 3)).consolidate(scope, owner, type)

            assertEquals(3, report.activeBefore)
            coVerify(exactly = 0) { sessionManager.getOrCreateSession(any<AgentContext>(), any(), any()) }
        }

        @Test
        fun `the deployment kill switch fails with an explicit reason`() = runTest {
            val error = assertFailsWith<IllegalStateException> {
                agent(MemoryConsolidationSettings(available = false)).consolidate(scope, owner, type)
            }

            assertTrue(error.message!!.contains("disabled by configuration"))
        }

        @Test
        fun `an unconfigured curation model points at the settings page`() = runTest {
            coEvery { auxResolver.resolveConfig(any<Collection<String>>(), AuxModelTask.MEMORY_CURATION) } returns null

            val error = assertFailsWith<IllegalStateException> { agent().consolidate(scope, owner, type) }

            assertTrue(error.message!!.contains("Memory curation model is not configured"))
        }

        @Test
        fun `a model whose protocol no factory supports fails before building a session`() = runTest {
            coEvery { auxResolver.resolveConfig(any<Collection<String>>(), AuxModelTask.MEMORY_CURATION) } returns curationConfig()
            val factory = mockk<ChatModelFactory>()
            every { factory.supports(any()) } returns false
            every { agentService.chatModelFactories } returns listOf(factory)

            val error = assertFailsWith<IllegalStateException> { agent().consolidate(scope, owner, type) }

            assertTrue(error.message!!.contains("No ChatModelFactory supports protocol"))
            coVerify(exactly = 0) { sessionManager.getOrCreateSession(any<AgentContext>(), any(), any()) }
        }

        @Test
        fun `an owner without a user id is rejected`() = runTest {
            val error = assertFailsWith<IllegalStateException> {
                agent().consolidate(scope, MemoryOwnerContext(), type)
            }

            assertTrue(error.message!!.contains("owning user"))
        }

        @Test
        fun `a run that outlives its budget fails as a failure, not as a cancel`() = runTest {
            givenSupportedModel()
            coEvery { store.listActiveIndex(scope, owner, type) } coAnswers {
                delay(500)
                listOf(indexRow("a"), indexRow("b"))
            }

            val error = assertFailsWith<IllegalStateException> {
                agent(MemoryConsolidationSettings(runTimeoutMs = 100)).consolidate(scope, owner, type)
            }

            assertTrue(error.message!!.contains("timed out"), error.message.orEmpty())
            coVerify(exactly = 0) { sessionManager.getOrCreateSession(any<AgentContext>(), any(), any()) }
        }
    }

    @Nested
    inner class TwoPasses {

        private val rows = listOf(indexRow("a"), indexRow("b"), indexRow("c"), indexRow("d"))
        private val pool = listOf(entry("a"), entry("b"), entry("c"), entry("d"))

        @Test
        fun `grouping asks once and merging follows with the bodies`() = runTest {
            val session = givenRun(
                rows,
                pool,
                groupingAnswer(listOf(listOf("doc-a", "doc-b"), listOf("doc-c", "doc-d")), leftovers = emptyList()),
                mergeAnswer(listOf(listOf("doc-a", "doc-b"), listOf("doc-c", "doc-d")))
            )

            val report = agent().consolidate(scope, owner, type)

            assertEquals(2, sent.size, "one grouping prompt, one merge prompt")
            assertEquals("grouping", sent[0].metadata["consolidationPhase"])
            assertEquals("merge", sent[1].metadata["consolidationPhase"])
            assertEquals(2, report.clustersProposed)
            assertEquals(2, report.mergedWritten)
            assertEquals(4, report.archived)
            assertEquals(0, report.uncoveredDocIds)
            assertTrue(sent[1].text().contains("body a"), "the merge pass must be given the source bodies")
            assertFalse(sent[0].text().contains("body a"), "grouping must not be shown bodies")
            verify(exactly = 1) { session.updateTurnOutputContract(any(), CONSOLIDATION_GROUPING_SCHEMA, null) }
            verify(exactly = 1) { session.updateTurnOutputContract(any(), CONSOLIDATION_MERGE_SCHEMA, false) }
        }

        @Test
        fun `a preview stops at grouping and reads no bodies`() = runTest {
            givenRun(
                rows,
                pool,
                groupingAnswer(listOf(listOf("doc-a", "doc-b")), leftovers = listOf("doc-c", "doc-d"))
            )

            val report = agent().consolidate(scope, owner, type, dryRun = true)

            assertEquals(1, sent.size)
            assertEquals("grouping", sent.single().metadata["consolidationPhase"])
            assertEquals(1, report.clustersProposed)
            assertEquals(0, report.mergedWritten, "a preview writes nothing")
            coVerify(exactly = 0) { store.findByIds(any(), any(), any()) }
        }

        @Test
        fun `the run asks for a session with no tools and no skills, which its prompt promises`() = runTest {
            givenRun(
                rows,
                pool,
                groupingAnswer(listOf(listOf("doc-a", "doc-b")), leftovers = listOf("doc-c", "doc-d"))
            )

            agent().consolidate(scope, owner, type, dryRun = true)

            val context = slot<AgentContext>()
            coVerify { sessionManager.getOrCreateSession(capture(context), any(), any()) }
            assertTrue(
                context.captured.tools.isEmpty() &&
                    context.captured.skills.isEmpty() &&
                    context.captured.allowedSkillNames.isEmpty(),
                "the system prompt says \"You have no tools\", so the request must carry no tools and no " +
                    "skill list — this agentId has no row, and the session manager takes an empty list as final"
            )
        }

        @Test
        fun `the whole slice fits one grouping prompt, so clusters never form across prompts`() = runTest {
            // The index is small next to its budget by design: this is the property the old batch-by-body
            // prompt lost, where 20 fat entries became three prompts that could not see each other.
            val many = (1..40).map { indexRow("e$it", chars = 2_000) }
            givenRun(many, emptyList(), groupingAnswer(emptyList(), leftovers = many.map { it.docId }))

            agent().consolidate(scope, owner, type, dryRun = true)

            assertEquals(1, sent.size, "40 index rows are one prompt, whatever their stored bodies weigh")
        }

        @Test
        fun `an oversized slice overflows into name-adjacent prompts rather than arbitrary ones`() = runTest {
            val long = "x".repeat(1_000)
            val many = (1..60).map { indexRow("entry-$it", blurb = long) }
            val ids = many.map { it.docId }
            // Rows are cut in name order, which for these names is the lexicographic order.
            val ordered = many.sortedBy { it.name }.map { it.docId }
            givenRun(many, emptyList(), groupingAnswer(emptyList(), leftovers = ids), groupingAnswer(emptyList(), leftovers = ids))

            agent(MemoryConsolidationSettings(maxGroupingPromptChars = 20_000))
                .consolidate(scope, owner, type, dryRun = true)

            assertTrue(sent.size > 1, "a slice past the budget must still be cut")
            // Match the quoted attribute, never a bare id: doc-entry-1 is a prefix of doc-entry-10.
            val seen = sent.flatMap { prompt -> ids.filter { id -> prompt.text().contains("docId=\"$id\"") } }
            assertEquals(ids.toSet(), seen.toSet(), "every entry is asked about")
            assertEquals(ids.size, seen.size, "and exactly once — a row repeated across prompts would be")
            // Rows are ordered by name before cutting, so the overflow splits adjacent topics as little
            // as a fixed ordering can: the entries that could not fit are always the tail, not a scatter.
            val firstPromptIds = ordered.filter { sent[0].text().contains("docId=\"$it\"") }
            assertEquals(firstPromptIds, ordered.take(firstPromptIds.size), "the first prompt holds a prefix of the order")
        }

        @Test
        fun `clusters are packed whole and never split across merge prompts`() = runTest {
            val pairs = (1..6).map { listOf("doc-p$it", "doc-q$it") }
            val rows = (1..6).flatMap { listOf(indexRow("p$it"), indexRow("q$it")) }
            val pool = rows.map { entry(it.name) }
            givenRun(
                rows,
                pool,
                groupingAnswer(pairs, leftovers = emptyList()),
                mergeAnswer(pairs.take(3)),
                mergeAnswer(pairs.drop(3))
            )

            val report = agent(MemoryConsolidationSettings(clustersPerMergeCall = 3)).consolidate(scope, owner, type)

            assertEquals(6, report.clustersProposed)
            assertEquals(3, sent.size, "one grouping prompt plus two merge prompts of three clusters")
            assertEquals(6, report.mergedWritten)
            // A cluster is atomic: whichever prompt it travelled in carries both of its bodies.
            sent.drop(1).forEach { prompt ->
                pairs.forEach { pair ->
                    val bodies = pair.map { "body ${it.removePrefix("doc-")}" }
                    val seen = bodies.count { prompt.text().contains(it) }
                    assertTrue(seen == 0 || seen == 2, "a cluster's bodies must travel together: $pair")
                }
            }
        }

        @Test
        fun `a re-ask is handed the material it asks about`() = runTest {
            givenRun(
                rows,
                pool,
                // Grouping forgets c and d entirely...
                groupingAnswer(listOf(listOf("doc-a", "doc-b")), leftovers = emptyList()),
                // ...so the re-ask must be shown their rows to decide on them.
                groupingAnswer(listOf(listOf("doc-c", "doc-d")), leftovers = emptyList()),
                // The merge pass then writes one cluster and drops the other's docIds on the floor...
                mergeAnswer(listOf(listOf("doc-a", "doc-b"))),
                // ...and its re-ask must be shown their bodies, not just their ids.
                mergeAnswer(emptyList(), unmerged = listOf("doc-c", "doc-d"))
            )

            val report = agent().consolidate(scope, owner, type)

            val reasks = sent.filter { it.metadata.containsKey("consolidationReask") }
            assertEquals(2, reasks.size, "both passes re-ask once")
            val groupingReask = reasks.first { it.metadata["consolidationPhase"] == "grouping" }
            assertTrue(groupingReask.text().contains("desc c"), "its rows are repeated with their blurbs")
            assertFalse(groupingReask.text().contains("body a"), "and it stays inside the entries that went missing")
            val mergeReask = reasks.first { it.metadata["consolidationPhase"] == "merge" }
            assertTrue(mergeReask.text().contains("body c"), "a merge re-ask without the body asks about nothing")
            assertEquals(0, report.uncoveredDocIds, "both gaps closed")
            assertEquals(1, report.mergedWritten)
        }
    }

    /** What the agent makes of each prompt's answer: the whole point of moving writes out of the tool. */
    @Nested
    inner class BatchAnswers {

        private val rows = listOf(indexRow("a"), indexRow("b"))
        private val pool = listOf(entry("a"), entry("b"))

        @Test
        fun `an answer without JSON loses that prompt and writes nothing`() = runTest {
            val session = givenRun(rows, pool, "These entries look related, I would merge them.")

            val report = agent().consolidate(scope, owner, type)

            assertEquals(1, report.rejectedBatches)
            assertEquals(0, report.mergedWritten)
            assertEquals(2, report.uncoveredDocIds, "nothing was declared, so nothing was accounted for")
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
            verify(exactly = 1) { session.promptWithHistory(any()) }
        }

        @Test
        fun `a re-ask that is unusable too stops there`() = runTest {
            val session = givenRun(
                rows + indexRow("c"),
                pool + entry("c"),
                groupingAnswer(listOf(listOf("doc-a", "doc-b")), leftovers = emptyList()),
                "Sorry, I cannot decide about the remaining entry.",
                mergeAnswer(listOf(listOf("doc-a", "doc-b")))
            )

            val report = agent().consolidate(scope, owner, type)

            verify(exactly = 3) { session.promptWithHistory(any()) }
            assertEquals(1, report.rejectedBatches, "the failed re-ask costs one prompt, no more")
            assertEquals(1, report.uncoveredDocIds, "and the entry it lost stays unaccounted for")
            assertEquals(1, report.mergedWritten, "the first grouping answer still stands and was merged")
        }

        @Test
        fun `a call that never reached the model is not reported as a bad answer`() = runTest {
            givenRun(rows, pool, null)
            val session = mockk<ChatSession>(relaxed = true)
            every { session.promptWithHistory(any()) } returns streamOf(null)
            every { session.lastEndReason } returns "error"
            coEvery { sessionManager.getOrCreateSession(any<AgentContext>(), any(), any()) } returns session

            val report = agent().consolidate(scope, owner, type)

            assertEquals(1, report.llmFailures)
            assertEquals(0, report.rejectedBatches, "a model that was never asked did not answer badly")
            assertEquals(2, report.uncoveredDocIds, "the entries are still unaccounted for either way")
            assertTrue(report.llmFailureReason!!.contains("com.openai.core.JsonValue"))
            coVerify(exactly = 0) { store.writeEntry(any(), any(), any()) }
        }

        @Test
        fun `a merge refusal is declared, not left as a silent gap`() = runTest {
            givenRun(
                rows,
                pool,
                groupingAnswer(listOf(listOf("doc-a", "doc-b")), leftovers = emptyList()),
                mergeAnswer(emptyList(), unmerged = listOf("doc-a", "doc-b"))
            )

            val report = agent().consolidate(scope, owner, type)

            assertEquals(0, report.uncoveredDocIds, "refusing is an answer; silence is not")
            assertEquals(0, report.mergedWritten)
            assertEquals(0, report.rejectedBatches)
        }
    }

    private fun UserMessage.text(): String = content.filterIsInstance<TextContent>().joinToString("") { it.text }

}
