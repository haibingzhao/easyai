package com.easy.easyai.core.memory

import com.easy.easyai.core.domain.DomainCatalog

/**
 * Memory entry categories, determining the storage directory and classification.
 * Mirrors the knowledge-base taxonomy shown in the Memory UI.
 *
 * Each entry carries a [domains] set indicating which application domains it
 * belongs to. Shared categories (e.g. user_preferences) appear in multiple
 * domains; domain-specific categories (e.g. development_standards for coding)
 * are restricted to a single domain.
 */
enum class MemoryType(val dirName: String, val domains: Set<String>) {
    /** User preferences, role, background info. */
    USER_PREFERENCES("user_preferences", setOf("coding", "trading")),
    /** Active project context, decisions, constraints. */
    PROJECT_INFORMATION("project_information", setOf("coding", "trading")),
    /** Coding and process standards the agent must follow. */
    DEVELOPMENT_STANDARDS("development_standards", setOf("coding")),
    /** Completed task retrospectives and execution summaries. */
    TASK_SUMMARY("task_summary", setOf("coding", "trading")),
    /** Reusable experience and lessons learned. */
    EXPERIENCE_LESSONS("experience_lessons", setOf("coding", "trading")),
    /** Anything that does not fit the other categories. */
    OTHER("other", setOf("coding", "trading")),
    /** Market observations and macro views (trading domain). */
    MARKET_INSIGHTS("market_insights", setOf("trading")),
    /** Strategy back-test lessons and insights (trading domain). */
    STRATEGY_LESSONS("strategy_lessons", setOf("trading")),
    /** Risk management red lines and rules (trading domain). */
    RISK_RULES("risk_rules", setOf("trading"));

    companion object {
        private val byDirName = entries.associateBy { it.dirName }

        /** Resolve a type from its directory name (case-insensitive), or null if not found. */
        @JvmStatic
        fun fromDirName(name: String): MemoryType? = byDirName[name.lowercase()]

        /**
         * Return all memory types available for the given [domain].
         * Used by prompt builders, validators, and UI category lists.
         * Falls back to the coding domain's types for unknown domains.
         */
        @JvmStatic
        fun entriesFor(domain: String): List<MemoryType> =
            entries.filter { domain.lowercase() in it.domains }
                .ifEmpty { entries.filter { "coding" in it.domains } }
    }
}
