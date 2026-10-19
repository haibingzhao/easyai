package com.easy.easyai.core.memory.consolidation

/**
 * Names the hidden session backing a consolidation run. Optional: hosts without a session-title
 * store simply keep the default title; the run itself is unaffected.
 */
fun interface MemoryConsolidationSessionTitlePort {
    suspend fun rename(sessionId: String, title: String, userId: String)
}
