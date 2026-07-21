package com.easy.easyai.repository.project

import com.easy.easyai.core.model.ProjectInfo
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * R2DBC-based implementation of AsyncProjectStore.
 * Uses Exposed R2DBC for pure async database operations.
 */
class R2dbcAsyncProjectStore(
    private val db: R2dbcDatabase
) : AsyncProjectStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun save(project: ProjectInfo, userId: String) {
        val normalizedPath = project.path.trimEnd('/')
        suspendTransaction(db) {
            val existingCount = Tables.Project
                .selectAll()
                .where { Tables.Project.id eq project.id }
                .count()

            val createdAt = project.createdAt.toEpochMilli()
            val updatedAt = project.updatedAt.toEpochMilli()

            if (existingCount > 0) {
                Tables.Project.update(
                    where = { (Tables.Project.id eq project.id) and UserScope.filterStrict(Tables.Project.userId, userId) }
                ) { p ->
                    p[Tables.Project.name] = project.name
                    p[Tables.Project.path] = normalizedPath
                    p[Tables.Project.description] = project.description
                    p[Tables.Project.memoryAutoGeneration] = project.memoryAutoGeneration
                    p[Tables.Project.updatedAt] = updatedAt
                }
                logger.info("Updated project: {} ({})", project.name, project.id)
            } else {
                Tables.Project.insert {
                    it[Tables.Project.id] = project.id
                    it[Tables.Project.name] = project.name
                    it[Tables.Project.path] = normalizedPath
                    it[Tables.Project.description] = project.description
                    it[Tables.Project.userId] = userId
                    it[Tables.Project.memoryAutoGeneration] = project.memoryAutoGeneration
                    it[Tables.Project.createdAt] = createdAt
                    it[Tables.Project.updatedAt] = updatedAt
                }
                logger.info("Inserted project: {} ({})", project.name, project.id)
            }
        }
    }

    override suspend fun findById(id: String, userId: String): ProjectInfo? {
        return suspendTransaction(db) {
            val row = Tables.Project
                .selectAll()
                .where { (Tables.Project.id eq id) and UserScope.filterStrict(Tables.Project.userId, userId) }
                .limit(1)
                .toList()
                .firstOrNull() ?: return@suspendTransaction null

            toProjectInfo(row)
        }
    }

    override suspend fun findByPath(path: String, userId: String): ProjectInfo? {
        val normalizedPath = path.trimEnd('/')
        return suspendTransaction(db) {
            val row = Tables.Project
                .selectAll()
                .where { (Tables.Project.path eq normalizedPath) and UserScope.filterStrict(Tables.Project.userId, userId) }
                .limit(1)
                .toList()
                .firstOrNull() ?: return@suspendTransaction null

            toProjectInfo(row)
        }
    }

    override fun findAll(limit: Int?, search: String?, userId: String): Flow<ProjectInfo> = flow {
        val rows: List<ResultRow> = suspendTransaction(db) {
            val userFilter = UserScope.filterStrict(Tables.Project.userId, userId)
            val query = Tables.Project
                .selectAll()
                .apply {
                    if (!search.isNullOrBlank()) {
                        val pattern = "%$search%"
                        where {
                            userFilter and (Tables.Project.name like pattern or
                                (Tables.Project.path like pattern))
                        }
                    } else {
                        where(userFilter)
                    }
                }
                .orderBy(Tables.Project.updatedAt to SortOrder.DESC)
                .apply {
                    if (limit != null && limit > 0) {
                        limit(limit)
                    }
                }
                .toList()
            query
        }
        for (row in rows) {
            emit(toProjectInfo(row))
        }
    }

    override suspend fun delete(id: String, userId: String) {
        suspendTransaction(db) {
            val deleted = Tables.Project.deleteWhere {
                (Tables.Project.id eq id) and UserScope.filterStrict(Tables.Project.userId, userId)
            }
            if (deleted > 0) {
                logger.info("Deleted project: {}", id)
            }
        }
    }

    private fun toProjectInfo(row: ResultRow): ProjectInfo {
        return ProjectInfo(
            id = row[Tables.Project.id],
            name = row[Tables.Project.name],
            path = row[Tables.Project.path],
            description = row[Tables.Project.description],
            memoryAutoGeneration = row[Tables.Project.memoryAutoGeneration] ?: true,
            createdAt = Instant.ofEpochMilli(row[Tables.Project.createdAt]),
            updatedAt = Instant.ofEpochMilli(row[Tables.Project.updatedAt])
        )
    }
}
