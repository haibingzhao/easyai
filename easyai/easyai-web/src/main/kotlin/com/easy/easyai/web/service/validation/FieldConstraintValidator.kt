package com.easy.easyai.web.service.validation

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.web.model.ConfigValidationError

/**
 * Validates agent config field constraints (length, format).
 */
class FieldConstraintValidator : AgentConfigValidator {

    override suspend fun validate(request: AgentCreateRequest, userId: String): List<ConfigValidationError> {
        val errors = mutableListOf<ConfigValidationError>()

        if (request.id.isBlank() || request.id.length > MAX_CALLSIGN_LENGTH) {
            errors.add(ConfigValidationError("id", "Agent ID must be 1-${MAX_CALLSIGN_LENGTH} characters"))
        }
        if (request.name.isBlank() || request.name.length > MAX_NAME_LENGTH) {
            errors.add(ConfigValidationError("name", "Agent name must be 1-${MAX_NAME_LENGTH} characters"))
        }
        if ((request.description?.length ?: 0) > MAX_DESCRIPTION_LENGTH) {
            errors.add(ConfigValidationError("description", "Description must be ${MAX_DESCRIPTION_LENGTH} characters or less"))
        }

        return errors
    }

    companion object {
        private const val MAX_NAME_LENGTH = 20
        private const val MAX_CALLSIGN_LENGTH = 50
        private const val MAX_DESCRIPTION_LENGTH = 200
    }
}
