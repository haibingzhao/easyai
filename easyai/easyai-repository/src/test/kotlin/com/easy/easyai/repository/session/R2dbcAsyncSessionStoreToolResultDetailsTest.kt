package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * A tool's `details` is the renderer channel: hosts replay it from history instead of
 * re-deriving a card from prose. That only works if the structured payload survives the
 * message store unchanged, alongside the tool call that names it.
 */
class R2dbcAsyncSessionStoreToolResultDetailsTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///tool_details_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private val store = R2dbcAsyncSessionStore(db)

    @Test
    fun `tool result details survive a database round trip`() = runTest {
        val sessionId = "details-${UUID.randomUUID()}"
        val context = AgentContext(agentId = "page-assistant", sessionId = sessionId)
        store.ensureSessionExists(sessionId, context)
        val details = mapOf<String, Any?>(
            "kind" to "form",
            "title" to "本月支出",
            "fields" to listOf(
                mapOf(
                    "key" to "total",
                    "label" to "合计",
                    "type" to "currency",
                    "value" to 128.5,
                    "unit" to "元",
                ),
            ),
        )
        store.upsertMessages(
            context,
            sessionId,
            listOf(
                AssistantMessage(
                    id = "a1",
                    content = listOf(
                        ToolCallContent(id = "c1", name = "home_form_visual", arguments = """{"title":"本月支出"}"""),
                    ),
                ),
                ToolResultMessage(
                    id = "t1",
                    toolResults = listOf(
                        ToolResultEntry(
                            toolCallId = "c1",
                            toolName = "home_form_visual",
                            result = "已生成数据卡",
                            details = details,
                        ),
                    ),
                ),
            ),
        )

        val messages = store.loadMessagesWithTimestamps(sessionId).map { it.message }
        val call = messages.filterIsInstance<AssistantMessage>().single().toolCalls().single()
        val result = messages.filterIsInstance<ToolResultMessage>().single().toolResults.single()

        assertEquals("c1", call.id)
        assertEquals("home_form_visual", result.toolName)
        assertEquals("form", result.details["kind"])
        val fields = result.details["fields"] as List<*>
        assertEquals(128.5, (fields.single() as Map<*, *>)["value"])
    }
}
