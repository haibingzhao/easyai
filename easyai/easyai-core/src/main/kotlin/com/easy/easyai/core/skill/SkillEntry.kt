package com.easy.easyai.core.skill

/**
 * A skill document exchanged with a [SkillStore] (RAG retrieval index layer).
 *
 * Deliberately has **no** `source` field: a skill in the index is an ordinary local skill and the
 * execution path must not branch on provenance. Provenance classification lives in the DB `skill`
 * catalog table instead (see [SkillCatalogEntry]).
 *
 * @param key logical key within the skill type, e.g. `skills/my-skill.md`
 * @param name skill name (frontmatter `name`)
 * @param description skill description (frontmatter `description`)
 * @param tags frontmatter `tags`
 * @param examples frontmatter `examples`
 * @param content full SKILL.md body (frontmatter + markdown), used for indexing and
 *   for materialization when the store holds the authoritative text
 * @param location absolute path of the on-disk SKILL.md for local skills (load_skill hint)
 * @param origin provenance info for local skills (the catalog row keeps the detailed record)
 * @param scope granularity back-derived from the chunk's `biz_id` (search results only)
 * @param score retrieval relevance (search results only)
 */
data class SkillEntry(
    val key: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val content: String,
    val location: String? = null,
    val origin: String? = null,
    val scope: SkillScope? = null,
    val score: Double? = null,
    /** SHA-256 of the raw source bytes, not the generated RAG document. */
    val checksum: String? = null
) {
    companion object {
        /** Directory segment of the logical key layout. */
        const val KEY_DIR: String = "skills"

        /**
         * Logical key of one skill document: `skills/{name}.md`.
         *
         * Shared by the indexing layer and its readers so the two can never drift; GLOBAL and
         * PROJECT skills with the same name produce the same key on purpose, they live in
         * different `biz_id` slices.
         */
        @JvmStatic
        fun keyFor(name: String): String = "$KEY_DIR/${sanitizeSegment(name)}.md"

        /**
         * Make one logical-key segment path-safe: skill names are slug-like already, this only
         * defends against path-hostile characters.
         *
         * Public so every producer of a `skills/…` key derives the segment the same way and can
         * never drift apart.
         */
        @JvmStatic
        fun sanitizeSegment(name: String): String {
            val cleaned = name.trim().map { c ->
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.') c else '-'
            }.joinToString("")
            return cleaned.ifBlank { "unnamed" }
        }
    }
}
