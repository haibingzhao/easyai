package com.easy.easyai.core.task

import com.easy.easyai.core.memory.ConsolidationReport
import com.easy.easyai.core.memory.MemoryConsolidationAgent
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSessionTitlePort
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [MemoryConsolidationTaskHandler]'s payload restoration: it must rebuild the slice, owner,
 * visibility set, type and dry-run flag from the persisted [AsyncTask.payload] (so a task survives a
 * restart), mint/link the hidden session, and serialize the agent's report into the task result.
 * The agent itself is mocked.
 */
class MemoryConsolidationTaskHandlerTest {

    private val agent = mockk<MemoryConsolidationAgent>(relaxed = true)
    private val taskStore = mockk<AsyncTaskStore>(relaxed = true)
    private val titlePort = mockk<MemoryConsolidationSessionTitlePort>(relaxed = true)

    private fun task(payload: Map<String, Any?>, sessionId: String? = "session-1", retryCount: Int = 0) = AsyncTask(
        id = "task-1",
        userId = "alice",
        type = AsyncTaskType.MEMORY_CONSOLIDATION,
        trigger = TaskTrigger.SCHEDULE,
        title = "记忆整理",
        sessionId = sessionId,
        payload = payload,
        retryCount = retryCount
    )

    @Test
    fun `restores slice, owner, type and dryRun from payload and serializes the report`() = runTest {
        val scopeSlot = slot<MemoryScope>()
        val ownerSlot = slot<MemoryOwnerContext>()
        val typeSlot = slot<MemoryType>()
        val ownersSlot = slot<Collection<String>>()
        val dryRunSlot = slot<Boolean>()
        val report = ConsolidationReport(
            type = MemoryType.EXPERIENCE_LESSONS,
            scope = MemoryScope.PROJECT,
            activeBefore = 7,
            mergedWritten = 2,
            archived = 5,
            skippedSingletons = 2,
            skippedOverCap = 1,
            dryRun = true,
            sessionId = "session-1",
            plan = listOf(mapOf("title" to "cluster-a", "sources" to 3))
        )
        coEvery {
            agent.consolidate(
                capture(scopeSlot),
                capture(ownerSlot),
                capture(typeSlot),
                capture(ownersSlot),
                "session-1",
                capture(dryRunSlot)
            )
        } returns report

        val handler = MemoryConsolidationTaskHandler(agent, taskStore, titlePort)
        val outcome = handler.execute(
            task(
                mapOf(
                    MemoryConsolidationTaskHandler.KEY_SCOPE to "project",
                    MemoryConsolidationTaskHandler.KEY_USER_ID to "alice",
                    MemoryConsolidationTaskHandler.KEY_PROJECT_PATH to "/tmp/demo",
                    MemoryConsolidationTaskHandler.KEY_OWNERS to listOf("alice", "system"),
                    MemoryConsolidationTaskHandler.KEY_TYPE to "experience_lessons",
                    MemoryConsolidationTaskHandler.KEY_SESSION_ID to "session-1",
                    MemoryConsolidationTaskHandler.KEY_DRY_RUN to true
                )
            )
        )

        assertTrue(outcome is TaskOutcome.Success)
        val result = (outcome as TaskOutcome.Success).result
        assertEquals("experience_lessons", result["type"])
        assertEquals("PROJECT", result["scope"])
        assertEquals(7, result["activeBefore"])
        assertEquals(2, result["mergedWritten"])
        assertEquals(5, result["archived"])
        assertEquals(true, result["dryRun"])
        assertEquals("session-1", result["sessionId"])
        assertNull(result["message"], "the persisted result carries numbers only; the UI localizes them")

        assertEquals(MemoryScope.PROJECT, scopeSlot.captured)
        assertEquals("alice", ownerSlot.captured.userId)
        assertEquals(Path.of("/tmp/demo"), ownerSlot.captured.projectPath)
        assertEquals(MemoryType.EXPERIENCE_LESSONS, typeSlot.captured)
        assertEquals(listOf("alice", "system"), ownersSlot.captured)
        assertEquals(true, dryRunSlot.captured)
        coVerify { titlePort.rename("session-1", match { it.contains("experience_lessons") }, "alice") }
    }

    @Test
    fun `a retry does not rename the hidden session again`() = runTest {
        coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
            ConsolidationReport(type = MemoryType.USER_PREFERENCES, scope = MemoryScope.GLOBAL, activeBefore = 0)

        val handler = MemoryConsolidationTaskHandler(agent, taskStore, titlePort)
        handler.execute(
            task(
                mapOf(
                    MemoryConsolidationTaskHandler.KEY_SCOPE to "global",
                    MemoryConsolidationTaskHandler.KEY_USER_ID to "alice",
                    MemoryConsolidationTaskHandler.KEY_TYPE to "user_preferences"
                ),
                retryCount = 1
            )
        )

        coVerify(exactly = 0) { titlePort.rename(any(), any(), any()) }
    }

    @Test
    fun `a task without a session mints one and links it back to the task row`() = runTest {
        coEvery { agent.consolidate(any(), any(), any(), any(), any(), any()) } returns
            ConsolidationReport(type = MemoryType.USER_PREFERENCES, scope = MemoryScope.GLOBAL, activeBefore = 0)

        val handler = MemoryConsolidationTaskHandler(agent, taskStore, titlePort)
        val outcome = handler.execute(
            task(
                mapOf(
                    MemoryConsolidationTaskHandler.KEY_SCOPE to "global",
                    MemoryConsolidationTaskHandler.KEY_TYPE to "user_preferences"
                ),
                sessionId = null
            )
        )

        assertTrue(outcome is TaskOutcome.Success)
        coVerify { taskStore.updateSessionId("task-1", match { it.isNotBlank() }) }
    }

    @Test
    fun `an invalid scope in the payload fails the task`() = runTest {
        val handler = MemoryConsolidationTaskHandler(agent, taskStore, titlePort)
        val outcome = handler.execute(task(mapOf(MemoryConsolidationTaskHandler.KEY_SCOPE to "nonsense")))
        assertTrue(outcome is TaskOutcome.Failure)
    }

    @Test
    fun `an unknown memory type fails the task`() = runTest {
        val handler = MemoryConsolidationTaskHandler(agent, taskStore, titlePort)
        val outcome = handler.execute(
            task(
                mapOf(
                    MemoryConsolidationTaskHandler.KEY_SCOPE to "global",
                    MemoryConsolidationTaskHandler.KEY_TYPE to "not_a_type"
                )
            )
        )
        assertTrue(outcome is TaskOutcome.Failure)
    }
}
