package com.easy.easyai.core.knowledge

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnowledgeTreeTest {

    private fun folder(
        id: String,
        name: String,
        parentId: String? = null,
        userId: String = "alice",
    ) = KnowledgeFolder(
        id = id,
        userId = userId,
        parentId = parentId,
        name = name,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private val family = folder("f-family", "Family")
    private val recipes = folder("f-recipes", "Recipes", parentId = "f-family")
    private val dinner = folder("f-dinner", "Dinner", parentId = "f-recipes")
    private val work = folder("f-work", "Work")
    private val folders = listOf(family, recipes, dinner, work)

    @Nested
    inner class PathOf {

        @Test
        fun `builds the ancestor chain`() {
            val byId = folders.associateBy { it.id }

            assertEquals("Family/Recipes/Dinner", KnowledgeTree.pathOf(dinner.id, byId))
        }

        @Test
        fun `unknown or null ids render empty`() {
            val byId = folders.associateBy { it.id }

            assertEquals("", KnowledgeTree.pathOf(null, byId))
            assertEquals("", KnowledgeTree.pathOf("missing", byId))
        }

        @Test
        fun `a cycle does not loop forever`() {
            val a = folder("a", "A", parentId = "b")
            val b = folder("b", "B", parentId = "a")

            assertEquals("B/A", KnowledgeTree.pathOf("a", mapOf("a" to a, "b" to b)))
        }
    }

    @Nested
    inner class SubtreeIds {

        @Test
        fun `includes the root and all descendants`() {
            assertEquals(
                setOf(family.id, recipes.id, dinner.id),
                KnowledgeTree.subtreeIds(family.id, folders),
            )
        }

        @Test
        fun `a leaf is its own subtree`() {
            assertEquals(setOf(dinner.id), KnowledgeTree.subtreeIds(dinner.id, folders))
        }
    }

    @Nested
    inner class Resolve {

        @Test
        fun `resolves a path inside one bucket`() {
            assertEquals(dinner, KnowledgeTree.resolve("alice", "Family/Recipes/Dinner", folders))
        }

        @Test
        fun `tolerates surrounding blanks`() {
            assertEquals(recipes, KnowledgeTree.resolve("alice", " Family / Recipes ", folders))
        }

        @Test
        fun `does not resolve into another bucket`() {
            assertNull(KnowledgeTree.resolve("bob", "Family", folders))
        }

        @Test
        fun `returns null for an unknown segment`() {
            assertNull(KnowledgeTree.resolve("alice", "Family/Nope", folders))
            assertNull(KnowledgeTree.resolve("alice", "", folders))
        }
    }
}
