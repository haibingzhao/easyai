package com.easy.easyai.web.controller

import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.TaskTrigger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests [TaskController]'s control plane and its strict per-user isolation. With no security context
 * `getCurrentUserId()` resolves to `system`, so a `system`-owned task is reachable while an
 * `alice`-owned one is a 404 — the ownership guard the global Tasks page relies on.
 */
class TaskControllerTest {

    private fun task(
        id: String,
        userId: String = "system",
        status: AsyncTaskStatus = AsyncTaskStatus.COMPLETED,
        retryCount: Int = 0
    ) = AsyncTask(
        id = id,
        userId = userId,
        type = AsyncTaskType.MEMORY_CONSOLIDATION,
        trigger = TaskTrigger.FRONTEND,
        title = "记忆归并",
        status = status,
        retryCount = retryCount,
        createdAt = 1_000,
        updatedAt = 2_000
    )

    @Test
    fun `list is empty when no store is wired`() {
        val controller = TaskController(null, null)
        val result = controller.listTasks(null, null, null, 100).block()
        assertTrue(result != null && result.isEmpty())
    }

    @Test
    fun `get fails 503 when no store is wired`() {
        val controller = TaskController(null, null)
        val e = assertFailsWith<ResponseStatusException> { controller.getTask("x").block() }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.statusCode)
    }

    @Test
    fun `list maps rows to dtos for the current user`() = runTest {
        val store = mockk<AsyncTaskStore>()
        coEvery { store.listByUser("system", null, null, null, 100) } returns listOf(task("t1"))
        val controller = TaskController(store, null)

        val dtos = controller.listTasks(null, null, null, 100).block()

        assertEquals(1, dtos?.size)
        assertEquals("t1", dtos?.first()?.id)
        assertEquals("memory_consolidation", dtos?.first()?.type)
        assertEquals("completed", dtos?.first()?.status)
        assertEquals("frontend", dtos?.first()?.trigger)
    }

    @Test
    fun `another user's task is not found`() = runTest {
        val store = mockk<AsyncTaskStore>()
        coEvery { store.findById("t2") } returns task("t2", userId = "alice")
        val controller = TaskController(store, null)

        val e = assertFailsWith<ResponseStatusException> { controller.getTask("t2").block() }
        assertEquals(HttpStatus.NOT_FOUND, e.statusCode)
    }

    @Test
    fun `cancel delegates to the runner for a live task`() = runTest {
        val store = mockk<AsyncTaskStore>()
        val runner = mockk<AsyncTaskRunner>()
        coEvery { store.findById("t1") } returns task("t1", status = AsyncTaskStatus.RUNNING)
        coEvery { runner.cancel("t1") } returns true
        val controller = TaskController(store, runner)

        val result = controller.cancelTask("t1").block()

        assertEquals(true, result?.get("cancelled"))
    }

    @Test
    fun `cancel of a terminal task is a no-op`() = runTest {
        val store = mockk<AsyncTaskStore>()
        val runner = mockk<AsyncTaskRunner>()
        coEvery { store.findById("t1") } returns task("t1", status = AsyncTaskStatus.COMPLETED)
        val controller = TaskController(store, runner)

        val result = controller.cancelTask("t1").block()

        assertEquals(false, result?.get("cancelled"))
    }

    @Test
    fun `retry resets a terminal task and redispatches it`() = runTest {
        val store = mockk<AsyncTaskStore>()
        val runner = mockk<AsyncTaskRunner>(relaxed = true)
        coEvery { store.findById("t1") } returns task("t1", status = AsyncTaskStatus.FAILED)
        coEvery { store.retry("t1") } returns task("t1", status = AsyncTaskStatus.PENDING, retryCount = 1)
        every { runner.dispatch(any()) } returns Unit
        val controller = TaskController(store, runner)

        val dto = controller.retryTask("t1").block()

        assertEquals("pending", dto?.status)
        assertEquals(1, dto?.retryCount)
    }

    @Test
    fun `retry of a still-running task conflicts`() = runTest {
        val store = mockk<AsyncTaskStore>()
        val runner = mockk<AsyncTaskRunner>(relaxed = true)
        coEvery { store.findById("t1") } returns task("t1", status = AsyncTaskStatus.RUNNING)
        val controller = TaskController(store, runner)

        val e = assertFailsWith<ResponseStatusException> { controller.retryTask("t1").block() }
        assertEquals(HttpStatus.CONFLICT, e.statusCode)
    }

    @Test
    fun `delete removes an owned task`() = runTest {
        val store = mockk<AsyncTaskStore>()
        coEvery { store.findById("t1") } returns task("t1")
        coEvery { store.delete("t1") } returns true
        val controller = TaskController(store, null)

        val result = controller.deleteTask("t1").block()

        assertEquals(true, result?.get("deleted"))
    }

    @Test
    fun `clear deletes by status for the current user`() = runTest {
        val store = mockk<AsyncTaskStore>()
        coEvery { store.deleteByUser("system", AsyncTaskStatus.COMPLETED) } returns 3
        val controller = TaskController(store, null)

        val result = controller.clearTasks("completed").block()

        assertEquals(3, result?.get("deleted"))
    }
}
