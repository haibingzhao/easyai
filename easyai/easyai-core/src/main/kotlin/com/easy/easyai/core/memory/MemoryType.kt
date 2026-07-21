package com.easy.easyai.core.memory

/**
 * Memory entry types, determining the storage directory and categorization.
 */
enum class MemoryType(val dirName: String) {
    /** User preferences, role, background info. */
    USER("user"),
    /** Behavioral guidance — what to do or avoid. */
    FEEDBACK("feedback"),
    /** Active project context, decisions, constraints. */
    PROJECT("project"),
    /** Pointers to external systems, dashboards, docs. */
    REFERENCE("reference");

    companion object {
        private val byDirName = entries.associateBy { it.dirName }

        /** Resolve a type from its directory name (case-insensitive), or null if not found. */
        @JvmStatic
        fun fromDirName(name: String): MemoryType? = byDirName[name.lowercase()]
    }
}
