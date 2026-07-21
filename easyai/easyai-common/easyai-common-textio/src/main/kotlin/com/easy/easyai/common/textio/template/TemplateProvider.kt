package com.easy.easyai.common.textio.template

/**
 * Object that can provide templates through a logical name.
 * Creates an abstraction so that different
 * implementations can choose probabilistic or other methods
 * to map logical names to template resources.
 * Templates for a given name must fulfil the same contract,
 * taking the same variables (or a subset of) and producing
 * the same output.
 * Typically used to source prompts for interactions with LLMs.
 */
fun interface TemplateProvider {

    /**
     * Create system prompt for knowledge graph extraction
     */
    @Throws(NoSuchTemplateException::class)
    fun resolveTemplate(logicalName: String): CompiledTemplate
}
