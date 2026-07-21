package com.easy.easyai.web.service.validation

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.common.textio.template.InvalidTemplateException
import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.web.model.ConfigValidationError

/**
 * Validates Jinja2 template syntax for promptTemplate.
 */
class TemplateSyntaxValidator(
    private val templateRenderer: TemplateRenderer? = null,
) : AgentConfigValidator {

    override suspend fun validate(request: AgentCreateRequest, userId: String): List<ConfigValidationError> {
        val errors = mutableListOf<ConfigValidationError>()

        val template = request.promptTemplate
        if (template.isNullOrBlank() || templateRenderer == null) return errors

        try {
            templateRenderer.renderLiteralTemplate(template, emptyMap())
        } catch (e: InvalidTemplateException) {
            val msg = e.errors.joinToString("; ") { it.message }
            errors.add(ConfigValidationError("promptTemplate", "Jinja2 template error: $msg"))
        } catch (e: Exception) {
            errors.add(ConfigValidationError("promptTemplate", "Jinja2 template error: ${e.message}"))
        }

        return errors
    }
}
