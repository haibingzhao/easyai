package com.easy.easyai.repository.project

import com.easy.easyai.core.model.ProjectInfo
import kotlinx.coroutines.flow.Flow

/**
 * Async project storage interface using Exposed R2DBC.
 * All operations are non-blocking and return suspend functions or Flow.
 */
interface AsyncProjectStore {
    /**
     * Save a project. If the project ID already exists, update it.
     */
    suspend fun save(project: ProjectInfo, userId: String = "system")

    /**
     * Find a project by ID.
     */
    suspend fun findById(id: String, userId: String = "system"): ProjectInfo?

    /**
     * Find a project by path for a given user.
     * Path is unique per user (composite unique index on path + userId).
     */
    suspend fun findByPath(path: String, userId: String = "system"): ProjectInfo?

    /**
     * List projects with optional limit and search filter.
     * @param limit Maximum number of projects to return (null = no limit)
     * @param search Filter by name or path (case-insensitive substring match, null = no filter)
     */
    fun findAll(limit: Int? = null, search: String? = null, userId: String = "system"): Flow<ProjectInfo>

    /**
     * Delete a project by ID.
     */
    suspend fun delete(id: String, userId: String = "system")
}