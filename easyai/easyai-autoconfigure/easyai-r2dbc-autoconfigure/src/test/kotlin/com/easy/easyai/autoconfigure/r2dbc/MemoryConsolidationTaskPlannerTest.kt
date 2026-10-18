package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.memory.MemoryConsolidationSettings
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationUserSettings
import com.easy.easyai.core.model.ProjectInfo
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskPlanContext
import com.easy.easyai.core.task.TaskDraft
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.repository.project.AsyncProjectStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests what the consolidation planner proposes: the threshold gate, slice expansion across global and
 * project memory, duplicate suppression against pending tasks, and the draft payload.
 *
 * Due-ness and submission are no longer in scope here — [ScheduledTaskRunnerTest] covers those.
 */
class MemoryConsolidationTaskPlannerTest {

    private val memoryStore = mockk<MemoryStore>()
    private val projectStore = mockk<AsyncProjectStore>()

    private val now = LocalDateTime.of(2026, 10, 17, 3, 0)

    private fun planner(
        settings: MemoryConsolidationSettings = MemoryConsolidationSettings(defaultThreshold = 2)
    ) = MemoryConsolidationTaskPlanner(
        settings = settings,
        memoryStore = memoryStore,
        projectStore = projectStore
    )

    /**
     * Build the plan input for [user], with [projects] as the user's non-temp projects and [pending] as
     * the tasks the runner found not yet settled. The user's view travels through the generic scheduled
     * row, so this also exercises the `typeThresholds` ↔ `params` hop.
     */
    private fun context(
        user: MemoryConsolidationUserSettings = MemoryConsolidationUserSettings(userId = "alice", enabled = true),
        pending: List<AsyncTask> = emptyList(),
        projects: List<ProjectInfo> = emptyList()
    ): ScheduledTaskPlanContext {
        coEvery {
            projectStore.findAll(limit = null, userId = user.userId, includeTemp = false)
        } returns flowOf(*projects.toTypedArray())
        return ScheduledTaskPlanContext(
            userId = user.userId,
            setting = MemoryConsolidationUserSettings.toScheduled(user),
            now = now,
            pendingTasks = pending
        )
    }

    /**
     * Only [MemoryType.EXPERIENCE_LESSONS] is over the default threshold of 2; every other type is
     * absent from the counts, so a plan has exactly one candidate slice per scope. The planner measures
     * a slice with one count query, so that is the call to stub.
     */
    private fun givenOverThreshold(count: Int = 2) {
        coEvery { memoryStore.countActiveByType(MemoryScope.GLOBAL, any()) } returns
            mapOf(MemoryType.EXPERIENCE_LESSONS to count)
        coEvery { memoryStore.countActiveByType(MemoryScope.PROJECT, any()) } returns
            mapOf(MemoryType.EXPERIENCE_LESSONS to count)
    }

    private fun pendingTask(scope: String, type: String, projectPath: String? = null) = AsyncTask(
        id = "existing",
        userId = "alice",
        type = AsyncTaskType.MEMORY_CONSOLIDATION,
        trigger = TaskTrigger.FRONTEND,
        title = "running",
        status = AsyncTaskStatus.RUNNING,
        sessionId = "session-running",
        payload = mapOf("scope" to scope, "type" to type, "projectPath" to projectPath)
    )

    @Test
    fun `an over-threshold slice proposes one draft carrying its own hidden session`() = runBlocking {
        givenOverThreshold()

        val drafts = planner().plan(context())

        assertEquals(1, drafts.size)
        val task = drafts.first()
        assertEquals(AsyncTaskType.MEMORY_CONSOLIDATION, task.type)
        assertEquals("alice", task.userId)
        assertNotNull(task.sessionId)
        assertEquals(task.sessionId, task.payload["sessionId"])
        assertEquals("global", task.payload["scope"])
        assertEquals("experience_lessons", task.payload["type"])
        assertEquals(false, task.payload["dryRun"])
    }

    @Test
    fun `a per-type threshold above the active count proposes nothing`() = runBlocking {
        givenOverThreshold()
        val user = MemoryConsolidationUserSettings(
            userId = "alice",
            enabled = true,
            typeThresholds = mapOf(MemoryType.EXPERIENCE_LESSONS.dirName to 5)
        )

        assertEquals(emptyList(), planner().plan(context(user = user)))
    }

