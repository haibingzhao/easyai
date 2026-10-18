package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.memory.MemoryConsolidationSettings
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunHandle
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunLifecycle
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSessionTitlePort
import com.easy.easyai.core.task.ScheduledTaskPlanner
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.SessionExecutionService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * Host-side wiring for the memory-consolidation pieces that need the repository module:
 *
 * - [MemoryConsolidationTaskPlanner]: turns a user's thresholds into the slices due for a pass.
 * - [MemoryConsolidationRunLifecycle]: marks the hidden session `streaming` while a run is live, so
 *   the Tasks page can tell a running run from a queued one.
 * - [MemoryConsolidationSessionTitlePort]: gives the hidden session a readable title once created.
 *
 * Kept separate from `RagAutoConfiguration` because this module depends on `easyai-repository` while
 * the RAG autoconfiguration must not. Runs after [R2dbcRepositoryAutoConfiguration] so the session
 * store, execution service and project store are already registered.
 *
 * No `@EnableScheduling` and no scheduler of its own: [ScheduledTaskAutoConfiguration] owns the single
 * tick, so making another type schedulable means contributing a [ScheduledTaskPlanner] bean here and
 * nothing else.
 */
@AutoConfiguration(after = [R2dbcRepositoryAutoConfiguration::class])
@ConditionalOnProperty(prefix = "easyai.r2dbc", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(
    prefix = "easyai.memory.consolidation",
    name = ["available"],
    havingValue = "true",
    matchIfMissing = true
)
class MemoryConsolidationTaskAutoConfiguration {

    /**
     * Guarded on the concrete type, not on [ScheduledTaskPlanner]: the planner list is a collection,
     * and a missing-bean check on the shared interface would suppress every planner after the first.
     */
    @Bean
    @ConditionalOnMissingBean(MemoryConsolidationTaskPlanner::class)
    fun memoryConsolidationTaskPlanner(
        settings: ObjectProvider<MemoryConsolidationSettings>,
        memoryStore: ObjectProvider<MemoryStore>,
        projectStore: ObjectProvider<AsyncProjectStore>
    ): MemoryConsolidationTaskPlanner = MemoryConsolidationTaskPlanner(
        settings = settings.ifAvailable ?: MemoryConsolidationSettings(),
        memoryStore = memoryStore.ifAvailable,
        projectStore = projectStore.ifAvailable
    )

    @Bean
    @ConditionalOnMissingBean
    fun memoryConsolidationRunLifecycle(
        executions: SessionExecutionService
    ): MemoryConsolidationRunLifecycle = MemoryConsolidationRunLifecycle { sessionId, userId ->
        val handle = executions.beginExecution(sessionId, userId, resetEndReason = true)
        MemoryConsolidationRunHandle { endReason -> executions.endExecution(handle, endReason ?: "normal") }
    }

    @Bean
    @ConditionalOnMissingBean
    fun memoryConsolidationSessionTitlePort(
        sessionStore: AsyncSessionStore
    ): MemoryConsolidationSessionTitlePort = MemoryConsolidationSessionTitlePort { sessionId, title, userId ->
        sessionStore.updateTitle(sessionId, title, userId)
    }
}
