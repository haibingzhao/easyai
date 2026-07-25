package com.easy.easyai.web.controller

import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.web.model.TeamMemberExecutionDto
import com.easy.easyai.web.model.TeamRoundRecordDto
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

/**
 * REST controller for Team Agent execution history (Team Member Panel).
 *
 * Endpoints:
 * - GET /api/team/sessions/{sessionId}/executions - Member execution records
 * - GET /api/team/sessions/{sessionId}/rounds     - Coordination round records
 */
@RestController
@RequestMapping("/api/team")
class TeamController(
    private val teamExecutionStore: TeamExecutionStore,
    private val agentStore: AsyncAgentStore,
) {

    @GetMapping("/sessions/{sessionId}/executions")
    fun getExecutions(@PathVariable sessionId: String): Mono<List<TeamMemberExecutionDto>> = mono {
        val userId = getCurrentUserId()
        val executions = teamExecutionStore.getExecutions(sessionId)
        // Resolve member display names in one batch query
        val memberIds = executions.map { it.memberId }.toSet()
        val agents = agentStore.findByIds(memberIds, userId)
        executions.map { exec ->
            TeamMemberExecutionDto(
                id = exec.id,
                memberId = exec.memberId,
                memberName = agents[exec.memberId]?.name ?: exec.memberId,
                round = exec.round,
                assignment = exec.assignment,
                status = exec.status.name,
                summary = exec.summary,
                blockedQuestion = exec.escalationReason,
                memberSessionId = exec.memberSessionId,
                toolCallId = exec.toolCallId,
                inputTokens = exec.inputTokens,
                outputTokens = exec.outputTokens,
                startedAt = exec.startedAt,
                completedAt = exec.completedAt,
            )
        }
    }

    @GetMapping("/sessions/{sessionId}/rounds")
    fun getRounds(@PathVariable sessionId: String): Mono<List<TeamRoundRecordDto>> = mono {
        getCurrentUserId()
        teamExecutionStore.getRounds(sessionId).map { record ->
            TeamRoundRecordDto(
                id = record.id,
                round = record.round,
                delegatedMembers = record.delegatedMembers,
                completedMembers = record.completedMembers,
                blockedMembers = record.blockedMembers,
                resumedMembers = record.resumedMembers,
                createdAt = record.createdAt,
            )
        }
    }
}
