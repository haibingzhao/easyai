package com.easy.easyai.core.prompt

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateVariableReferencesTest {

    @Test
    fun `detects output references`() {
        assertTrue(TemplateVariableReferences.references("{{ skills }}", "skills"))
        assertTrue(TemplateVariableReferences.references("{{skills}}", "skills"))
        assertTrue(TemplateVariableReferences.references("{{ skills|length }}", "skills"))
        assertTrue(TemplateVariableReferences.references("{{ skills.payload }}", "skills"))
    }

    @Test
    fun `detects loop and condition references`() {
        assertTrue(
            TemplateVariableReferences.references(
                "{% for s in skills %}{{ s.name }}{% endfor %}",
                "skills"
            )
        )
        assertTrue(TemplateVariableReferences.references("{% if skills %}x{% endif %}", "skills"))
        assertTrue(TemplateVariableReferences.references("{% if not skills %}x{% endif %}", "skills"))
        assertTrue(TemplateVariableReferences.references("{% elif skills %}x{% endif %}", "skills"))
    }

    @Test
    fun `detects subscript, parenthesized and whitespace-control references`() {
        assertTrue(TemplateVariableReferences.references("{{ skills[0].name }}", "skills"))
        assertTrue(TemplateVariableReferences.references("{{ (skills) }}", "skills"))
        assertTrue(TemplateVariableReferences.references("{% if (skills) %}x{% endif %}", "skills"))
        assertTrue(TemplateVariableReferences.references("{% for s in (skills) %}{% endfor %}", "skills"))
        assertTrue(TemplateVariableReferences.references("{{- skills }}", "skills"))
        assertTrue(TemplateVariableReferences.references("{%- if skills %}x{% endif %}", "skills"))
    }

    @Test
    fun `does not match a variable whose name merely contains the target`() {
        assertFalse(TemplateVariableReferences.references("{{ custom_instructions }}", "instructions"))
        assertFalse(TemplateVariableReferences.references("{{ instructions }}", "skills"))
        assertFalse(TemplateVariableReferences.references("{{ skill.name }}", "skills"))
        assertFalse(TemplateVariableReferences.references("{{ instructions_count }}", "instructions"))
    }
}
