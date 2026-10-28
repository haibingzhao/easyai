package com.easy.easyai.web.controller

import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.watch.WatchConflictException
import com.easy.easyai.core.watch.WatchException
import com.easy.easyai.core.watch.WatchNotFoundException
import com.easy.easyai.core.watch.WatchService
import com.easy.easyai.core.watch.WatchUnavailableException
import com.easy.easyai.repository.database.UserScope
import com.easy.easyai.web.model.AsyncTaskDto
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * REST surface over the durable `async_task` table — the read/control plane for the global Tasks
 * page and the Session Summary panel. Execution is decoupled from any session, so this controller
 * only lists/inspects tasks and issues control commands (cancel / retry / delete); the app-level
 * [AsyncTaskRunner] owns the running state.
 *
 * Ownership is `user_id` strict: a caller may only see and act on their own tasks. Every by-id
 * operation loads the row and verifies [UserScope.matchesStrict] before delegating. Accounts are
 * application-scoped, so this also isolates two applications sharing one database.
 */
@RestController
@RequestMapping("/api/tasks")
class TaskController(
    @param:Autowired(required = false) private val store: AsyncTaskStore? = null,
    @param:Autowired(required = false) private val runner: AsyncTaskRunner? = null,
    private val watchService: WatchService? = null
) {

    @GetMapping
    fun listTasks(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) sessionId: String?,
        @RequestParam(defaultValue = "100") limit: Int
    ): Mono<List<AsyncTaskDto>> = mono {
        val taskStore = store ?: return@mono emptyList()
        val userId = getCurrentUserId()
        taskStore.listByUser(
            userId = userId,
            type = type?.let { parseType(it) },
            status = status?.let { parseStatus(it) },
            sessionId = sessionId?.takeIf { it.isNotBlank() },
            limit = limit.coerceIn(1, 500)
        ).map { it.toDto() }
    }

    @GetMapping("/{id}")
    fun getTask(@PathVariable id: String): Mono<AsyncTaskDto> = mono {
        ownedTask(id).toDto()
    }

    @PostMapping("/{id}/cancel")
    fun cancelTask(@PathVariable id: String): Mono<Map<String, Boolean>> = mono {
        val task = ownedTask(id)
        val taskRunner = runner ?: throw runnerUnavailable()
        if (task.status.isTerminal) {
            mapOf("cancelled" to false)
        } else {
            mapOf("cancelled" to taskRunner.cancel(id))
        }
    }

    /**
     * Reset a terminal task in place (status → PENDING, clear error/result, bump retry_count) and
     * hand it back to the runner. Reuses the same task id — the retry is the same logical unit.
     */
    @PostMapping("/{id}/retry")
    fun retryTask(@PathVariable id: String): Mono<AsyncTaskDto> = mono {
        val task = ownedTask(id)
        val taskStore = store ?: throw storeUnavailable()
        val taskRunner = runner ?: throw runnerUnavailable()
        if (!task.status.isTerminal) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Only a finished task can be retried")
        }
        if (task.type == AsyncTaskType.WATCH) {
            val service = watchService ?: throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Watch scheduling is not enabled in this deployment"
            )
            onWatch { service.retryRun(getCurrentUserId(), task) }.toDto()
        } else {
            val reset = taskStore.retry(id) ?: throw notFound(id)
            taskRunner.dispatch(reset)
            reset.toDto()
        }
    }

    @DeleteMapping("/{id}")
    fun deleteTask(@PathVariable id: String): Mono<Map<String, Boolean>> = mono {
        val taskStore = store ?: throw storeUnavailable()
        val task = ownedTask(id)
        // Watch workers must finish unwinding before their admission row can disappear.
        if (task.type == AsyncTaskType.WATCH) {
            (runner ?: throw runnerUnavailable()).cancelAndAwait(id)
        } else {
            runner?.cancel(id)
        }
        mapOf("deleted" to taskStore.delete(id))
    }

    /** Bulk manual cleanup of the caller's tasks, optionally only those in [status]. */
    @DeleteMapping
    fun clearTasks(@RequestParam(required = false) status: String?): Mono<Map<String, Int>> = mono {
        val taskStore = store ?: throw storeUnavailable()
        val userId = getCurrentUserId()
        val parsedStatus = status?.let { parseStatus(it) }
        // Cancel any in-flight workers about to be deleted, so they do not outlive their row.
        val taskRunner = runner
        if (taskRunner != null) {
            taskStore.listByUser(userId = userId, status = parsedStatus, limit = 500).forEach {
                if (it.type == AsyncTaskType.WATCH) taskRunner.cancelAndAwait(it.id)
                else if (!it.status.isTerminal) taskRunner.cancel(it.id)
            }
        }
        val deleted = taskStore.deleteByUser(userId, parsedStatus)
        mapOf("deleted" to deleted)
    }

    private suspend fun <T> onWatch(block: suspend () -> T): T = try {
        block()
    } catch (e: WatchException) {
        throw when (e) {
            is WatchNotFoundException -> ResponseStatusException(HttpStatus.NOT_FOUND, e.message)
            is WatchConflictException -> ResponseStatusException(HttpStatus.CONFLICT, e.message)
            is WatchUnavailableException -> ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message)
            else -> ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
    }

    private suspend fun ownedTask(id: String): AsyncTask {
        val taskStore = store ?: throw storeUnavailable()
        val task = taskStore.findById(id) ?: throw notFound(id)
        if (!UserScope.matchesStrict(task.userId, getCurrentUserId())) throw notFound(id)
        return task
    }

    private fun notFound(id: String) = ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found: $id")
    private fun storeUnavailable() = ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Task store is not available")
    private fun runnerUnavailable() = ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Task runner is not available")

    /**
     * Task types are open keys contributed by the host, so only a blank or oversized key is invalid
     * here. An unknown-but-valid key simply lists nothing.
     */
    private fun parseType(type: String): AsyncTaskType {
        val key = type.trim()
        if (key.isEmpty() || key.length > TASK_TYPE_MAX_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task type: $type")
        }
        return AsyncTaskType(key)
    }

    private fun parseStatus(status: String): AsyncTaskStatus =
        runCatching { AsyncTaskStatus.valueOf(status.uppercase()) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task status: $status")

    private fun AsyncTask.toDto(): AsyncTaskDto = AsyncTaskDto(
        id = id,
        type = type.key,
        status = status.name.lowercase(),
        trigger = trigger.name.lowercase(),
        title = title,
        sessionId = sessionId,
        timeoutMs = timeoutMs,
        payload = payload.takeIf { it.isNotEmpty() },
        result = result,
        error = error,
        retryCount = retryCount,
        retriedAt = retriedAt,
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt,
        updatedAt = updatedAt,
        durationMs = durationMs
    )

    private companion object {
        /** Matches the varchar width of `async_task.type` and `scheduled_task_setting.task_type`. */
        const val TASK_TYPE_MAX_LENGTH = 64
    }
}
