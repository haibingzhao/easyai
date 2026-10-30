package com.easy.easyai.core.memory

import com.easy.easyai.core.domain.DomainCatalog
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * Storage category of a memory entry: the `{type}/{name}.md` directory and stable API value.
 *
 * The set of categories available to a deployment is **not** defined here — it lives in the
 * domain's [DomainCatalog] (`CodingDomainCatalog` in the framework, one contributor per host
 * application). This type only carries the storage key, so an entry written under another
 * domain's taxonomy still parses and round-trips unchanged.
 */
data class MemoryType(@get:JsonValue val dirName: String) {

    init {
        require(DomainCatalog.CODE_PATTERN.matches(dirName)) {
            "Invalid memory type code '$dirName': codes must match ${DomainCatalog.CODE_PATTERN.pattern}"
        }
    }

    companion object {
        /** JSON keeps the stable storage code; legacy enum names differ only by case. */
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromJson(value: String): MemoryType =
            of(value) ?: throw IllegalArgumentException("Invalid memory type code '$value'")

        @JvmField
        val USER_PREFERENCES = MemoryType("user_preferences")

        @JvmField
        val PROJECT_INFORMATION = MemoryType("project_information")

        @JvmField
        val DEVELOPMENT_STANDARDS = MemoryType("development_standards")

        @JvmField
        val TASK_SUMMARY = MemoryType("task_summary")

        @JvmField
        val EXPERIENCE_LESSONS = MemoryType("experience_lessons")

        @JvmField
        val OTHER = MemoryType(DomainCatalog.OTHER_CODE)

        /**
         * Tolerant parse of a stored type code (frontmatter, file path, tool argument).
         * Returns null for values that cannot be a storage directory name; callers fall back
         * to [OTHER]. Membership in the active domain is deliberately not checked here — use
         * [DomainCatalog.memoryType] for input validation.
         */
        @JvmStatic
        fun of(code: String): MemoryType? {
            val normalized = code.trim().lowercase()
            return if (DomainCatalog.CODE_PATTERN.matches(normalized)) MemoryType(normalized) else null
        }
    }
}
