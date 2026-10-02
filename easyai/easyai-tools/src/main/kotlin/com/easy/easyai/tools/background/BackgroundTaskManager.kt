package com.easy.easyai.tools.background

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.event.CustomEvent
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-session manager for background tasks.
 * Handles task lifecycle, coroutine management, and result injection.
 *
 * @param sessionId Session that owns this manager
 * @param scope CoroutineScope for launching background tasks (SupervisorJob + Dispatchers.Default)
 * @param sessionLookup Dynamic lookup for the ChatSession instance
 * @param isExecutingCheck Check if the session is currently executing (loop running)
 * @param eventPublisher Lambda to publish CustomEvent for SSE streaming
 * @param autoResumeTrigger Lambda to trigger auto-resume when loop is idle
 */
class BackgroundTaskManager(
    val sessionId: String,
    private val scope: CoroutineScope,
    private val sessionLookup: () -> ChatSession?,
    private val isExecutingCheck: () -> Boolean,
    private val eventPublisher: (CustomEvent) -> Unit,
    private val autoResumeTrigger: suspend (sessionId: String, userId: String) -> Unit
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val tasks = ConcurrentHashMap<String, BackgroundTask>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val resumeGuard = AtomicBoolean(false)

    /**
     * Launch a background task.
     *
     * @param taskId Unique task identifier (UUID)
     * @param toolName Target tool to execute
     * @param toolArgs Arguments for the target tool
     * @param description Optional human-readable description
     * @param parentToolCallId Tool call ID of the run_background invocation
     * @param agentContext Agent context for tool execution
     * @param targetTool The ToolDefinition to execute
     * @return The taskId
     */
    fun launch(
        taskId: String,
        toolName: String,
        toolArgs: Map<String, Any?>,
        description: String?,
        parentToolCallId: String?,
        agentContext: AgentContext,
        targetTool: ToolDefinition
    ): String {
        val task = BackgroundTask(
            taskId = taskId,
            sessionId = sessionId,
            userId = agentContext.userId,
            toolName = toolName,
            toolArgs = toolArgs,
            description = description,
            parentToolCallId = parentToolCallId
        )
        tasks[taskId] = task

        // Publish launched event
        publishLaunched(taskId, toolName, description)

        val job = scope.launch {
            try {
                logger.info("Background task started: taskId={}, tool={}", taskId, toolName)

                val result = targetTool.execute(
                    agentContext = agentContext,
                    toolCallId = "bg-$taskId",
                    messageId = null,
                    args = toolArgs,
                    coroutineScope = this,
                    onUpdate = { update ->
                        // Forward progress updates as SSE events
                        if (update is ToolUpdate.Progress) {
                            publishProgress(taskId, toolName, update.message)
                        }
                    }
                )

                // Extract result text
                val resultText = result.content
                    .filterIsInstance<com.easy.easyai.core.model.TextContent>()
                    .joinToString("") { it.text }

                // Update task status
                val completedTask = task.copy(
                    status = if (result.isError) BackgroundTaskStatus.FAILED else BackgroundTaskStatus.COMPLETED,
                    result = if (!result.isError) resultText else null,
                    error = if (result.isError) resultText else null,
                    completedAt = System.currentTimeMillis()
                )
                tasks[taskId] = completedTask

                if (result.isError) {
                    logger.warn("Background task failed: taskId={}, error={}", taskId, resultText)
                    publishFailed(taskId, toolName, resultText, completedTask.startedAt)
                } else {
                    logger.info("Background task completed: taskId={}", taskId)
                    publishCompleted(taskId, toolName, resultText, completedTask.startedAt)
                }

                // Inject result into agent
                injectResult(completedTask)

            } catch (e: CancellationException) {
                logger.info("Background task cancelled: taskId={}", taskId)
                val cancelledTask = task.copy(
                    status = BackgroundTaskStatus.CANCELLED,
                    completedAt = System.currentTimeMillis()
                )
                tasks[taskId] = cancelledTask
                publishCancelled(taskId, toolName)
                throw e
            } catch (e: Exception) {
                logger.error("Background task error: taskId={}", taskId, e)
                val failedTask = task.copy(
                    status = BackgroundTaskStatus.FAILED,
                    error = e.message ?: "Unknown error",
                    completedAt = System.currentTimeMillis()
                )
                tasks[taskId] = failedTask
                publishFailed(taskId, toolName, e.message ?: "Unknown error", failedTask.startedAt)
                injectResult(failedTask)
            } finally {
                jobs.remove(taskId)
            }
        }

        jobs[taskId] = job
        return taskId
    }

    /**
     * Get task status by ID.
     */
    fun getStatus(taskId: String): BackgroundTask? = tasks[taskId]

    /**
     * List all tasks, optionally filtered by status.
     */
    fun listTasks(statusFilter: BackgroundTaskStatus? = null): List<BackgroundTask> {
        return if (statusFilter == null) {
            tasks.values.toList()
        } else {
            tasks.values.filter { it.status == statusFilter }
        }
    }

    /**
     * Cancel a specific task.
     * @return true if the task was found and cancelled
     */
    fun cancelTask(taskId: String): Boolean {
        val job = jobs[taskId] ?: return false
        job.cancel()
        return true
    }

    /**
     * Cancel all running tasks. Called when session is destroyed or aborted.
     */
    fun cancelAll() {
        logger.info("Cancelling all background tasks for session {}", sessionId)
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    /**
     * Inject task result into the agent's steering queue.
     * If the agent loop is running, it will pick up the message on the next iteration.
     * If the loop is idle, trigger auto-resume to process the message.
     */
    private suspend fun injectResult(task: BackgroundTask) {
        val session = sessionLookup()
        if (session == null) {
            logger.warn("Cannot inject result: session not found for taskId={}", task.taskId)
            return
        }

        val statusText = when (task.status) {
            BackgroundTaskStatus.COMPLETED -> "completed successfully"
            BackgroundTaskStatus.FAILED -> "failed"
            BackgroundTaskStatus.CANCELLED -> "was cancelled"
            else -> return
        }

        val descriptionText = task.description?.let { "'$it'" } ?: "task"
        val resultPreview = (task.result ?: task.error ?: "")
            .take(2000)
            .let { if (it.length == 2000) "$it..." else it }

        val message = buildString {
            append("[System: Background $descriptionText (id=${task.taskId}, tool=${task.toolName}) $statusText.")
            if (resultPreview.isNotBlank()) {
                append("\nResult: $resultPreview")
            }
            append("]")
        }

        session.steer(
            UserMessage(
                content = listOf(TextContent(message)),
                metadata = mapOf(
                    UserMessage.SOURCE_KEY to UserMessage.SOURCE_STEERING,
                    UserMessage.SYSTEM_ORIGIN_KEY to UserMessage.ORIGIN_BACKGROUND_TASK
                )
            )
        )

        // Check if we need to auto-resume (loop is idle)
        if (!isExecutingCheck()) {
            if (resumeGuard.compareAndSet(false, true)) {
                try {
                    val userId = session.agentContext.userId
                    if (userId != null) {
                        logger.info("Triggering auto-resume for background task completion: taskId={}", task.taskId)
                        autoResumeTrigger(sessionId, userId)
                    }
                } finally {
                    resumeGuard.set(false)
                }
            }
        }
    }

    private fun publishLaunched(taskId: String, toolName: String, description: String?) {
        eventPublisher(CustomEvent(
            customType = "background_task",
            sessionId = sessionId,
            metadata = mapOf(
                "event" to "launched",
                "taskId" to taskId,
                "toolName" to toolName,
                "description" to description
            )
        ))
    }

    private fun publishProgress(taskId: String, toolName: String, message: String) {
        eventPublisher(CustomEvent(
            customType = "background_task",
            sessionId = sessionId,
            metadata = mapOf(
                "event" to "progress",
                "taskId" to taskId,
                "toolName" to toolName,
                "message" to message
            )
        ))
    }

    private fun publishCompleted(taskId: String, toolName: String, result: String, startedAt: Long) {
        eventPublisher(CustomEvent(
            customType = "background_task",
            sessionId = sessionId,
            metadata = mapOf(
                "event" to "completed",
                "taskId" to taskId,
                "toolName" to toolName,
                "result" to result.take(500),
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        ))
    }

    private fun publishFailed(taskId: String, toolName: String, error: String, startedAt: Long) {
        eventPublisher(CustomEvent(
            customType = "background_task",
            sessionId = sessionId,
            metadata = mapOf(
                "event" to "failed",
                "taskId" to taskId,
                "toolName" to toolName,
                "error" to error.take(500),
                "durationMs" to (System.currentTimeMillis() - startedAt)
            )
        ))
    }

    private fun publishCancelled(taskId: String, toolName: String) {
        eventPublisher(CustomEvent(
            customType = "background_task",
            sessionId = sessionId,
            metadata = mapOf(
                "event" to "cancelled",
                "taskId" to taskId,
                "toolName" to toolName
            )
        ))
    }
}
