package com.easy.easyai.core.task

import com.easy.easyai.core.memory.ConsolidationReport
import com.easy.easyai.core.memory.MemoryConsolidationAgent
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSessionTitlePort
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.LocalDate
import java.util.UUID

/**
 * [TaskHandler] bridging the async-task subsystem to [MemoryConsolidationAgent].
 *
 * The target slice and type are restored entirely from [AsyncTask.payload], so a task submitted
 * before a restart can be re-run. Runs are backed by a hidden session (id stored on the task row and
 * payload): the retry path reuses it, which keeps the full transcript of every attempt in one place.
 */
class MemoryConsolidationTaskHandler(
    private val agent: MemoryConsolidationAgent,
    private val taskStore: AsyncTaskStore? = null,
    private val titlePort: MemoryConsolidationSessionTitlePort? = null
) : TaskHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: AsyncTaskType = AsyncTaskType.MEMORY_CONSOLIDATION

    override suspend fun execute(task: AsyncTask): TaskOutcome {
        val payload = task.payload
        val scope = (payload[KEY_SCOPE] as? String)
            ?.let { runCatching { MemoryScope.valueOf(it.uppercase()) }.getOrNull() }
            ?: return TaskOutcome.Failure("Invalid or missing scope in task payload")
        val userId = payload[KEY_USER_ID] as? String ?: task.userId
        val projectPath = (payload[KEY_PROJECT_PATH] as? String)?.takeIf { it.isNotBlank() }
        val owner = MemoryOwnerContext(userId = userId, projectPath = projectPath?.let { Paths.get(it) })
        val owners = (payload[KEY_OWNERS] as? List<*>)?.filterIsInstance<String>().orEmpty()
            .ifEmpty { listOf(userId) }
        val typeDirName = payload[KEY_TYPE] as? String
            ?: return TaskOutcome.Failure("Invalid or missing memory type in task payload")
        val memoryType = MemoryType.fromDirName(typeDirName)
            ?: return TaskOutcome.Failure("Unknown memory type '$typeDirName'")
        val dryRun = payload[KEY_DRY_RUN] as? Boolean ?: false

        val sessionId = (payload[KEY_SESSION_ID] as? String)?.takeIf { it.isNotBlank() }
            ?: task.sessionId
            ?: UUID.randomUUID().toString()
        if (task.sessionId.isNullOrBlank()) {
            taskStore?.updateSessionId(task.id, sessionId)
        }

        logger.debug(
            "Running memory consolidation task {}: scope={}, type={}, dryRun={}, session={}",
            task.id, scope, memoryType.dirName, dryRun, sessionId
        )

        val report = try {
            agent.consolidate(
                scope = scope,
                owner = owner,
                type = memoryType,
                owners = owners,
                sessionId = sessionId,
                dryRun = dryRun
            )
        } finally {
            // The session row only exists after the agent created it; renaming earlier would no-op.
            if (task.retryCount == 0) {
                titlePort?.rename(sessionId, sessionTitle(memoryType), userId)
            }
        }
        return TaskOutcome.Success(report.toResult())
    }

    private fun sessionTitle(type: MemoryType): String =
        "Memory consolidation · ${type.dirName} · ${LocalDate.now()}"

    /** Structured numbers only — display text is rendered per locale by the UI, never persisted. */
    private fun ConsolidationReport.toResult(): Map<String, Any?> = mapOf(
        "type" to type.dirName,
        "scope" to scope.name,
        "activeBefore" to activeBefore,
        "mergedWritten" to mergedWritten,
        "archived" to archived,
        "archiveShortfall" to archiveShortfall,
        "skippedSingletons" to skippedSingletons,
        "skippedOverCap" to skippedOverCap,
        "rejectedBatches" to rejectedBatches,
        "uncoveredDocIds" to uncoveredDocIds,
        "dryRun" to dryRun,
        "sessionId" to sessionId,
        "plan" to plan
    )

    companion object {
        const val KEY_SCOPE = "scope"
        const val KEY_USER_ID = "userId"
        const val KEY_PROJECT_PATH = "projectPath"
        const val KEY_OWNERS = "owners"
        const val KEY_TYPE = "type"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_DRY_RUN = "dryRun"

        /**
         * Identity of the consolidation work unit: one `(scope, project, memory type)` slice.
         *
         * Duplicate suppression is per slice, never per task — two runs over the same slice read the
         * same active set, each write their own merged entries, and fight over the same sources to
         * archive, which is exactly the duplicate the consolidation exists to remove. Both the scheduled
         * planner and the manual web entry point key on this so they agree.
         */
        @JvmStatic
        fun sliceKey(scope: MemoryScope, projectPath: String?, type: MemoryType): String =
            "${scope.name.lowercase()}|${projectPath ?: ""}|${type.dirName}"

        /** The [sliceKey] of an already-built task, or null when its payload does not name one slice. */
        @JvmStatic
        fun sliceKeyOf(task: AsyncTask): String? {
            val scope = task.payload[KEY_SCOPE] as? String ?: return null
            val type = task.payload[KEY_TYPE] as? String ?: return null
            val projectPath = (task.payload[KEY_PROJECT_PATH] as? String).orEmpty()
            return "$scope|$projectPath|$type"
        }
    }
}
