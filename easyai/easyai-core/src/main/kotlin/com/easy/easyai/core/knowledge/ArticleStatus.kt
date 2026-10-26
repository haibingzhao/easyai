package com.easy.easyai.core.knowledge

/**
 * Lifecycle of a knowledge article.
 *
 * Drafts are creator-only and never enter the RAG index; publishing is the single action that makes
 * an article retrievable by the knowledge_* tools.
 */
enum class ArticleStatus {
    DRAFT,
    PUBLISHED;

    companion object {
        /** Column value as stored in `knowledge_article.status`. */
        @JvmStatic
        fun fromColumn(value: String?): ArticleStatus =
            if (value.equals(DRAFT.name, ignoreCase = true)) DRAFT else PUBLISHED

        /** Filters accept the lowercase wire value (`draft` / `published`). */
        @JvmStatic
        fun fromFilter(value: String?): ArticleStatus? = when (value?.lowercase()) {
            "draft" -> DRAFT
            "published" -> PUBLISHED
            else -> null
        }

        /** Lowercase wire value used by the REST DTO. */
        @JvmStatic
        fun toWire(value: ArticleStatus): String = value.name.lowercase()
    }
}
