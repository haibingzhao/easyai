package com.easy.easyai.web.controller

import com.easy.easyai.web.model.ForkBranchInfo
import com.easy.easyai.web.model.ForkSessionRequest
import com.easy.easyai.web.model.SessionDetail
import com.easy.easyai.web.model.SessionResponse
import com.easy.easyai.web.model.UpdateSessionTagsRequest
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.SessionService
import kotlinx.coroutines.reactor.mono
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/chat")
class SessionController(
    private val sessionService: SessionService
) {

    @GetMapping("/sessions")
    fun listSessions(
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) tags: List<String>?,
        /** Restrict to project-less sessions (temporary workspaces); the "No Project" mode. */
        @RequestParam(required = false, defaultValue = "false") tempWorkspace: Boolean
    ): Mono<SessionService.SessionListResponse> {
        return mono {
            val userId = getCurrentUserId()
            sessionService.listSessions(limit, offset, projectId, userId, tags, tempWorkspace)
        }
    }

    /** Distinct tags in use, for the History panel autocomplete + filter chip row. */
    @GetMapping("/sessions/tags")
    fun listSessionTags(
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false, defaultValue = "false") tempWorkspace: Boolean
    ): Mono<List<String>> {
        return mono {
            val userId = getCurrentUserId()
            sessionService.listSessionTags(projectId, userId, tempWorkspace)
        }
    }

    @GetMapping("/session/{id}")
    fun getSessionDetail(@PathVariable id: String): Mono<SessionDetail> {
        return mono {
            val userId = getCurrentUserId()
            sessionService.getSessionDetail(id, userId) ?: throw IllegalArgumentException("Session not found")
        }
    }

    /** Overwrite the tag set of a session. */
    @PutMapping("/session/{id}/tags")
    fun updateSessionTags(
        @PathVariable id: String,
        @RequestBody request: UpdateSessionTagsRequest
    ): Mono<Void> {
        return mono {
            val userId = getCurrentUserId()
            sessionService.updateSessionTags(id, request.tags, userId)
        }.then()
    }

    @PostMapping("/session")
    fun createSession(): Mono<SessionResponse> {
        return mono {
            val userId = getCurrentUserId()
            val sessionId = sessionService.createSession(userId)
            SessionResponse(sessionId = sessionId)
        }
    }

    @PostMapping("/session/{id}/fork")
    fun forkSession(@PathVariable id: String, @RequestBody request: ForkSessionRequest): Mono<SessionResponse> {
        return mono {
            val userId = getCurrentUserId()
            val sessionId = sessionService.forkSession(id, request.messageId, userId)
            SessionResponse(sessionId = sessionId)
        }
    }

    @GetMapping("/session/{id}/forks")
    fun listForks(@PathVariable id: String): Mono<List<ForkBranchInfo>> {
        return mono {
            val userId = getCurrentUserId()
            sessionService.listForks(id, userId)
        }
    }

    @DeleteMapping("/session/{id}")
    fun deleteSession(@PathVariable id: String): Mono<Void> {
        return mono {
            val userId = getCurrentUserId()
            sessionService.deleteSession(id, userId)
        }.then()
    }
}
