package com.easy.easyai.repository.team

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.core.team.TeamMemberExecutionEntity
import com.easy.easyai.core.team.TeamMemberStatus
import com.easy.easyai.core.team.TeamRoundRecord
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.asyncTransaction
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory

/**
 * R2DBC-backed implementation of [TeamExecutionStore].
 * Persists Team Agent member executions and round records via Exposed.
 */
class R2dbcTeamExecutionStore(
    private val db: R2dbcDatabase
) : TeamExecutionStore {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = SharedObjectMapper.instance

    override suspend fun saveExecution(entity: TeamMemberExecutionEntity) {
        asyncTransaction(db) {
            Tables.TeamMemberExecutionTable.insert {
                it[id] = entity.id
                it[teamSessionId] = entity.teamSessionId
                it[memberId] = entity.memberId
                it[round] = entity.round
                it[assignment] = entity.assignment
                it[status] = entity.status.name
                it[summary] = entity.summary
                it[escalationReason] = entity.escalationReason
                it[memberSessionId] = entity.memberSessionId
                it[toolCallId] = entity.toolCallId
                it[inputTokens] = entity.inputTokens
                it[outputTokens] = entity.outputTokens
                it[startedAt] = entity.startedAt
                it[completedAt] = entity.completedAt
            }
        }
        logger.debug("Saved team execution {} for member '{}' in session {}",
            entity.id, entity.memberId, entity.teamSessionId)
    }

    override suspend fun updateExecution(
        id: String,
        status: TeamMemberStatus,
        summary: String?,
        escalationReason: String?,
        inputTokens: Long,
        outputTokens: Long,
    ) {
        asyncTransaction(db) {
            Tables.TeamMemberExecutionTable.update({
                Tables.TeamMemberExecutionTable.id eq id
            }) {
                it[this.status] = status.name
                it[this.summary] = summary
                it[this.escalationReason] = escalationReason
                it[this.inputTokens] = inputTokens
                it[this.outputTokens] = outputTokens
                it[completedAt] = System.currentTimeMillis()
            }
        }
        logger.debug("Updated team execution {} → {}", id, status)
    }

    override suspend fun updateStatus(id: String, status: TeamMemberStatus) {
        asyncTransaction(db) {
            Tables.TeamMemberExecutionTable.update({
                Tables.TeamMemberExecutionTable.id eq id
            }) {
                it[this.status] = status.name
                it[completedAt] = System.currentTimeMillis()
            }
        }
        logger.debug("Updated team execution status {} → {}", id, status)
    }

    override suspend fun getExecutions(teamSessionId: String): List<TeamMemberExecutionEntity> {
        return asyncTransaction(db) {
            Tables.TeamMemberExecutionTable
                .selectAll()
                .where { Tables.TeamMemberExecutionTable.teamSessionId eq teamSessionId }
                .orderBy(Tables.TeamMemberExecutionTable.startedAt to SortOrder.ASC)
                .toList()
                .map { row -> row.toEntity() }
        }
    }

    override suspend fun getIncompleteExecutions(teamSessionId: String): List<TeamMemberExecutionEntity> {
        return asyncTransaction(db) {
            Tables.TeamMemberExecutionTable
                .selectAll()
                .where {
                    (Tables.TeamMemberExecutionTable.teamSessionId eq teamSessionId) and
                        (Tables.TeamMemberExecutionTable.status eq TeamMemberStatus.RUNNING.name)
                }
                .orderBy(Tables.TeamMemberExecutionTable.startedAt to SortOrder.ASC)
                .toList()
                .map { row -> row.toEntity() }
        }
    }

    override suspend fun saveRound(record: TeamRoundRecord) {
        asyncTransaction(db) {
            Tables.TeamRoundRecordTable.insert {
                it[id] = record.id
                it[teamSessionId] = record.teamSessionId
                it[round] = record.round
                it[delegatedMembers] = toJson(record.delegatedMembers)
                it[completedMembers] = toJson(record.completedMembers)
                it[blockedMembers] = toJson(record.blockedMembers)
                it[resumedMembers] = toJson(record.resumedMembers)
                it[createdAt] = record.createdAt
            }
        }
        logger.debug("Saved team round {} for session {}", record.round, record.teamSessionId)
    }

    override suspend fun getRounds(teamSessionId: String): List<TeamRoundRecord> {
        return asyncTransaction(db) {
            Tables.TeamRoundRecordTable
                .selectAll()
                .where { Tables.TeamRoundRecordTable.teamSessionId eq teamSessionId }
                .orderBy(Tables.TeamRoundRecordTable.round to SortOrder.ASC)
                .toList()
                .map { row ->
                    TeamRoundRecord(
                        id = row[Tables.TeamRoundRecordTable.id],
                        teamSessionId = row[Tables.TeamRoundRecordTable.teamSessionId],
                        round = row[Tables.TeamRoundRecordTable.round],
                        delegatedMembers = fromJson(row[Tables.TeamRoundRecordTable.delegatedMembers]),
                        completedMembers = fromJson(row[Tables.TeamRoundRecordTable.completedMembers]),
                        blockedMembers = fromJson(row[Tables.TeamRoundRecordTable.blockedMembers]),
                        resumedMembers = fromJson(row[Tables.TeamRoundRecordTable.resumedMembers]),
                        createdAt = row[Tables.TeamRoundRecordTable.createdAt],
                    )
                }
        }
    }

    override suspend fun deleteByTeamSession(teamSessionId: String) {
        asyncTransaction(db) {
            Tables.TeamMemberExecutionTable.deleteWhere {
                Tables.TeamMemberExecutionTable.teamSessionId eq teamSessionId
            }
            Tables.TeamRoundRecordTable.deleteWhere {
                Tables.TeamRoundRecordTable.teamSessionId eq teamSessionId
            }
        }
        logger.debug("Deleted team execution + round records for session {}", teamSessionId)
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toEntity(): TeamMemberExecutionEntity {
        val t = Tables.TeamMemberExecutionTable
        return TeamMemberExecutionEntity(
            id = this[t.id],
            teamSessionId = this[t.teamSessionId],
            memberId = this[t.memberId],
            round = this[t.round],
            assignment = this[t.assignment],
            status = try {
                TeamMemberStatus.valueOf(this[t.status])
            } catch (_: Exception) {
                TeamMemberStatus.ERROR
            },
            summary = this[t.summary],
            escalationReason = this[t.escalationReason],
            memberSessionId = this[t.memberSessionId],
            toolCallId = this[t.toolCallId],
            inputTokens = this[t.inputTokens],
            outputTokens = this[t.outputTokens],
            startedAt = this[t.startedAt],
            completedAt = this[t.completedAt],
        )
    }

    private fun toJson(list: List<String>): String? =
        if (list.isEmpty()) null else mapper.writeValueAsString(list)

    private fun fromJson(json: String?): List<String> =
        if (json.isNullOrBlank()) emptyList()
        else try {
            mapper.readValue(json, mapper.typeFactory.constructCollectionType(List::class.java, String::class.java))
        } catch (_: Exception) {
            emptyList()
        }
}
