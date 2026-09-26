package com.easy.easyai.tools.shell

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class BashToolTest {

    private val builder = BashToolBuilder()

    @Nested
    inner class `Parameter contract` {

        @Test
        fun `schema includes optional description with user facing guidance`() {
            val tool = BashTool(builder.metadata)
            val schema = SharedObjectMapper.instance.readTree(tool.inputSchema)
            val description = schema.path("properties").path("description")
            assertTrue(description.path("type").any { it.asString() == "string" })
            assertTrue(description.path("type").any { it.asString() == "null" })
            assertTrue(description.path("description").asString().contains("user's language"))
            assertTrue(schema.path("required").any { it.asString() == "command" })
            assertFalse(schema.path("required").any { it.asString() == "description" })
            assertNull(BashCommandParams(command = "true").description)
        }

        @Test
        fun `permission pattern keys remain command and cmd only`() {
            assertEquals(listOf("command", "cmd"), builder.metadata.patternKeys)
            assertEquals(listOf("command", "cmd"), BashTool(builder.metadata).patternKeys)
        }
    }

    @Nested
    inner class `Description isolation` {

        @TempDir
        lateinit var workDir: Path

        @Test
        fun `description does not change execution or enter the shell`() = runBlocking {
            val tool = BashTool(builder.metadata, workDir)
            val context = AgentContext(agentId = "test", projectPath = workDir)
            suspend fun execute(args: Map<String, Any?>): ToolResult = tool.execute(
                agentContext = context,
                toolCallId = "bash-test",
                args = args,
                coroutineScope = this
            )
            val description = "\nprintf 'injected' > description-marker\n" +
                "\$(printf 'substitution' > substitution-marker)"
            for (exitCode in listOf(0, 7)) {
                val args = mapOf("command" to "printf 'command-output\\n'; printf '%s\\n' \"\$0\" \"\$@\"; (exit $exitCode)")
                val withoutDescription = execute(args)
                val withDescription = execute(args + ("description" to description))
                val withNullDescription = execute(args + ("description" to null))
                val content = withoutDescription.content.single() as ToolResultContent
                assertTrue(content.output.startsWith("command-output\n"))
                assertEquals(exitCode, content.exitCode)
                assertEquals(exitCode != 0, withoutDescription.isError)
                assertEquals(withoutDescription, withDescription)
                assertEquals(withoutDescription, withNullDescription)
                assertFalse(Files.exists(workDir.resolve("description-marker")))
                assertFalse(Files.exists(workDir.resolve("substitution-marker")))
            }
        }
    }
}
