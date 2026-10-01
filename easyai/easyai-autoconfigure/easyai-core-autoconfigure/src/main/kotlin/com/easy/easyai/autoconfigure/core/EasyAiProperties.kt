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
    /** Parent directory of the owner skill roots (`{root-dir}/{userId}`). */
    var rootDir: String = "${System.getProperty("user.home")}/.easyai/skills",
    var injectIntoSystemPrompt: Boolean = true,
    /**
     * Owners with at most this many effective skills keep the full listing in the system prompt
     * even when the RAG index is ready — search round-trips are not worth it at small scale.
     * Set 0 to restore "ready index suppresses the listing" for every owner.
     */
    var directInjectMaxCount: Int = 8,
    /** Hard cap on one packed skill package in bytes; larger directories are refused before upload. */
    var packageMaxBytes: Long = 20L * 1024 * 1024,
    /** On-demand discovery through EasyRAG; off by default so behaviour is unchanged. */
    var rag: SkillRagProperties = SkillRagProperties(),
    /** Turn-level decision-model routing; only active when the user configured a SKILL_SELECTION aux model. */
    var selection: SkillSelectionProperties = SkillSelectionProperties(),
)

/**
 * Skill routing settings (`easyai.skills.selection.*`). Cheap to keep on: with no
 * `SKILL_SELECTION` aux model configured for the user the router short-circuits before any
 * HTTP call, leaving behavior identical to unrouted runs.
 */
data class SkillSelectionProperties(
    /** Master switch for the per-message System One routing. */
    var enabled: Boolean = true,
    /** Choices below this confidence keep the baseline visibility instead of narrowing to one skill. */
    var minConfidence: Double = 0.5,
    /** HTTP budget for one decision call; a timeout degrades to the baseline. */
    var timeoutMs: Long = 5_000,
)

/**
 * Skill retrieval-index settings (`easyai.skills.rag.*`).
 *
 * [enabled] additionally requires `easyai.rag.enabled=true` (the index backend). With it off the
 * catalog/package pipeline still syncs and restores skill directories, but no search index exists,
 * so the wiring keeps injecting the full skill list into the prompt.
 */
data class SkillRagProperties(
    /** Master switch: index skills per owner and let `skill_search` discover them. */
    var enabled: Boolean = false,
    /** Skills returned per local search; applied as a quota to each owner slice. */
    var searchTopK: Int = 5,
    /** Index writes in flight during startup reconciliation; bounds pressure on the RAG pipeline. */
    var indexConcurrency: Int = 4,
)

