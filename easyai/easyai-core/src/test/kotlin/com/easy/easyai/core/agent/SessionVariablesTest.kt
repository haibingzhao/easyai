package com.easy.easyai.core.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class SessionVariablesTest {

    @Test
    fun `restoring persisted variables does not overwrite trusted ones`() {
        val variables = SessionVariables()
        variables.putTrusted("home.userId", "alice")

        variables.loadAll(mapOf("home.userId" to "stale", "extracted" to "value"))

        assertEquals("alice", variables.getAll()["home.userId"])
        assertEquals("value", variables.getAll()["extracted"])
    }

    @Test
    fun `compaction replacement keeps trusted keys`() {
        val variables = SessionVariables()
        variables.put("draft", "old")
        variables.putTrusted("home.userId", "alice")
        variables.putTrusted("home.familyId", "family-1")

        variables.replaceAll(mapOf("draft" to "new"))

        assertEquals(
            mapOf("draft" to "new", "home.userId" to "alice", "home.familyId" to "family-1"),
            variables.getAll()
        )
    }

    @Test
    fun `trusted values are visible through getAll`() {
        val variables = SessionVariables()
        variables.putTrusted("home.userId", "alice")

        assertEquals(mapOf("home.userId" to "alice"), variables.getAll())
    }

    @Test
    fun `the conversation-owned view excludes trusted values`() {
        val variables = SessionVariables()
        variables.put("draft", "value")
        variables.putTrusted("home.familyId", "family-1")

        assertEquals(mapOf("draft" to "value"), variables.getConversationOwned())
        assertEquals(mapOf("draft" to "value", "home.familyId" to "family-1"), variables.getAll())
    }

    @Test
    fun `a conversation write cannot overwrite a trusted key`() {
        val variables = SessionVariables()
        variables.putTrusted("home.userId", "alice")

        variables.put("home.userId", "mallory")

        assertEquals("alice", variables.getAll()["home.userId"])
    }

    @Test
    fun `removing a trusted key also revokes it for later replacements`() {
        val variables = SessionVariables()
        variables.putTrusted("home.userId", "alice")

        variables.remove("home.userId")
        variables.replaceAll(mapOf("draft" to "value"))

        assertEquals(mapOf("draft" to "value"), variables.getAll())
    }

    @Test
    fun `getAll is a read-only view`() {
        val variables = SessionVariables()
        variables.putTrusted("home.userId", "alice")

        @Suppress("UNCHECKED_CAST")
        val mutable = variables.getAll() as MutableMap<String, String>

        assertFailsWith<UnsupportedOperationException> { mutable["home.userId"] = "mallory" }
        assertEquals("alice", variables.getAll()["home.userId"])
    }

    @Test
    fun `concurrent replaceAll never exposes a partial snapshot`() = runBlocking {
        val variables = SessionVariables()
        variables.putTrusted("home.userId", "alice")
        variables.putTrusted("home.familyId", "family-1")
        variables.put("draft", "old")

        val writer = launch(Dispatchers.Default) {
            repeat(20_000) {
                variables.replaceAll(mapOf("home.userId" to "system", "draft" to "model-$it"))
            }
        }
        val reader = launch(Dispatchers.Default) {
            repeat(20_000) {
                val state = variables.getAll()
                assertEquals("alice", state["home.userId"])
                assertEquals("family-1", state["home.familyId"])
            }
        }
        writer.join()
        reader.join()
    }
}
