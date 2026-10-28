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
    /**
     * The application that created this workspace, stamped by the store on insert. Only its orphan
     * sweep may release a [ProjectKind.TEMP] row and delete the directory behind it: hosts sharing
     * one data directory would otherwise delete each other's in-flight scratch space. User projects
     * are shared content and are never filtered on this.
     */
    val ownerApp: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
