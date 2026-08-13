package com.easy.easyai.core.memory

/**
 * Memory entry categories, determining the storage directory and classification.
 * Mirrors the knowledge-base taxonomy shown in the Memory UI.
 */
enum class MemoryType(val dirName: String) {
    /** User preferences, role, background info. */
    USER_PREFERENCES("user_preferences"),
    /** Active project context, decisions, constraints. */
    PROJECT_INFORMATION("project_information"),
    /** Coding and process standards the agent must follow. */
    DEVELOPMENT_STANDARDS("development_standards"),
    /** Completed task retrospectives and execution summaries. */
    TASK_SUMMARY("task_summary"),
    /** Reusable experience and lessons learned. */
    EXPERIENCE_LESSONS("experience_lessons"),
    /** Anything that does not fit the other categories. */
    OTHER("other");

    companion object {
        private val byDirName = entries.associateBy { it.dirName }

        /** Resolve a type from its directory name (case-insensitive), or null if not found. */
        @JvmStatic
        fun fromDirName(name: String): MemoryType? = byDirName[name.lowercase()]
    }
}
