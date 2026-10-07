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
     * The project's [com.easy.easyai.core.model.ProjectKind] is persisted on insert only —
     * updating a project never reclassifies it.
     */
    suspend fun save(project: ProjectInfo, userId: String = "system")

    /**
     * Insert a project unless its id is already taken; never updates an existing row.
     *
     * Implementations must detect the id collision (e.g. a unique-constraint violation) and return
     * false rather than overwriting. There is deliberately no default body: delegating to [save]
     * would turn this into an upsert and silently break the "never updates" contract.
     *
     * @return true when this call inserted the row, false when a concurrent writer got there first.
     *   Any other failure propagates, so callers never proceed on a row that does not exist.
     */
    suspend fun saveIfAbsent(project: ProjectInfo, userId: String = "system"): Boolean

    /**
     * Maintenance query: distinct owners of project rows, ignoring user scoping.
     * Only for background reconciliation that must see every user's rows (e.g. the temporary
     * workspace sweep) — never expose it through a request-scoped API.
     */
    suspend fun listDistinctUserIds(): List<String> = emptyList()

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
     * @param includeTemp Include system-managed temporary workspaces (kind = temp).
     *   Defaults to false: temporary workspaces are per-session scratch directories and must not
     *   surface in user-facing project lists. Lookup by id/path ([findById]/[findByPath]) is
     *   never kind-filtered, since chat requests reference temporary projects explicitly.
     */
    fun findAll(
        limit: Int? = null,
        search: String? = null,
        userId: String = "system",
        includeTemp: Boolean = false
    ): Flow<ProjectInfo>

    /**
     * Delete a project by ID.
     */
    suspend fun delete(id: String, userId: String = "system")
}