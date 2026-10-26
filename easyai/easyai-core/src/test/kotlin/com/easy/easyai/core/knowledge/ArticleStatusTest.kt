package com.easy.easyai.core.knowledge

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArticleStatusTest {

    @Nested
    inner class FromColumn {

        @Test
        fun `draft column values map to DRAFT regardless of case`() {
            assertEquals(ArticleStatus.DRAFT, ArticleStatus.fromColumn("draft"))
            assertEquals(ArticleStatus.DRAFT, ArticleStatus.fromColumn("DRAFT"))
        }

        @Test
        fun `published and unknown column values map to PUBLISHED`() {
            assertEquals(ArticleStatus.PUBLISHED, ArticleStatus.fromColumn("published"))
            assertEquals(ArticleStatus.PUBLISHED, ArticleStatus.fromColumn("PUBLISHED"))
            assertEquals(ArticleStatus.PUBLISHED, ArticleStatus.fromColumn("legacy"))
            assertEquals(ArticleStatus.PUBLISHED, ArticleStatus.fromColumn(null))
        }
    }

    @Nested
    inner class FromFilter {

        @Test
        fun `wire values map case-insensitively`() {
            assertEquals(ArticleStatus.DRAFT, ArticleStatus.fromFilter("draft"))
            assertEquals(ArticleStatus.PUBLISHED, ArticleStatus.fromFilter("PUBLISHED"))
        }

        @Test
        fun `missing or unknown filters mean no filter`() {
            assertNull(ArticleStatus.fromFilter(null))
            assertNull(ArticleStatus.fromFilter("draftt"))
            assertNull(ArticleStatus.fromFilter(""))
        }
    }
}
