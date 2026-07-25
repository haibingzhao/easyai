package com.easy.easyai.web.service

import com.easy.easyai.auth.jwt.JwtTokenProvider
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.tool.ScriptEnvProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Default implementation of [ScriptEnvProvider] that generates a short-lived
 * script token and assembles environment variables for script LLM access.
 *
 * Only active when `easyai.script-llm.enabled=true`.
 */
@Component
@ConditionalOnProperty(prefix = "easyai.script-llm", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class DefaultScriptEnvProvider(
    private val jwtTokenProvider: JwtTokenProvider,
    private val environment: Environment
) : ScriptEnvProvider {

    override fun getScriptEnv(context: AgentContext): Map<String, String> {
        val userId = context.userId ?: return emptyMap()
        val sessionId = context.sessionId ?: return emptyMap()
        val configId = context.configId.takeIf { it.isNotBlank() } ?: return emptyMap()

        val token = jwtTokenProvider.generateScriptToken(userId, sessionId, configId)
        val port = environment.getProperty("local.server.port", "8080")

        return mapOf(
            "EASYAI_SCRIPT_TOKEN" to token,
            "EASYAI_BACKEND_URL" to "http://127.0.0.1:$port",
            "EASYAI_MODEL_CONFIG_ID" to configId
        )
    }
}
