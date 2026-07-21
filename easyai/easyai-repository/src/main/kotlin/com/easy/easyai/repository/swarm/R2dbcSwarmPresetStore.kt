package com.easy.easyai.repository.swarm

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import com.easy.easyai.swarm.model.SwarmAgentSpec
import com.easy.easyai.swarm.model.SwarmPreset
import com.easy.easyai.swarm.model.SwarmTask
import com.easy.easyai.swarm.model.SwarmVariable
import com.easy.easyai.swarm.preset.SwarmPresetStore
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * R2DBC-backed implementation of [SwarmPresetStore].
 * Stores swarm presets in H2/PostgreSQL via Exposed.
 */
class R2dbcSwarmPresetStore(
    private val db: R2dbcDatabase
) : SwarmPresetStore {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = SharedObjectMapper.instance

    override suspend fun save(preset: SwarmPreset, userId: String) {
        val now = System.currentTimeMillis()
        suspendTransaction(db) {
            Tables.SwarmPresetTable.batchInsert(listOf(preset)) { p ->
                this[Tables.SwarmPresetTable.id] = UUID.randomUUID().toString()
                this[Tables.SwarmPresetTable.name] = p.name
                this[Tables.SwarmPresetTable.title] = p.title
                this[Tables.SwarmPresetTable.description] = p.description
                this[Tables.SwarmPresetTable.agentsJson] = mapper.writeValueAsString(p.agents)
                this[Tables.SwarmPresetTable.tasksJson] = mapper.writeValueAsString(p.tasks)
                this[Tables.SwarmPresetTable.variablesJson] = mapper.writeValueAsString(p.variables)
                this[Tables.SwarmPresetTable.language] = p.language
                this[Tables.SwarmPresetTable.userId] = userId
                this[Tables.SwarmPresetTable.enabled] = true
                this[Tables.SwarmPresetTable.createdAt] = now
                this[Tables.SwarmPresetTable.updatedAt] = now
            }
        }
        logger.debug("Saved swarm preset '{}' for user '{}'", preset.name, userId)
    }

    override suspend fun findById(id: String, userId: String): SwarmPreset? {
        return suspendTransaction(db) {
            val row = Tables.SwarmPresetTable.selectAll()
                .where {
                    (Tables.SwarmPresetTable.id eq id) and
                        UserScope.filter(Tables.SwarmPresetTable.userId, userId)
                }
                .toList()
                .firstOrNull() ?: return@suspendTransaction null

            rowToPreset(row)
        }
    }

    override suspend fun findByName(name: String, userId: String): SwarmPreset? {
        return suspendTransaction(db) {
            val row = Tables.SwarmPresetTable.selectAll()
                .where {
                    (Tables.SwarmPresetTable.name eq name) and
                        UserScope.filter(Tables.SwarmPresetTable.userId, userId)
                }
                .toList()
                .firstOrNull() ?: return@suspendTransaction null

            rowToPreset(row)
        }
    }

    override suspend fun findAll(userId: String): List<SwarmPreset> {
        return suspendTransaction(db) {
            Tables.SwarmPresetTable.selectAll()
                .where { UserScope.filter(Tables.SwarmPresetTable.userId, userId) }
                .orderBy(Tables.SwarmPresetTable.name to SortOrder.ASC)
                .toList()
                .map { rowToPreset(it) }
        }
    }

    override suspend fun update(preset: SwarmPreset, userId: String) {
        suspendTransaction(db) {
            Tables.SwarmPresetTable.update({
                (Tables.SwarmPresetTable.name eq preset.name) and
                    UserScope.filterStrict(Tables.SwarmPresetTable.userId, userId)
            }) {
                it[title] = preset.title
                it[description] = preset.description
                it[agentsJson] = mapper.writeValueAsString(preset.agents)
                it[tasksJson] = mapper.writeValueAsString(preset.tasks)
                it[variablesJson] = mapper.writeValueAsString(preset.variables)
                it[language] = preset.language
                it[updatedAt] = System.currentTimeMillis()
            }
        }
        logger.debug("Updated swarm preset '{}' for user '{}'", preset.name, userId)
    }

    override suspend fun delete(name: String, userId: String) {
        suspendTransaction(db) {
            Tables.SwarmPresetTable.deleteWhere {
                (Tables.SwarmPresetTable.name eq name) and
                    UserScope.filterStrict(Tables.SwarmPresetTable.userId, userId)
            }
        }
        logger.debug("Deleted swarm preset '{}' for user '{}'", name, userId)
    }

    private fun rowToPreset(row: org.jetbrains.exposed.v1.core.ResultRow): SwarmPreset {
        return SwarmPreset(
            name = row[Tables.SwarmPresetTable.name],
            title = row[Tables.SwarmPresetTable.title],
            description = row[Tables.SwarmPresetTable.description],
            agents = mapper.readValue(
                row[Tables.SwarmPresetTable.agentsJson],
                mapper.typeFactory.constructCollectionType(List::class.java, SwarmAgentSpec::class.java)
            ),
            tasks = mapper.readValue(
                row[Tables.SwarmPresetTable.tasksJson],
                mapper.typeFactory.constructCollectionType(List::class.java, SwarmTask::class.java)
            ),
            variables = mapper.readValue(
                row[Tables.SwarmPresetTable.variablesJson],
                mapper.typeFactory.constructCollectionType(List::class.java, SwarmVariable::class.java)
            ),
            language = row[Tables.SwarmPresetTable.language]
        )
    }
}
