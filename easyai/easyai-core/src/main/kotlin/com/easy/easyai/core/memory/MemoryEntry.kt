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
 * @property lastAccessed Date the entry was last retrieved by an agent; a staleness hint
 *   only — it never feeds the business time used by time-range filtering.
 * @property id Backend document id (EasyRAG docId); the stable reference used by consolidation.
 * @property archived True for a history entry absorbed by a consolidated entry. Archived entries
 *   are excluded from normal retrieval and can only be read back by id.
 * @property level Consolidation depth: source entries are 0, a consolidated entry is
 *   `max(sources) + 1`.
 * @property refs For a consolidated entry: the docIds it absorbed. Drives downward drill-down.
 * @property mergedInto For an absorbed source: the docId of the consolidated entry. Drives upward
 *   drill-down.
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
    val scenarios: List<String> = emptyList(),
    val lastAccessed: LocalDate? = null,
    val id: String? = null,
    val archived: Boolean = false,
    val level: Int = 0,
    val refs: List<String> = emptyList(),
    val mergedInto: String? = null
)
