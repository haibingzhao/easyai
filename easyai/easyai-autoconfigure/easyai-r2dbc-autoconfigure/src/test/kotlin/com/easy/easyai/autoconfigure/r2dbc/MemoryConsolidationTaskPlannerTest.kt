package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.memory.MemoryConsolidationSettings
import com.easy.easyai.core.memory.MemoryIndexEntry
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSliceState
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
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests what the consolidation planner proposes: the threshold gate, the two suppression gates that a
 * count alone cannot express (nothing arrived since the last pass / the last pass gained nothing), slice
 * expansion across global and project memory, duplicate suppression against pending tasks, and the draft
 * payload.
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

    /** One listing answers counts, fingerprints and everything else, so that is the call to stub. */
    private fun indexRows(count: Int, type: MemoryType = MemoryType.EXPERIENCE_LESSONS) =
        (1..count).map { index ->
            MemoryIndexEntry(
                docId = "${type.dirName}-$index",
                name = "entry-$index",
                type = type,
                level = 0,
                contentChars = 500,
                blurb = "desc $index"
            )
        }

    /** Rows at [level], with docIds distinct from [indexRows] so a mixed set has a fingerprint of its own. */
    private fun indexRowsAtLevel(
        count: Int,
        level: Int,
        type: MemoryType = MemoryType.EXPERIENCE_LESSONS
    ) = (1..count).map { index ->
        MemoryIndexEntry(
            docId = "${type.dirName}-l$level-$index",
            name = "entry-l$level-$index",
            type = type,
            level = level,
            contentChars = 500,
            blurb = "desc $index"
        )
    }

    /**
     * Only [MemoryType.EXPERIENCE_LESSONS] is over the default threshold of 2; every other type has no
     * rows, so a plan has exactly one candidate slice per scope.
     */
    private fun givenOverThreshold(count: Int = 2) {
        val rows = indexRows(count)
        coEvery { memoryStore.listActiveIndex(MemoryScope.GLOBAL, any(), null) } returns rows
        coEvery { memoryStore.listActiveIndex(MemoryScope.PROJECT, any(), null) } returns rows
    }

    private fun givenGlobalRows(rows: List<MemoryIndexEntry>) {
        coEvery { memoryStore.listActiveIndex(MemoryScope.GLOBAL, any(), null) } returns rows
        coEvery { memoryStore.listActiveIndex(MemoryScope.PROJECT, any(), null) } returns emptyList()
    }

    /** The planner resolves a LocalDateTime through the system zone; the test must not invent its own. */
    private fun epoch(at: LocalDateTime): Long = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

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
    fun `a slice made only of finished digests proposes nothing despite its count`() = runBlocking {
        // Every row sits at the depth ceiling, so the agent would list them, filter to nothing mergeable
        // and return an empty report at once. A raw count turns that into a task row, a hidden session and
        // a full listing per scan — and then bills the no-op to the slice's zero-gain delay.
        givenGlobalRows(indexRowsAtLevel(count = 3, level = 3))

        assertEquals(emptyList(), planner().plan(context()))
    }

    @Test
    fun `the threshold counts entries under the ceiling, not every active one`() = runBlocking {
        // Three digests plus two level-0 rows: five active, but only two are material a run would be
        // offered, which is exactly the default threshold.
        givenGlobalRows(indexRowsAtLevel(count = 3, level = 3) + indexRows(2))

        assertEquals(1, planner().plan(context()).size)
    }

    @Test
    fun `the fingerprint still covers finished digests, so the gate matches what a run records`() = runBlocking {
        // A run records its baseline from a listing it does *not* filter by level. Narrowing this gate to
        // eligible rows would make the two sets permanently unequal, the "nothing changed" branch would
        // never fire, and the slice would be consolidated again at every scan.
        val rows = indexRowsAtLevel(count = 3, level = 3) + indexRows(2)
        givenGlobalRows(rows)
        val user = MemoryConsolidationUserSettings(
            userId = "alice",
            enabled = true,
            sliceStates = mapOf(
                "global||experience_lessons" to MemoryConsolidationSliceState(
                    activeFingerprint = MemoryIndexEntry.fingerprint(rows.map { it.docId }),
                    lastRunAt = epoch(now)
                )
            )
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
    fun `a slice costs one listing no matter how many types it holds`() = runBlocking {
        givenOverThreshold()
        val projects = listOf(ProjectInfo(id = "p1", name = "demo", path = "/tmp/demo"))

        planner().plan(context(projects = projects))

        coVerify(exactly = 1) { memoryStore.listActiveIndex(MemoryScope.GLOBAL, any(), null) }
        coVerify(exactly = 1) { memoryStore.listActiveIndex(MemoryScope.PROJECT, any(), null) }
    }

    @Test
    fun `a slice whose every type is pending is never measured`() = runBlocking {
        givenOverThreshold()
        val pending = MemoryType.entriesFor("coding").map { pendingTask(scope = "global", type = it.dirName) }

        assertEquals(emptyList(), planner().plan(context(pending = pending)))
        coVerify(exactly = 0) { memoryStore.listActiveIndex(MemoryScope.GLOBAL, any(), null) }
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

    @Test
    fun `a slice untouched since the last pass is not asked the same question again`() = runBlocking {
        // The count is still over threshold — merged entries stay active — so only the docId set tells
        // "the store grew" apart from "nothing happened since we curated it".
        val rows = indexRows(2)
        coEvery { memoryStore.listActiveIndex(MemoryScope.GLOBAL, any(), null) } returns rows
        coEvery { memoryStore.listActiveIndex(MemoryScope.PROJECT, any(), null) } returns emptyList()
        val user = MemoryConsolidationUserSettings(
            userId = "alice",
            enabled = true,
            sliceStates = mapOf(
                "global||experience_lessons" to MemoryConsolidationSliceState(
                    activeFingerprint = MemoryIndexEntry.fingerprint(rows.map { it.docId }),
                    lastRunAt = epoch(now)
                )
            )
        )

        assertEquals(emptyList(), planner().plan(context(user = user)))
    }

    @Test
    fun `a slice whose set did change is due again`() = runBlocking {
        givenOverThreshold()
        val user = MemoryConsolidationUserSettings(
            userId = "alice",
            enabled = true,
            sliceStates = mapOf(
                "global||experience_lessons" to MemoryConsolidationSliceState(activeFingerprint = 12345)
            )
        )

        assertEquals(1, planner().plan(context(user = user)).size)
    }

    @Test
    fun `a type in its zero-gain delay proposes nothing, and every deferred type skips the listing`() = runBlocking {
        givenOverThreshold()
        val deferred = MemoryType.entriesFor("coding").associate { memoryType ->
            "global||${memoryType.dirName}" to
                MemoryConsolidationSliceState(zeroGainStreak = 1, nextEligibleAt = epoch(now.plusDays(6)))
        }
        val user = MemoryConsolidationUserSettings(userId = "alice", enabled = true, sliceStates = deferred)

        assertEquals(emptyList(), planner().plan(context(user = user)))
        // The delay is checked before anything is read: a fully deferred slice must not cost a listing.
        coVerify(exactly = 0) { memoryStore.listActiveIndex(MemoryScope.GLOBAL, any(), null) }
    }

    @Test
    fun `a delay that has expired lets the slice through again`() = runBlocking {
        givenOverThreshold()
        val user = MemoryConsolidationUserSettings(
            userId = "alice",
            enabled = true,
            sliceStates = mapOf(
                "global||experience_lessons" to MemoryConsolidationSliceState(
                    zeroGainStreak = 1,
                    nextEligibleAt = epoch(now.minusSeconds(1))
                )
            )
        )

        assertEquals(1, planner().plan(context(user = user)).size)
    }
}
