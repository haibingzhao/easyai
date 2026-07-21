package com.easy.easyai.core.model

/**
 * Status of a tool call in the agent loop.
 * Represents the lifecycle state from creation to completion.
 */
enum class ToolCallStatus {
    /** Tool call created, waiting to be scheduled */
    PENDING,

    /** Tool is currently executing */
    RUNNING,

    /** Tool execution completed successfully */
    COMPLETED,

    /** Tool execution failed */
    FAILED
}