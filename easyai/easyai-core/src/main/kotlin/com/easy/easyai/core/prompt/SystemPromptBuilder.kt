package com.easy.easyai.core.prompt

/**
 * Builds system prompt by assembling multiple segments.
 * Assembly order: providerPrompt -> customInstructions -> environmentInfo -> toolsList -> skillsList -> subAgentsList -> instructions -> memory -> outputSchema.
 * Empty segments are filtered out.
 */
class SystemPromptBuilder(
    private val promptLoader: ProviderPromptLoader
) {
    /**
     * Build system prompt segments from the given context.
     * @return list of non-empty prompt segments
     */
    fun build(context: AgentPromptConfig): List<String> = buildList {
        fun addIfNotBlank(value: String?) {
            value?.takeIf { it.isNotBlank() }?.let { add(it) }
        }

        // 1. Provider prompt based on protocol
        if (!context.protocol.isNullOrBlank()) {
            add(promptLoader.getPromptForProtocol(context.protocol))
        }

        // 2. Custom instructions from agent definition
        addIfNotBlank(context.customInstructions)

        // 3. Environment info (runtime generated)
        if (!context.environmentInfo.isNullOrBlank()) {
            add(context.environmentInfo)
        } else {
            val envSegment = EnvironmentInfoSegment.generate(context.cwd, context.os)
            if (envSegment.isNotBlank()) {
                add(envSegment)
            }
        }

        // 3.5 Script LLM access info (when available)
        addIfNotBlank(context.scriptLlmSegment)

        // 4-6. Tools, skills, sub-agents lists (runtime generated)
        addIfNotBlank(context.toolsList)
        addIfNotBlank(context.skillsList)
        addIfNotBlank(context.subAgentsList)

        // 7. Project instructions (AGENTS.md, runtime loaded)
        addIfNotBlank(context.instructionsSegment)

        // 8. Memory (persistent cross-session knowledge)
        addIfNotBlank(context.memorySegment)

        // 9. Output schema format instructions (supplementary)
        addIfNotBlank(context.outputSchemaSegment)
    }
}
