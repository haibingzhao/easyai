package com.easy.easyai.web.service.validation

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.core.prompt.TemplateVariableReferences
import com.easy.easyai.web.model.ConfigValidationError

/**
 * Validates that template variables whose source must be configured have that backing.
 *
 * Runtime sections (skills, sub-agents, team members, project instructions, custom instructions)
 * are appended by the prompt assembler when the template does not reference them, so they are not
 * checked here.
 */
class TemplateConsistencyValidator : AgentConfigValidator {

    override suspend fun validate(request: AgentCreateRequest, userId: String, owners: Collection<String>): List<ConfigValidationError> {
        val errors = mutableListOf<ConfigValidationError>()
        val template = request.promptTemplate
        if (template.isNullOrBlank()) return errors

        // input.xxx requires inputSchema
        if (TemplateVariableReferences.references(template, "input") && request.inputSchema.isNullOrBlank()) {
            errors.add(
                ConfigValidationError(
                    "promptTemplate",
                    "Template references '{{ input.xxx }}' but inputSchema is not defined. Without inputSchema, input variables are unavailable.",
                    "warning"
                )
            )
        }

        return errors
    }
}
