package com.easy.easyai.core.memory.consolidation

/**
 * Execution-status bridge for consolidation runs. The agent runs in `easyai-core`, while the
 * streaming/active status registry lives in the repository layer, so the wiring layer supplies an
 * implementation backed by `SessionExecutionService` (and stays optional for hosts without it).
 */
fun interface MemoryConsolidationRunLifecycle {
    fun begin(sessionId: String, userId: String): MemoryConsolidationRunHandle
}

/** Handle returned by [MemoryConsolidationRunLifecycle.begin]; ending is best-effort and idempotent. */
fun interface MemoryConsolidationRunHandle {
    fun end(endReason: String?)
}
