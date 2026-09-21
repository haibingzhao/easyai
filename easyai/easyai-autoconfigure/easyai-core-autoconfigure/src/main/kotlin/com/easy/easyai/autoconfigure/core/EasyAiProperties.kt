package com.easy.easyai.autoconfigure.core

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "easyai")
data class EasyAiProperties(
    var model: String = "gpt-4o",
    var systemPrompt: String = "You are a helpful AI assistant.",
    var maxIterations: Int = 50,
    var maxRetries: Int = 3,
    var workDir: String = ".",
    var domain: String = "coding",
    var skills: SkillProperties = SkillProperties(),
    var memory: MemoryProperties = MemoryProperties(),
)

data class MemoryProperties(
    /** Whether the memory system is enabled (requires EasyRAG to be configured). */
    var enabled: Boolean = true,
)

data class SkillProperties(
    var enabled: Boolean = true,
    var paths: List<String> = emptyList(),
    var homeSkillDirs: List<String> = listOf(".agents/skills", ".easyai/skills"),
    var injectIntoSystemPrompt: Boolean = true,
    /** On-demand discovery through EasyRAG; off by default so behaviour is unchanged. */
    var rag: SkillRagProperties = SkillRagProperties(),
)

/**
 * Skill retrieval-index settings (`easyai.skills.rag.*`).
 *
 * [enabled] additionally requires `easyai.rag.enabled=true` (the index store) and
 * `easyai.r2dbc.enabled=true` (the catalog table that owns per-user slicing); with either missing
 * the wiring below never activates and the full skill list keeps being injected.
 */
data class SkillRagProperties(
    /** Master switch: index skills per owner and let `skill_search` discover them. */
    var enabled: Boolean = false,
    /** Skills returned per local search; applied to each granularity slice. */
    var searchTopK: Int = 5,
    /** Index writes in flight during startup reconciliation; bounds pressure on the RAG pipeline. */
    var indexConcurrency: Int = 4,
)

