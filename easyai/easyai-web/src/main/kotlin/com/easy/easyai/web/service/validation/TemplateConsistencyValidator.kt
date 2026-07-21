package com.easy.easyai.web.service.validation

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.web.model.ConfigValidationError

/**
 * Validates consistency between promptTemplate and config fields.
 *
 * Checks that template variable references have corresponding config backing.
 */
class TemplateConsistencyValidator : AgentConfigValidator {

    override suspend fun validate(request: AgentCreateRequest, userId: String): List<ConfigValidationError> {
        val errors = mutableListOf<ConfigValidationError>()
        val template = request.promptTemplate
        if (template.isNullOrBlank()) return errors

        // 1. input.xxx requires inputSchema
        if (referencesTemplateVariable(template, "input") && request.inputSchema.isNullOrBlank()) {
            errors.add(
                ConfigValidationError(
                    "promptTemplate",
                    "Template references '{{ input.xxx }}' but inputSchema is not defined. Without inputSchema, input variables are unavailable.",
                    "warning"
                )
            )
        }

        // 2. tools requires toolNames or mcpConfigs
        if (referencesTemplateVariable(template, "tools") &&
            request.toolNames.isEmpty() && request.mcpConfigs.isEmpty()) {
            errors.add(
                ConfigValidationError(
                    "promptTemplate",
                    "Template references '{{ tools }}' but no tools are configured (toolNames and mcpConfigs are both empty). The tools section will be empty.",
                    "warning"
                )
            )
        }

        // 3. skills requires skillNames
        if (referencesTemplateVariable(template, "skills") && request.skillNames.isEmpty()) {
            errors.add(
                ConfigValidationError(
                    "promptTemplate",
                    "Template references '{{ skills }}' but no skills are configured (skillNames is empty). The skills section will be empty.",
                    "warning"
                )
            )
        }

        // 4. sub_agents requires subAgentIds
        if (referencesTemplateVariable(template, "sub_agents") && request.subAgentIds.isEmpty()) {
            errors.add(
                ConfigValidationError(
                    "promptTemplate",
                    "Template references '{{ sub_agents }}' but no sub-agents are configured (subAgentIds is empty). The sub_agents section will be empty.",
                    "warning"
                )
            )
        }

        // 5. instructions requires instructionsEnabled
        if (referencesTemplateVariable(template, "instructions") && request.instructionsEnabled == false) {
            errors.add(
                ConfigValidationError(
                    "promptTemplate",
                    "Template references '{{ instructions }}' but instructionsEnabled is false. Instructions will not be loaded.",
                    "warning"
                )
            )
        }

        // 6. customInstructions is redundant when promptTemplate exists but doesn't reference custom_instructions
        if (!request.customInstructions.isNullOrBlank() &&
            !referencesTemplateVariable(template, "custom_instructions")) {
            errors.add(
                ConfigValidationError(
                    "customInstructions",
                    "customInstructions is set but promptTemplate does not reference '{{ custom_instructions }}'. The customInstructions will not appear in the rendered prompt.",
                    "warning"
                )
            )
        }

        return errors
    }

    companion object {
        // Pre-compiled patterns with capture group for variable name.
        // These cover: {{ var }}, {{var}}, {{ var.field }}, {{ var|filter }},
        // {% for x in var %}, {% for x in var.field %},
        // {% if var %}, {% if var.field %}, {% if not var %}, {% elif var %}
        private val OUTPUT_VAR_PATTERN = Regex("""\{\{\s*(\w+)[\s.}|%]""")
        private val FOR_LOOP_PATTERN = Regex("""\{%\s*for.*?\bin\s+(\w+)[\s.%}]""")
        private val IF_COND_PATTERN = Regex("""\{%\s*(?:if|elif)\s+(?:not\s+)?(\w+)[\s.%}]""")

        /**
         * Check if a Jinja2 template references a given variable name.
         */
        fun referencesTemplateVariable(template: String, variable: String): Boolean {
            return OUTPUT_VAR_PATTERN.containsVariable(template, variable) ||
                FOR_LOOP_PATTERN.containsVariable(template, variable) ||
                IF_COND_PATTERN.containsVariable(template, variable)
        }

        private fun Regex.containsVariable(template: String, variable: String): Boolean {
            return findAll(template).any { match ->
                match.groupValues.getOrNull(1) == variable
            }
        }
    }
}
