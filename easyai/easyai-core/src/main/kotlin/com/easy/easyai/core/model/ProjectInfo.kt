package com.easy.easyai.core.model

import java.time.Instant

/**
 * Project entity representing a workspace/project.
 * A project corresponds to a working directory, and sessions are isolated by project.
 */
data class ProjectInfo(
    val id: String,
    val name: String,
    val path: String,
    val description: String? = null,
    /** Whether automatic memory generation (MemoryFlushAgent) is enabled for this project. */
    val memoryAutoGeneration: Boolean = true,
    /** [ProjectKind.TEMP] workspaces are system-managed scratch directories, invisible in project lists. */
    val kind: ProjectKind = ProjectKind.USER,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
