package com.easy.easyai.core.agent

import com.easy.easyai.core.model.EasyAiMessage
import java.time.Instant

/**
 * Data model for persisting a session.
 * Contains the minimal information needed to recreate a session with its configuration.
 * Note: configId and modelId are stored per-message (in the Message table), not on the session.
 * Use AsyncSessionStore.getLastMessageConfig to retrieve them.
 */
data class PersistedSession(
    val id: String,
    val messages: List<EasyAiMessage>,
    val projectId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val swarmRunId: String? = null,
    val swarmTaskId: String? = null
)
