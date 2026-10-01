package com.easy.easyai.skills

/**
 * Configuration properties for the skill system.
 * Bound to `easyai.skills.*` prefix via Spring Boot @ConfigurationProperties.
 *
 * Skills are strictly owner-granular: every user (and the shared `system` layer) owns a root
 * directory `{rootDir}/{owner}` and nothing outside an owner root is ever scanned.
 */
data class SkillConfig(
    val enabled: Boolean = true,
    /** Parent directory of all owner skill roots, e.g. `~/.easyai/skills`. */
    val rootDir: String = "${System.getProperty("user.home")}/.easyai/skills",
    val injectIntoSystemPrompt: Boolean = true,
    /** Hard cap on one packed skill directory (zip bytes); rejects oversized packages before upload. */
    val packageMaxBytes: Long = 20L * 1024 * 1024
)
