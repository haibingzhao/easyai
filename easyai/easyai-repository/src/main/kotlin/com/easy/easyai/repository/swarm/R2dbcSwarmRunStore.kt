package com.easy.easyai.repository.swarm

import com.easy.easyai.core.team.TeamMemberExecution
import com.easy.easyai.core.team.TeamMemberStatus
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import com.easy.easyai.swarm.model.*
import com.easy.easyai.swarm.store.SwarmRunStore
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import com.easy.easyai.common.util.SharedObjectMapper
import java.util.UUID

/**
 * R2DBC-backed implementation of [SwarmRunStore].
 * Stores swarm runs, tasks, and deliberation history in H2/PostgreSQL via Exposed.
 */
class R2dbcSwarmRunStore(
    private val db: R2dbcDatabase
) : SwarmRunStore {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val mapper = SharedObjectMapper.instance

    override suspend fun saveRun(run: SwarmRun, userId: String) {
        suspendTransaction(db) {
            Tables.SwarmRunTable.batchInsert(listOf(run)) { r ->
                this[Tables.SwarmRunTable.id] = r.id
                this[Tables.SwarmRunTable.presetName] = r.presetName
                this[Tables.SwarmRunTable.title] = r.title
                this[Tables.SwarmRunTable.status] = r.status.name
                this[Tables.SwarmRunTable.agents] = mapper.writeValueAsString(r.agents)
                this[Tables.SwarmRunTable.userVars] = mapper.writeValueAsString(r.userVars)
                this[Tables.SwarmRunTable.totalInputTokens] = r.totalInputTokens
                this[Tables.SwarmRunTable.totalOutputTokens] = r.totalOutputTokens
                this[Tables.SwarmRunTable.totalCacheReadTokens] = r.totalCacheReadTokens
                this[Tables.SwarmRunTable.totalCacheWriteTokens] = r.totalCacheWriteTokens
                this[Tables.SwarmRunTable.totalDurationMs] = r.totalDurationMs
                this[Tables.SwarmRunTable.error] = r.error
                this[Tables.SwarmRunTable.userId] = userId
                this[Tables.SwarmRunTable.createdAt] = r.createdAt.toEpochMilli()
                this[Tables.SwarmRunTable.startedAt] = r.startedAt?.toEpochMilli()
                this[Tables.SwarmRunTable.completedAt] = r.completedAt?.toEpochMilli()
            }
        }
        logger.debug("Saved swarm run '{}' for user '{}'", run.id, userId)
    }

    override suspend fun updateRun(run: SwarmRun, userId: String) {
        suspendTransaction(db) {
            // userId intentionally not updated — ownership is immutable after creation
            Tables.SwarmRunTable.update({
                (Tables.SwarmRunTable.id eq run.id) and
                    UserScope.filterStrict(Tables.SwarmRunTable.userId, userId)
            }) {
                it[status] = run.status.name
                it[userVars] = mapper.writeValueAsString(run.userVars)
                it[totalInputTokens] = run.totalInputTokens
                it[totalOutputTokens] = run.totalOutputTokens
                it[Tables.SwarmRunTable.totalCacheReadTokens] = run.totalCacheReadTokens
                it[Tables.SwarmRunTable.totalCacheWriteTokens] = run.totalCacheWriteTokens
                it[Tables.SwarmRunTable.totalDurationMs] = run.totalDurationMs
                it[error] = run.error
                it[startedAt] = run.startedAt?.toEpochMilli()
                it[completedAt] = run.completedAt?.toEpochMilli()
            }
        }
    }

    override suspend fun getRun(runId: String, userId: String): SwarmRun? {
        return suspendTransaction(db) {
            val row = Tables.SwarmRunTable.selectAll()
                .where {
                    (Tables.SwarmRunTable.id eq runId) and
                        UserScope.filterStrict(Tables.SwarmRunTable.userId, userId)
                }
                .toList()
                .firstOrNull() ?: return@suspendTransaction null

            SwarmRun(
                id = row[Tables.SwarmRunTable.id],
                presetName = row[Tables.SwarmRunTable.presetName],
                title = row[Tables.SwarmRunTable.title],
                status = SwarmRunStatus.valueOf(row[Tables.SwarmRunTable.status]),
                agents = mapper.readValue(row[Tables.SwarmRunTable.agents],
                    mapper.typeFactory.constructCollectionType(List::class.java, SwarmAgentSpec::class.java)),
                userVars = mapper.readValue<MutableMap<String, String>>(row[Tables.SwarmRunTable.userVars],
                    mapper.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, String::class.java)),
                totalInputTokens = row[Tables.SwarmRunTable.totalInputTokens],
                totalOutputTokens = row[Tables.SwarmRunTable.totalOutputTokens],
                totalCacheReadTokens = row[Tables.SwarmRunTable.totalCacheReadTokens],
                totalCacheWriteTokens = row[Tables.SwarmRunTable.totalCacheWriteTokens],
                totalDurationMs = row[Tables.SwarmRunTable.totalDurationMs],
                error = row[Tables.SwarmRunTable.error],
                userId = row[Tables.SwarmRunTable.userId],
                createdAt = java.time.Instant.ofEpochMilli(row[Tables.SwarmRunTable.createdAt]),
                startedAt = row[Tables.SwarmRunTable.startedAt]?.let { java.time.Instant.ofEpochMilli(it) },
                completedAt = row[Tables.SwarmRunTable.completedAt]?.let { java.time.Instant.ofEpochMilli(it) },
                tasks = emptyList()
            )
        }
    }

    override suspend fun listRuns(limit: Int, offset: Int, userId: String): List<SwarmRun> {
        return suspendTransaction(db) {
            Tables.SwarmRunTable.selectAll()
                .where { UserScope.filterStrict(Tables.SwarmRunTable.userId, userId) }
                .orderBy(Tables.SwarmRunTable.createdAt to SortOrder.DESC)
                .limit(limit).offset(offset.toLong())
                .toList()
                .map { row ->
                    SwarmRun(
                        id = row[Tables.SwarmRunTable.id],
                        presetName = row[Tables.SwarmRunTable.presetName],
                        title = row[Tables.SwarmRunTable.title],
                        status = SwarmRunStatus.valueOf(row[Tables.SwarmRunTable.status]),
                        agents = mapper.readValue(row[Tables.SwarmRunTable.agents],
                            mapper.typeFactory.constructCollectionType(List::class.java, SwarmAgentSpec::class.java)),
                        userVars = mapper.readValue<MutableMap<String, String>>(row[Tables.SwarmRunTable.userVars],
                            mapper.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, String::class.java)),
                        totalInputTokens = row[Tables.SwarmRunTable.totalInputTokens],
                        totalOutputTokens = row[Tables.SwarmRunTable.totalOutputTokens],
                        totalCacheReadTokens = row[Tables.SwarmRunTable.totalCacheReadTokens],
                        totalCacheWriteTokens = row[Tables.SwarmRunTable.totalCacheWriteTokens],
                        totalDurationMs = row[Tables.SwarmRunTable.totalDurationMs],
                        error = row[Tables.SwarmRunTable.error],
                        userId = row[Tables.SwarmRunTable.userId],
                        createdAt = java.time.Instant.ofEpochMilli(row[Tables.SwarmRunTable.createdAt]),
                        startedAt = row[Tables.SwarmRunTable.startedAt]?.let { java.time.Instant.ofEpochMilli(it) },
                        completedAt = row[Tables.SwarmRunTable.completedAt]?.let { java.time.Instant.ofEpochMilli(it) },
                        tasks = emptyList()
                    )
                }
        }
    }

    override suspend fun saveTasks(runId: String, tasks: List<SwarmTask>) {
        suspendTransaction(db) {
            // Delete existing tasks for this run
            Tables.SwarmTaskTable.deleteWhere { Tables.SwarmTaskTable.runId eq runId }

            if (tasks.isNotEmpty()) {
                Tables.SwarmTaskTable.batchInsert(tasks) { task ->
                    this[Tables.SwarmTaskTable.id] = UUID.randomUUID().toString()
                    this[Tables.SwarmTaskTable.runId] = runId
                    this[Tables.SwarmTaskTable.taskId] = task.id
                    this[Tables.SwarmTaskTable.agentId] = task.agentId
                    this[Tables.SwarmTaskTable.taskType] = task.type.name
                    this[Tables.SwarmTaskTable.status] = task.status.name
                    this[Tables.SwarmTaskTable.summary] = task.summary
                    this[Tables.SwarmTaskTable.error] = task.error
                    this[Tables.SwarmTaskTable.workerIterations] = task.workerIterations
                    this[Tables.SwarmTaskTable.inputTokens] = task.inputTokens
                    this[Tables.SwarmTaskTable.outputTokens] = task.outputTokens
                    this[Tables.SwarmTaskTable.cacheReadTokens] = task.cacheReadTokens
                    this[Tables.SwarmTaskTable.cacheWriteTokens] = task.cacheWriteTokens
                    this[Tables.SwarmTaskTable.durationMs] = task.durationMs
                    this[Tables.SwarmTaskTable.startedAt] = task.startedAt?.toEpochMilli()
                    this[Tables.SwarmTaskTable.completedAt] = task.completedAt?.toEpochMilli()
                }
            }
        }
    }

    override suspend fun getTasks(runId: String): List<SwarmTask> {
        return suspendTransaction(db) {
            Tables.SwarmTaskTable.selectAll()
                .where { Tables.SwarmTaskTable.runId eq runId }
                .toList()
                .map { row ->
                    SwarmTask(
                        id = row[Tables.SwarmTaskTable.taskId],
                        agentId = row[Tables.SwarmTaskTable.agentId],
                        promptTemplate = "",  // Not persisted (template, not result)
                        type = TaskType.valueOf(row[Tables.SwarmTaskTable.taskType]),
                        status = SwarmTaskStatus.valueOf(row[Tables.SwarmTaskTable.status]),
                        summary = row[Tables.SwarmTaskTable.summary],
                        error = row[Tables.SwarmTaskTable.error],
                        workerIterations = row[Tables.SwarmTaskTable.workerIterations],
                        inputTokens = row[Tables.SwarmTaskTable.inputTokens],
                        outputTokens = row[Tables.SwarmTaskTable.outputTokens],
                        cacheReadTokens = row[Tables.SwarmTaskTable.cacheReadTokens],
                        cacheWriteTokens = row[Tables.SwarmTaskTable.cacheWriteTokens],
                        durationMs = row[Tables.SwarmTaskTable.durationMs],
                        startedAt = row[Tables.SwarmTaskTable.startedAt]?.let { java.time.Instant.ofEpochMilli(it) },
                        completedAt = row[Tables.SwarmTaskTable.completedAt]?.let { java.time.Instant.ofEpochMilli(it) }
                    )
                }
        }
    }

    override suspend fun saveDeliberationHistory(
        runId: String,
        taskId: String,
        entries: List<DeliberationEntry>
    ) {
        suspendTransaction(db) {
            // Delete existing history for this task
            Tables.SwarmDeliberationHistoryTable.deleteWhere {
                (Tables.SwarmDeliberationHistoryTable.runId eq runId) and
                    (Tables.SwarmDeliberationHistoryTable.taskId eq taskId)
            }

            if (entries.isNotEmpty()) {
                val objectMapper = SharedObjectMapper.instance
                Tables.SwarmDeliberationHistoryTable.batchInsert(entries) { entry ->
                    this[Tables.SwarmDeliberationHistoryTable.id] = UUID.randomUUID().toString()
                    this[Tables.SwarmDeliberationHistoryTable.runId] = runId
                    this[Tables.SwarmDeliberationHistoryTable.taskId] = taskId
                    this[Tables.SwarmDeliberationHistoryTable.agentId] = entry.agentId
                    this[Tables.SwarmDeliberationHistoryTable.round] = entry.round
                    this[Tables.SwarmDeliberationHistoryTable.response] = entry.response
                    this[Tables.SwarmDeliberationHistoryTable.inputTokens] = entry.inputTokens
                    this[Tables.SwarmDeliberationHistoryTable.outputTokens] = entry.outputTokens
                    this[Tables.SwarmDeliberationHistoryTable.cacheReadTokens] = entry.cacheReadTokens
                    this[Tables.SwarmDeliberationHistoryTable.cacheWriteTokens] = entry.cacheWriteTokens
                    this[Tables.SwarmDeliberationHistoryTable.durationMs] = entry.durationMs
                    this[Tables.SwarmDeliberationHistoryTable.openingPrompt] = entry.openingPrompt
                    this[Tables.SwarmDeliberationHistoryTable.roundPrompts] =
                        entry.roundPrompts?.let { objectMapper.writeValueAsString(it) }
                    this[Tables.SwarmDeliberationHistoryTable.createdAt] = System.currentTimeMillis()
                }
            }
        }
    }

    override suspend fun getDeliberationHistory(
        runId: String,
        taskId: String
    ): List<DeliberationEntry> {
        return suspendTransaction(db) {
            val objectMapper = SharedObjectMapper.instance
            Tables.SwarmDeliberationHistoryTable.selectAll()
                .where {
                    (Tables.SwarmDeliberationHistoryTable.runId eq runId) and
                        (Tables.SwarmDeliberationHistoryTable.taskId eq taskId)
                }
                .orderBy(Tables.SwarmDeliberationHistoryTable.round to SortOrder.ASC,
                    Tables.SwarmDeliberationHistoryTable.createdAt to SortOrder.ASC)
                .toList()
                .map { row ->
                    val roundPromptsJson = row[Tables.SwarmDeliberationHistoryTable.roundPrompts]
                    val roundPrompts: Map<String, String>? = roundPromptsJson?.let {
                        @Suppress("UNCHECKED_CAST")
                        objectMapper.readValue(it, Map::class.java) as? Map<String, String>
                    }
                    DeliberationEntry(
                        agentId = row[Tables.SwarmDeliberationHistoryTable.agentId],
                        round = row[Tables.SwarmDeliberationHistoryTable.round],
                        response = row[Tables.SwarmDeliberationHistoryTable.response],
                        inputTokens = row[Tables.SwarmDeliberationHistoryTable.inputTokens],
                        outputTokens = row[Tables.SwarmDeliberationHistoryTable.outputTokens],
                        cacheReadTokens = row[Tables.SwarmDeliberationHistoryTable.cacheReadTokens],
                        cacheWriteTokens = row[Tables.SwarmDeliberationHistoryTable.cacheWriteTokens],
                        durationMs = row[Tables.SwarmDeliberationHistoryTable.durationMs],
                        openingPrompt = row[Tables.SwarmDeliberationHistoryTable.openingPrompt],
                        roundPrompts = roundPrompts,
                    )
                }
        }
    }

    override suspend fun saveDeliberationVerdict(
        runId: String,
        taskId: String,
        verdictPrompt: String,
        verdictResponse: String
    ) {
        suspendTransaction(db) {
            Tables.SwarmDeliberationVerdictTable.deleteWhere {
                (Tables.SwarmDeliberationVerdictTable.runId eq runId) and
                    (Tables.SwarmDeliberationVerdictTable.taskId eq taskId)
            }
            Tables.SwarmDeliberationVerdictTable.insert { stmt ->
                stmt[Tables.SwarmDeliberationVerdictTable.id] = UUID.randomUUID().toString()
                stmt[Tables.SwarmDeliberationVerdictTable.runId] = runId
                stmt[Tables.SwarmDeliberationVerdictTable.taskId] = taskId
                stmt[Tables.SwarmDeliberationVerdictTable.verdictPrompt] = verdictPrompt
                stmt[Tables.SwarmDeliberationVerdictTable.verdictResponse] = verdictResponse
                stmt[Tables.SwarmDeliberationVerdictTable.createdAt] = System.currentTimeMillis()
            }
        }
    }

    override suspend fun getDeliberationVerdict(
        runId: String,
        taskId: String
    ): Pair<String, String>? {
        return suspendTransaction(db) {
            Tables.SwarmDeliberationVerdictTable.selectAll()
                .where {
                    (Tables.SwarmDeliberationVerdictTable.runId eq runId) and
                        (Tables.SwarmDeliberationVerdictTable.taskId eq taskId)
                }
                .toList()
                .firstOrNull()
                ?.let { row ->
                    Pair(
                        row[Tables.SwarmDeliberationVerdictTable.verdictPrompt],
                        row[Tables.SwarmDeliberationVerdictTable.verdictResponse]
                    )
                }
        }
    }

    override suspend fun saveEscalationHistory(
        runId: String,
        taskId: String,
        entries: List<EscalationEntry>
    ) {
        suspendTransaction(db) {
            Tables.SwarmEscalationHistoryTable.deleteWhere {
                (Tables.SwarmEscalationHistoryTable.runId eq runId) and
                    (Tables.SwarmEscalationHistoryTable.taskId eq taskId)
            }

            if (entries.isNotEmpty()) {
                Tables.SwarmEscalationHistoryTable.batchInsert(entries) { entry ->
                    this[Tables.SwarmEscalationHistoryTable.id] = UUID.randomUUID().toString()
                    this[Tables.SwarmEscalationHistoryTable.runId] = runId
                    this[Tables.SwarmEscalationHistoryTable.taskId] = taskId
                    this[Tables.SwarmEscalationHistoryTable.memberId] = entry.memberId
                    this[Tables.SwarmEscalationHistoryTable.round] = entry.round
                    this[Tables.SwarmEscalationHistoryTable.reason] = entry.reason
                    this[Tables.SwarmEscalationHistoryTable.resolution] = entry.resolution
                    this[Tables.SwarmEscalationHistoryTable.reassignedTo] = entry.reassignedTo
                    this[Tables.SwarmEscalationHistoryTable.createdAt] = System.currentTimeMillis()
                }
            }
        }
    }

    override suspend fun getEscalationHistory(
        runId: String,
        taskId: String
    ): List<EscalationEntry> {
        return suspendTransaction(db) {
            Tables.SwarmEscalationHistoryTable.selectAll()
                .where {
                    (Tables.SwarmEscalationHistoryTable.runId eq runId) and
                        (Tables.SwarmEscalationHistoryTable.taskId eq taskId)
                }
                .orderBy(Tables.SwarmEscalationHistoryTable.round to SortOrder.ASC,
                    Tables.SwarmEscalationHistoryTable.createdAt to SortOrder.ASC)
                .toList()
                .map { row ->
                    EscalationEntry(
                        memberId = row[Tables.SwarmEscalationHistoryTable.memberId],
                        round = row[Tables.SwarmEscalationHistoryTable.round],
                        reason = row[Tables.SwarmEscalationHistoryTable.reason],
                        resolution = row[Tables.SwarmEscalationHistoryTable.resolution],
                        reassignedTo = row[Tables.SwarmEscalationHistoryTable.reassignedTo]
                    )
                }
        }
    }

    override suspend fun saveTeamHistory(
        runId: String,
        taskId: String,
        executions: List<TeamMemberExecution>,
        rounds: List<TeamRoundRecord>
    ) {
        suspendTransaction(db) {
            // Delete existing records for this task
            Tables.SwarmTeamMemberExecutionTable.deleteWhere {
                (Tables.SwarmTeamMemberExecutionTable.runId eq runId) and
                    (Tables.SwarmTeamMemberExecutionTable.taskId eq taskId)
            }
            Tables.SwarmTeamRoundRecordTable.deleteWhere {
                (Tables.SwarmTeamRoundRecordTable.runId eq runId) and
                    (Tables.SwarmTeamRoundRecordTable.taskId eq taskId)
            }

            if (executions.isNotEmpty()) {
                Tables.SwarmTeamMemberExecutionTable.batchInsert(executions) { exec ->
                    this[Tables.SwarmTeamMemberExecutionTable.id] = UUID.randomUUID().toString()
                    this[Tables.SwarmTeamMemberExecutionTable.runId] = runId
                    this[Tables.SwarmTeamMemberExecutionTable.taskId] = taskId
                    this[Tables.SwarmTeamMemberExecutionTable.memberId] = exec.memberId
                    this[Tables.SwarmTeamMemberExecutionTable.round] = exec.round
                    this[Tables.SwarmTeamMemberExecutionTable.assignment] = exec.assignment
                    this[Tables.SwarmTeamMemberExecutionTable.status] = exec.status.name
                    this[Tables.SwarmTeamMemberExecutionTable.summary] = exec.summary
                    this[Tables.SwarmTeamMemberExecutionTable.escalationReason] = exec.escalationReason
                    this[Tables.SwarmTeamMemberExecutionTable.inputTokens] = exec.inputTokens
                    this[Tables.SwarmTeamMemberExecutionTable.outputTokens] = exec.outputTokens
                    this[Tables.SwarmTeamMemberExecutionTable.memberSessionId] = exec.memberSessionId
                    this[Tables.SwarmTeamMemberExecutionTable.createdAt] = System.currentTimeMillis()
                }
            }

            if (rounds.isNotEmpty()) {
                Tables.SwarmTeamRoundRecordTable.batchInsert(rounds) { record ->
                    this[Tables.SwarmTeamRoundRecordTable.id] = UUID.randomUUID().toString()
                    this[Tables.SwarmTeamRoundRecordTable.runId] = runId
                    this[Tables.SwarmTeamRoundRecordTable.taskId] = taskId
                    this[Tables.SwarmTeamRoundRecordTable.round] = record.round
                    this[Tables.SwarmTeamRoundRecordTable.leaderAnalysis] = record.leaderAnalysis
                    this[Tables.SwarmTeamRoundRecordTable.delegatedMembers] = mapper.writeValueAsString(record.delegatedMembers)
                    this[Tables.SwarmTeamRoundRecordTable.completedMembers] = mapper.writeValueAsString(record.completedMembers)
                    this[Tables.SwarmTeamRoundRecordTable.escalations] = mapper.writeValueAsString(record.escalations)
                    this[Tables.SwarmTeamRoundRecordTable.leaderPrompt] = record.leaderPrompt
                    this[Tables.SwarmTeamRoundRecordTable.createdAt] = System.currentTimeMillis()
                }
            }
        }
    }

    override suspend fun getTeamHistory(
        runId: String,
        taskId: String
    ): Pair<List<TeamMemberExecution>, List<TeamRoundRecord>> {
        return suspendTransaction(db) {
            val executions = Tables.SwarmTeamMemberExecutionTable.selectAll()
                .where {
                    (Tables.SwarmTeamMemberExecutionTable.runId eq runId) and
                        (Tables.SwarmTeamMemberExecutionTable.taskId eq taskId)
                }
                .orderBy(Tables.SwarmTeamMemberExecutionTable.round to SortOrder.ASC,
                    Tables.SwarmTeamMemberExecutionTable.createdAt to SortOrder.ASC)
                .toList()
                .map { row ->
                    TeamMemberExecution(
                        memberId = row[Tables.SwarmTeamMemberExecutionTable.memberId],
                        round = row[Tables.SwarmTeamMemberExecutionTable.round],
                        assignment = row[Tables.SwarmTeamMemberExecutionTable.assignment],
                        status = try {
                            TeamMemberStatus.valueOf(row[Tables.SwarmTeamMemberExecutionTable.status])
                        } catch (e: IllegalArgumentException) {
                            logger.warn("Unknown TeamMemberStatus '{}' in DB, defaulting to COMPLETED",
                                row[Tables.SwarmTeamMemberExecutionTable.status])
                            TeamMemberStatus.COMPLETED
                        },
                        summary = row[Tables.SwarmTeamMemberExecutionTable.summary],
                        escalationReason = row[Tables.SwarmTeamMemberExecutionTable.escalationReason],
                        inputTokens = row[Tables.SwarmTeamMemberExecutionTable.inputTokens],
                        outputTokens = row[Tables.SwarmTeamMemberExecutionTable.outputTokens],
                        memberSessionId = row[Tables.SwarmTeamMemberExecutionTable.memberSessionId]
                    )
                }

            val rounds = Tables.SwarmTeamRoundRecordTable.selectAll()
                .where {
                    (Tables.SwarmTeamRoundRecordTable.runId eq runId) and
                        (Tables.SwarmTeamRoundRecordTable.taskId eq taskId)
                }
                .orderBy(Tables.SwarmTeamRoundRecordTable.round to SortOrder.ASC,
                    Tables.SwarmTeamRoundRecordTable.createdAt to SortOrder.ASC)
                .toList()
                .map { row ->
                    TeamRoundRecord(
                        round = row[Tables.SwarmTeamRoundRecordTable.round],
                        leaderAnalysis = row[Tables.SwarmTeamRoundRecordTable.leaderAnalysis],
                        delegatedMembers = mapper.readValue(
                            row[Tables.SwarmTeamRoundRecordTable.delegatedMembers],
                            mapper.typeFactory.constructCollectionType(List::class.java, String::class.java)
                        ),
                        completedMembers = mapper.readValue(
                            row[Tables.SwarmTeamRoundRecordTable.completedMembers],
                            mapper.typeFactory.constructCollectionType(List::class.java, String::class.java)
                        ),
                        escalations = mapper.readValue(
                            row[Tables.SwarmTeamRoundRecordTable.escalations],
                            mapper.typeFactory.constructCollectionType(List::class.java, String::class.java)
                        ),
                        leaderPrompt = row[Tables.SwarmTeamRoundRecordTable.leaderPrompt]
                    )
                }

            executions to rounds
        }
    }

    override suspend fun deleteRun(runId: String, userId: String) {
        suspendTransaction(db) {
            // Verify ownership before deleting anything.
            // Use filterStrict to ensure only the actual owner can delete (not system data).
            val ownedRun = Tables.SwarmRunTable.selectAll()
                .where {
                    (Tables.SwarmRunTable.id eq runId) and
                        UserScope.filterStrict(Tables.SwarmRunTable.userId, userId)
                }
                .toList()
                .firstOrNull()

            if (ownedRun == null) {
                logger.debug("Cannot delete swarm run '{}': not owned by user '{}'", runId, userId)
                return@suspendTransaction
            }

            // Ownership confirmed — safe to delete child records and the run itself

            // 1. Cascade-delete linked sessions and their messages
            val sessionIds = Tables.Session.selectAll()
                .where { Tables.Session.swarmRunId eq runId }
                .toList()
                .map { it[Tables.Session.id] }

            if (sessionIds.isNotEmpty()) {
                Tables.Message.deleteWhere {
                    Tables.Message.sessionId inList sessionIds
                }
                Tables.Session.deleteWhere {
                    Tables.Session.swarmRunId eq runId
                }
                logger.debug("Deleted {} session(s) and their messages for swarm run '{}'",
                    sessionIds.size, runId)
            }

            // 2. Delete swarm child tables
            Tables.SwarmDeliberationHistoryTable.deleteWhere {
                Tables.SwarmDeliberationHistoryTable.runId eq runId
            }
            Tables.SwarmEscalationHistoryTable.deleteWhere {
                Tables.SwarmEscalationHistoryTable.runId eq runId
            }
            Tables.SwarmTeamMemberExecutionTable.deleteWhere {
                Tables.SwarmTeamMemberExecutionTable.runId eq runId
            }
            Tables.SwarmTeamRoundRecordTable.deleteWhere {
                Tables.SwarmTeamRoundRecordTable.runId eq runId
            }
            Tables.SwarmTaskTable.deleteWhere { Tables.SwarmTaskTable.runId eq runId }
            Tables.SwarmRunTable.deleteWhere {
                (Tables.SwarmRunTable.id eq runId) and
                    UserScope.filterStrict(Tables.SwarmRunTable.userId, userId)
            }
        }
        logger.debug("Deleted swarm run '{}' and all associated data", runId)
    }

    override suspend fun saveTask(runId: String, task: SwarmTask) {
        suspendTransaction(db) {
            // Upsert: delete existing row for this task in this run, then insert
            Tables.SwarmTaskTable.deleteWhere {
                (Tables.SwarmTaskTable.runId eq runId) and
                    (Tables.SwarmTaskTable.taskId eq task.id)
            }
            Tables.SwarmTaskTable.batchInsert(listOf(task)) { t ->
                this[Tables.SwarmTaskTable.id] = UUID.randomUUID().toString()
                this[Tables.SwarmTaskTable.runId] = runId
                this[Tables.SwarmTaskTable.taskId] = t.id
                this[Tables.SwarmTaskTable.agentId] = t.agentId
                this[Tables.SwarmTaskTable.taskType] = t.type.name
                this[Tables.SwarmTaskTable.status] = t.status.name
                this[Tables.SwarmTaskTable.summary] = t.summary
                this[Tables.SwarmTaskTable.error] = t.error
                this[Tables.SwarmTaskTable.workerIterations] = t.workerIterations
                this[Tables.SwarmTaskTable.inputTokens] = t.inputTokens
                this[Tables.SwarmTaskTable.outputTokens] = t.outputTokens
                this[Tables.SwarmTaskTable.cacheReadTokens] = t.cacheReadTokens
                this[Tables.SwarmTaskTable.cacheWriteTokens] = t.cacheWriteTokens
                this[Tables.SwarmTaskTable.durationMs] = t.durationMs
                this[Tables.SwarmTaskTable.startedAt] = t.startedAt?.toEpochMilli()
                this[Tables.SwarmTaskTable.completedAt] = t.completedAt?.toEpochMilli()
            }
        }
    }

    override suspend fun listRunsByStatus(status: SwarmRunStatus): List<SwarmRun> {
        return suspendTransaction(db) {
            Tables.SwarmRunTable.selectAll()
                .where { Tables.SwarmRunTable.status eq status.name }
                .toList()
                .map { row ->
                    SwarmRun(
                        id = row[Tables.SwarmRunTable.id],
                        presetName = row[Tables.SwarmRunTable.presetName],
                        title = row[Tables.SwarmRunTable.title],
                        status = SwarmRunStatus.valueOf(row[Tables.SwarmRunTable.status]),
                        agents = mapper.readValue(row[Tables.SwarmRunTable.agents],
                            mapper.typeFactory.constructCollectionType(List::class.java, SwarmAgentSpec::class.java)),
                        userVars = mapper.readValue<MutableMap<String, String>>(row[Tables.SwarmRunTable.userVars],
                            mapper.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, String::class.java)),
                        totalInputTokens = row[Tables.SwarmRunTable.totalInputTokens],
                        totalOutputTokens = row[Tables.SwarmRunTable.totalOutputTokens],
                        totalCacheReadTokens = row[Tables.SwarmRunTable.totalCacheReadTokens],
                        totalCacheWriteTokens = row[Tables.SwarmRunTable.totalCacheWriteTokens],
                        totalDurationMs = row[Tables.SwarmRunTable.totalDurationMs],
                        error = row[Tables.SwarmRunTable.error],
                        userId = row[Tables.SwarmRunTable.userId],
                        createdAt = java.time.Instant.ofEpochMilli(row[Tables.SwarmRunTable.createdAt]),
                        startedAt = row[Tables.SwarmRunTable.startedAt]?.let { java.time.Instant.ofEpochMilli(it) },
                        completedAt = row[Tables.SwarmRunTable.completedAt]?.let { java.time.Instant.ofEpochMilli(it) },
                        tasks = emptyList()
                    )
                }
        }
    }
}
