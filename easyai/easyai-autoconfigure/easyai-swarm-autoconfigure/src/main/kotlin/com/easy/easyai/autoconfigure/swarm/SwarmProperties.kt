package com.easy.easyai.autoconfigure.swarm

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for swarm orchestration.
 *
 * Prefix: easyai.swarm
 */
@ConfigurationProperties(prefix = "easyai.swarm")
data class SwarmProperties(
    /** Whether swarm orchestration is enabled. */
    var enabled: Boolean = false,

    /** Maximum concurrent worker executions. */
    var maxConcurrency: Int = 4,

    /**
     * Controls which events are forwarded to the SSE event stream.
     *
     * - "task": only swarm-level task/lifecycle events (default)
     * - "tool": task events + worker tool-level events
     * - "all":  task events + all worker internal events
     */
    var eventVerbosity: String = "task"
)
