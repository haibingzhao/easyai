package com.easy.easyai.core.memory

import java.time.LocalDate

/**
 * A single memory entry stored as a Markdown file with YAML frontmatter.
 *
 * @property name Unique identifier, e.g. "user_testing_preference". Used as file name: `{type}/{name}.md`.
 * @property description One-line summary shown in MEMORY.md index.
 * @property type Memory category determining the storage subdirectory.
 * @property content Markdown body (after frontmatter).
 * @property path Relative path from memory root, e.g. "feedback/user_testing_preference.md".
 * @property created Creation date.
 * @property updated Last modification date.
 * @property maturity Maturity level (low/medium/high), null when not set.
 * @property scenarios Usage scenarios that this memory applies to.
 */
data class MemoryEntry(
    val name: String,
    val description: String,
    val type: MemoryType,
    val content: String,
    val path: String,
    val keywords: List<String> = emptyList(),
    val created: LocalDate? = null,
    val updated: LocalDate? = null,
    val maturity: MemoryMaturity? = null,
    val scenarios: List<String> = emptyList()
)
