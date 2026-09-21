package com.easy.easyai.core.skill

import java.nio.file.Path

/**
 * Skill granularity — determines which RAG slice a skill is indexed into.
 *
 * Mirrors [com.easy.easyai.core.memory.MemoryScope]: GLOBAL lives in the user's
 * home skill directories, PROJECT lives under a specific workspace.
 */
enum class SkillScope {
    /** Cross-project skill stored under `~/.easyai/skills/` (or `~/.agents/skills/`). */
    GLOBAL,
    /** Project-scoped skill stored under `{workspace}/.easyai/skills/`. */
    PROJECT
}

/**
 * Ownership context for skill operations, used to derive backend-level isolation
 * (EasyRAG biz_id slices `u_{userId}_s` / `u_{userId}-{seg}-{hash8}_s`).
 *
 * @param userId Owner user id as recorded in the `skill` catalog table; null or blank
 *   falls back to the `system` tenant (single-user/dev/desktop degradation).
 * @param projectPath Runtime project path; required for [SkillScope.PROJECT] addressing,
 *   absent means project-scoped reads degrade to the global slice only.
 */
data class SkillOwnerContext(
    val userId: String? = null,
    val projectPath: Path? = null
)
