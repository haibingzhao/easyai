package com.easy.easyai.web.controller

import com.easy.easyai.auth.jwt.JwtTokenProvider
import com.easy.easyai.web.model.BatchLlmRequest
import com.easy.easyai.web.model.BatchLlmResponse
import com.easy.easyai.web.model.InternalLlmRequest
import com.easy.easyai.web.model.InternalLlmResponse
import com.easy.easyai.web.security.AuthProperties
import com.easy.easyai.web.service.InternalLlmService
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Internal LLM processing endpoint for script callbacks.
 * Authenticates via Script Token (self-contained, bypasses standard JWT filter).
 * Only registered when easyai.script-llm.enabled=true.
 */
@RestController
@RequestMapping("/api/internal/llm")
@ConditionalOnProperty(prefix = "easyai.script-llm", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class InternalLlmController(
    private val jwtTokenProvider: JwtTokenProvider,
    private val internalLlmService: InternalLlmService,
    private val authProperties: AuthProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Process a single LLM request synchronously.
     */
    @PostMapping("/process")
    fun process(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody request: InternalLlmRequest,
        exchange: ServerWebExchange
    ): Mono<InternalLlmResponse> = mono {
        assertLocalhost(exchange)
        val claims = authenticateScriptToken(authorization)
        val modelConfigId = resolveModelConfigId(request.modelConfigId, claims.modelConfigId)

        logger.debug("Internal LLM process request: userId={}, modelConfigId={}", claims.userId, modelConfigId)

        internalLlmService.process(
            userId = claims.userId,
            modelConfigId = modelConfigId,
            messages = request.messages,
            temperature = request.temperature,
            maxTokens = request.maxTokens
        )
    }

    /**
     * Process multiple items concurrently.
     */
    @PostMapping("/batch-process")
    fun batchProcess(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestBody request: BatchLlmRequest,
        exchange: ServerWebExchange
    ): Mono<BatchLlmResponse> = mono {
        assertLocalhost(exchange)
        val claims = authenticateScriptToken(authorization)
        val modelConfigId = resolveModelConfigId(request.modelConfigId, claims.modelConfigId)

        logger.info("Internal LLM batch request: userId={}, items={}, concurrency={}",
            claims.userId, request.items.size, request.concurrency ?: 5)

        internalLlmService.batchProcess(
            userId = claims.userId,
            modelConfigId = modelConfigId,
            instruction = request.instruction,
            items = request.items,
            concurrency = request.concurrency ?: 5
        )
    }

    /**
     * Validate the script token from the Authorization header.
     * When auth is disabled, falls back to system user with a default config.
     */
    private fun authenticateScriptToken(authorization: String?): JwtTokenProvider.ScriptTokenClaims {
        if (!authProperties.enabled) {
            // Auth disabled (dev mode): return a system-level claims
            // The modelConfigId must still be provided in the request
            return JwtTokenProvider.ScriptTokenClaims(
                userId = "system",
                sessionId = "dev",
                modelConfigId = "",
                tokenId = "dev"
            )
        }

        val token = authorization
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header")

        return jwtTokenProvider.validateScriptToken(token)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired script token")
    }

    /**
     * Resolve the effective modelConfigId, validating it is not blank.
     */
    private fun resolveModelConfigId(requestConfigId: String?, tokenConfigId: String): String {
        val configId = requestConfigId ?: tokenConfigId
        if (configId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,
                "modelConfigId is required (no token-bound default available)")
        }
        return configId
    }

    /**
     * Enforce localhost-only access for internal endpoints.
     */
    private fun assertLocalhost(exchange: ServerWebExchange) {
        val remote = exchange.request.remoteAddress?.address
        if (remote != null && !remote.isLoopbackAddress) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Internal endpoint is localhost-only")
        }
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
    }
}
