package com.easy.easyai.skills

/**
 * Configuration properties for skill sources.
 * Bound to `easyai.skills.*` prefix via Spring Boot @ConfigurationProperties.
 */
data class SkillConfig(
    val enabled: Boolean = true,
    val paths: List<String> = emptyList(),
    val homeSkillDirs: List<String> = listOf(".agents/skills", ".easyai/skills"),
    val injectIntoSystemPrompt: Boolean = true,
    val systemPromptFormat: SkillPromptFormat = SkillPromptFormat.CONCISE,
    val workDir: String = ".",
)

enum class SkillPromptFormat { VERBOSE, CONCISE }