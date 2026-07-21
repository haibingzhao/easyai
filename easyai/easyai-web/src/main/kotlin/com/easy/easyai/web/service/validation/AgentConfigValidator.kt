package com.easy.easyai.web.service.validation

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.web.model.ConfigValidationError

/**
 * Interface for validating agent config at different concern levels.
 */
interface AgentConfigValidator {
    suspend fun validate(request: AgentCreateRequest, userId: String): List<ConfigValidationError>
}