    @Test
    fun `a pending task for the same slice is not duplicated`() = runBlocking {
        givenOverThreshold()
        val pending = listOf(pendingTask(scope = "global", type = "experience_lessons"))

        assertEquals(emptyList(), planner().plan(context(pending = pending)))
    }

    @Test
    fun `a pending task for another slice does not block this one`() = runBlocking {
        givenOverThreshold()
        val pending = listOf(pendingTask(scope = "global", type = "user_profile"))

        assertEquals(1, planner().plan(context(pending = pending)).size)
    }

    @Test
    fun `a slice costs one count query no matter how many types it holds`() = runBlocking {
        givenOverThreshold()
        val projects = listOf(ProjectInfo(id = "p1", name = "demo", path = "/tmp/demo"))

        planner().plan(context(projects = projects))

        coVerify(exactly = 1) { memoryStore.countActiveByType(MemoryScope.GLOBAL, any()) }
        coVerify(exactly = 1) { memoryStore.countActiveByType(MemoryScope.PROJECT, any()) }
    }

    @Test
    fun `a slice whose every type is pending is never measured`() = runBlocking {
        givenOverThreshold()
        val pending = MemoryType.entriesFor("coding").map { pendingTask(scope = "global", type = it.dirName) }

        assertEquals(emptyList(), planner().plan(context(pending = pending)))
        coVerify(exactly = 0) { memoryStore.countActiveByType(MemoryScope.GLOBAL, any()) }
    }

    @Test
    fun `project slices are planned alongside global memory`() = runBlocking {
        givenOverThreshold()
        val projects = listOf(ProjectInfo(id = "p1", name = "demo", path = "/tmp/demo"))

        val drafts = planner().plan(context(projects = projects))

        assertEquals(2, drafts.size)
        assertTrue(drafts.any { it.payload["scope"] == "global" && it.payload["projectPath"] == null })
        assertTrue(
            drafts.any { it.payload["scope"] == "project" && it.payload["projectPath"] == "/tmp/demo" },
            "expected a project draft for /tmp/demo, got ${drafts.map { it.payload }}"
        )
    }

    @Test
    fun `the planner proposes every due slice and only publishes its cap`() = runBlocking {
        // Truncating to the cap is the runner's single-count job; a second copy here would be a second
        // source of truth that could disagree with the one that actually limits submissions.
        givenOverThreshold()
        val projects = listOf(ProjectInfo(id = "p1", name = "demo", path = "/tmp/demo"))
        val capped = planner(MemoryConsolidationSettings(defaultThreshold = 2, maxTasksPerTickPerUser = 1))

        assertEquals(2, capped.plan(context(projects = projects)).size)
        assertEquals(1, capped.perUserPerTickLimit)
    }

    @Test
    fun `the saved owner set travels in the draft payload`() = runBlocking {
        // A tick has no security context, so the group bucket a run resolves its model against must
        // come from the row the user saved, not be re-derived from the user id.
        givenOverThreshold()
        val user = MemoryConsolidationUserSettings(
            userId = "alice",
            enabled = true,
            owners = listOf("alice", "group-1")
        )

        val drafts = planner().plan(context(user = user))

        assertEquals(listOf("alice", "group-1"), drafts.single().payload["owners"])
    }

    @Test
    fun `a row saved without owners still resolves for its own user`() = runBlocking {
        givenOverThreshold()
        val legacyRow = MemoryConsolidationUserSettings.toScheduled(
            MemoryConsolidationUserSettings(userId = "alice", enabled = true)
        ).copy(params = emptyMap())

        val drafts = planner().plan(
            ScheduledTaskPlanContext(userId = "alice", setting = legacyRow, now = now, pendingTasks = emptyList())
        )

        assertEquals(listOf("alice"), drafts.single().payload["owners"])
    }

    @Test
    fun `the deployment kill switch proposes nothing`() = runBlocking {
        givenOverThreshold()

        assertEquals(emptyList(), planner(MemoryConsolidationSettings(available = false, defaultThreshold = 2)).plan(context()))
    }

    @Test
    fun `no memory store means nothing to measure`() = runBlocking {
        val storeless = MemoryConsolidationTaskPlanner(MemoryConsolidationSettings(defaultThreshold = 2), null, projectStore)

        assertEquals(emptyList<TaskDraft>(), storeless.plan(context()))
    }
}
