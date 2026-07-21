package com.easy.easyai.web.controller

import com.easy.easyai.web.model.AiConfigGenerateRequest
import com.easy.easyai.web.model.AiConfigValidateRequest
import com.easy.easyai.web.model.ConfigValidationResult
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.service.ConfigValidator
import com.easy.easyai.web.service.configgen.AgentBasedConfigGenerator
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * REST controller for AI-powered config generation and validation.
 *
 * Endpoints:
 * - POST /api/ai-config/generate/stream  - SSE streaming generate for Agent or Swarm config
 * - POST /api/ai-config/validate         - Validate existing config without LLM
 *
 * Generation uses AgentLoop with tools (validate_config, list_resources, submit_config)
 * for multi-step generation with self-validation.
 */
@RestController
@RequestMapping("/api/ai-config")
class AiConfigController(
    private val configValidator: ConfigValidator,
    private val agentBasedConfigGenerator: AgentBasedConfigGenerator,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * SSE streaming generate using AgentLoop with tools.
     */
    @PostMapping("/generate/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun generateStream(@RequestBody request: AiConfigGenerateRequest): Flux<ServerSentEvent<String>> {
        require(request.description.isNotBlank()) { "Description must not be blank" }
        require(request.configType in VALID_CONFIG_TYPES) {
            "Invalid configType: ${request.configType}. Must be one of: ${VALID_CONFIG_TYPES.joinToString()}"
        }

        logger.info("Config generation request: type={}, descLength={}",
            request.configType, request.description.length)

        return flow {
            val userId = getCurrentUserId()
            agentBasedConfigGenerator.generate(request, userId, this)
        }.asFlux()
            .timeout(Duration.ofSeconds(1200))
    }

    /**
     * Validate an existing config without calling LLM.
     * Useful for client-side validation before saving.
     */
    @PostMapping("/validate")
    fun validate(@RequestBody request: AiConfigValidateRequest): Mono<ConfigValidationResult> = mono {
        require(request.configType in VALID_CONFIG_TYPES) {
            "Invalid configType: ${request.configType}. Must be one of: ${VALID_CONFIG_TYPES.joinToString()}"
        }
        val userId = getCurrentUserId()
        when (request.configType) {
            "agent" -> configValidator.validateAgentConfig(request.config, userId)
            "swarm" -> configValidator.validateSwarmConfig(request.config, userId)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown configType")
        }
    }

    private companion object {
        val VALID_CONFIG_TYPES = listOf("agent", "swarm")
    }
}
