package com.easy.easyai.web.controller

import com.easy.easyai.web.model.SessionDetail
import com.easy.easyai.web.model.SessionResponse
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
        @RequestParam(required = false) projectId: String?
    ): Mono<SessionService.SessionListResponse> {
        return mono {
            val userId = getCurrentUserId()
            sessionService.listSessions(limit, offset, projectId, userId)
        }
    }

    @GetMapping("/session/{id}")
    fun getSessionDetail(@PathVariable id: String): Mono<SessionDetail> {
        return mono {
            val userId = getCurrentUserId()
            sessionService.getSessionDetail(id, userId) ?: throw IllegalArgumentException("Session not found")
        }
    }

    @PostMapping("/session")
    fun createSession(): Mono<SessionResponse> {
        return mono {
            val userId = getCurrentUserId()
            val sessionId = sessionService.createSession(userId)
            SessionResponse(sessionId = sessionId)
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
