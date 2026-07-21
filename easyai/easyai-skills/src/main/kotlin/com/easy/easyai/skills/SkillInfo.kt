package com.easy.easyai.skills

import java.nio.file.Path

/**
 * Represents a loaded skill's metadata and content.
 */
data class SkillInfo(
    val name: String,
    val description: String? = null,
    val location: Path,
    val content: String,
    val tags: Set<String> = emptySet(),
    val examples: Set<String> = emptySet(),
)