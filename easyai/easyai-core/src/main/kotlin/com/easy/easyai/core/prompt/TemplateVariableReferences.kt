package com.easy.easyai.core.prompt

/**
 * Detects whether a Jinja2 prompt template references a given context variable.
 *
 * Prompt assembly uses this to append a runtime section only when the template author did not
 * place it by hand; config validation uses the same rules.
 */
object TemplateVariableReferences {
    // Pre-compiled patterns with capture group for the variable name.
    // These cover: {{ var }}, {{var}}, {{ var.field }}, {{ var[0] }}, {{ var|filter }},
    // the whitespace-control forms {{- var }} / {%- if var %}, a parenthesized reference,
    // {% for x in var %}, {% for x in var.field %},
    // {% if var %}, {% if var.field %}, {% if not var %}, {% elif var %}
    private val OUTPUT_VAR_PATTERN = Regex("""\{\{-?\s*\(?(\w+)[\s.}|%\[()]""")
    private val FOR_LOOP_PATTERN = Regex("""\{%-?\s*for.*?\bin\s+\(?(\w+)[\s.%}()\[]""")
    private val IF_COND_PATTERN = Regex("""\{%-?\s*(?:if|elif)\s+(?:not\s+)?\(?(\w+)[\s.%}()\[]""")

    /** True when [template] outputs, iterates over, or branches on [variable]. */
    @JvmStatic
    fun references(template: String, variable: String): Boolean =
        matchesVariable(template, OUTPUT_VAR_PATTERN, variable) ||
            matchesVariable(template, FOR_LOOP_PATTERN, variable) ||
            matchesVariable(template, IF_COND_PATTERN, variable)

    private fun matchesVariable(template: String, pattern: Regex, variable: String): Boolean =
        pattern.findAll(template).any { match -> match.groupValues.getOrNull(1) == variable }
}
