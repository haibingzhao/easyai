package com.easy.easyai.core.memory

/**
 * Raised when a memory storage backend operation fails (e.g. the remote RAG
 * service is unreachable or returns an error). Callers typically surface the
 * message to the user instead of silently degrading, since the backend is the
 * single source of truth for memories.
 */
class MemoryBackendException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
