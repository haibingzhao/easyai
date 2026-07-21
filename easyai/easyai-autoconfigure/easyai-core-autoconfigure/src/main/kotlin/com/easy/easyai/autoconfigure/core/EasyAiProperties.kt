package com.easy.easyai.autoconfigure.core

import com.easy.easyai.skills.SkillPromptFormat
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "easyai")
data class EasyAiProperties(
    var model: String = "gpt-4o",
    var systemPrompt: String = "You are a helpful AI assistant.",
    var maxIterations: Int = 50,
    var maxRetries: Int = 3,
    var workDir: String = ".",
    var skills: SkillProperties = SkillProperties(),
    var memory: MemoryProperties = MemoryProperties(),
)

data class MemoryProperties(
    /** Whether the memory system is enabled. */
    var enabled: Boolean = false,
    /** Root directory for global memories. */
    var globalDir: String = "~/.easyai/memory",
    /** Root directory for project-scoped memories (relative to workDir). */
    var projectDir: String = ".easyai/memory",
)

data class SkillProperties(
    var enabled: Boolean = true,
    var paths: List<String> = emptyList(),
    var homeSkillDirs: List<String> = listOf(".agents/skills", ".easyai/skills"),
    var injectIntoSystemPrompt: Boolean = true,
    var systemPromptFormat: SkillPromptFormat = SkillPromptFormat.CONCISE,
)

