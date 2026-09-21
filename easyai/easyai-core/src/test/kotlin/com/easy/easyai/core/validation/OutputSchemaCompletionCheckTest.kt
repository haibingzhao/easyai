package com.easy.easyai.core.validation

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [OutputSchemaCompletionCheck] multi-turn behaviour, in particular the fast path
 * that accepts a schema-conforming tool-phase output instead of forcing an extra
 * API-level structured-output iteration.
 *
 * The check is stateless: how far along the run is comes from
 * [CompletionCheckInput.nudgeAttempt], which the agent loop's per-run ledger supplies.
 */
class OutputSchemaCompletionCheckTest {

    private val schema = """
        {
          "type": "object",
          "properties": {
            "summary": {"type": "string"},
            "confidence": {"type": "integer", "minimum": 0, "maximum": 100}
          },
          "required": ["summary", "confidence"]
        }
    """.trimIndent()

    private fun assistant(text: String) = AssistantMessage(content = listOf(TextContent(text)))

    private fun inputOf(sessionId: String, vararg texts: String) = CompletionCheckInput(
        agentContext = AgentContext(
            agentId = "test-agent",
            sessionId = sessionId,
            outputSchema = schema,
            outputSchemaMultiTurn = true,
        ),
        transcript = texts.map { assistant(it) },
        turnId = 0,
    )

    private fun inputWithContext(context: AgentContext, turnId: Int, nudgeAttempt: Int, vararg texts: String) =
        CompletionCheckInput(
            agentContext = context,
            transcript = texts.map { assistant(it) },
            turnId = turnId,
            nudgeAttempt = nudgeAttempt,
        )

    @Test
    fun `multi turn skips structured phase when tool phase output already conforms`() {
        val check = OutputSchemaCompletionCheck(OutputSchemaValidator())
        val input = inputOf("s-conforming", """{"summary":"ok","confidence":80}""")
        val result = runBlocking { check.check(input) }
        assertEquals(CompletionCheckResult.Done, result)
    }

    @Test
    fun `multi turn accepts fenced json from tool phase`() {
        val check = OutputSchemaCompletionCheck(OutputSchemaValidator())
        val input = inputOf("s-fenced", "```json\n{\"summary\":\"ok\",\"confidence\":80}\n```")
        val result = runBlocking { check.check(input) }
        assertEquals(CompletionCheckResult.Done, result)
    }

    @Test
    fun `multi turn forces structured phase when tool phase output violates schema`() {
        val check = OutputSchemaCompletionCheck(OutputSchemaValidator())
        val input = inputOf("s-missing-field", """{"summary":"ok"}""")
        val result = runBlocking { check.check(input) }
        assertTrue(result is CompletionCheckResult.Continue, "expected the structured output phase to be triggered")
    }

    @Test
    fun `multi turn forces structured phase when tool phase output is prose`() {
        val check = OutputSchemaCompletionCheck(OutputSchemaValidator())
        val input = inputOf("s-prose", "分析完成，总体偏乐观。")
        val result = runBlocking { check.check(input) }
        assertTrue(result is CompletionCheckResult.Continue, "expected the structured output phase to be triggered")
    }

    @Test
    fun `first pass triggers the structured output phase, not a validation retry`() {
        val check = OutputSchemaCompletionCheck(OutputSchemaValidator())
        val context = AgentContext(
            agentId = "test-agent",
            sessionId = "s-two-phase",
            outputSchema = schema,
            outputSchemaMultiTurn = true,
        )
        val prose = "分析完成，总体偏乐观。"
        // Pass 1: prose does not conform -> enter the structured output phase.
        val first = runBlocking {
            check.check(inputWithContext(context, turnId = 0, nudgeAttempt = 0, texts = arrayOf(prose)))
        } as CompletionCheckResult.Continue
        assertTrue(
            first.prompt?.contains("produce your final result") == true,
            "expected the structured-output phase prompt, got: ${first.prompt}"
        )
    }

    @Test
    fun `second pass validates the forced output instead of re-forcing it`() {
        val check = OutputSchemaCompletionCheck(OutputSchemaValidator())
        val context = AgentContext(
            agentId = "test-agent",
            sessionId = "s-two-phase-2",
            outputSchema = schema,
            outputSchemaMultiTurn = true,
        )
        val prose = "分析完成，总体偏乐观。"
        val forced = """{"summary":"ok","confidence":80}"""
        // nudgeAttempt=1 means the structured-output phase was already forced: a conforming
        // output is accepted through normal validation, not through the tool-phase fast path.
        val second = runBlocking {
            check.check(inputWithContext(context, turnId = 1, nudgeAttempt = 1, texts = arrayOf(prose, forced)))
        }
        assertEquals(CompletionCheckResult.Done, second)
        // A non-conforming output in the same phase yields a validation retry rather than a
        // second "produce your final result" prompt.
        val retry = runBlocking {
            check.check(inputWithContext(context, turnId = 1, nudgeAttempt = 1, texts = arrayOf(prose, prose)))
        } as CompletionCheckResult.Continue
        assertTrue(
            retry.prompt?.contains("did not match the required output format") == true,
            "expected a validation retry prompt, got: ${retry.prompt}"
        )
    }

    @Test
    fun `a new run does not inherit the structured phase marker`() {
        val check = OutputSchemaCompletionCheck(OutputSchemaValidator())
        val context = AgentContext(
            agentId = "test-agent",
            sessionId = "s-reset",
            outputSchema = schema,
            outputSchemaMultiTurn = true,
        )
        val prose = "分析完成，总体偏乐观。"
        val conforming = """{"summary":"ok","confidence":80}"""
        // A previous run forced the structured phase...
        val first = runBlocking {
            check.check(inputWithContext(context, turnId = 0, nudgeAttempt = 0, texts = arrayOf(prose)))
        }
        assertTrue(first is CompletionCheckResult.Continue)
        // ...but the next run starts with nudgeAttempt=0 (the ledger is per-run), so the
        // conforming tool-phase output takes the fast path.
        val second = runBlocking {
            check.check(inputWithContext(context, turnId = 0, nudgeAttempt = 0, texts = arrayOf(conforming)))
        }
        assertEquals(CompletionCheckResult.Done, second)
    }

    @Test
    fun `validation gives up once maxRetries is exhausted`() {
        val check = OutputSchemaCompletionCheck(OutputSchemaValidator(), maxRetries = 2)
        val context = AgentContext(
            agentId = "test-agent",
            sessionId = "s-exhausted",
            outputSchema = schema,
            outputSchemaMultiTurn = true,
        )
        val prose = "分析完成，总体偏乐观。"
        val result = runBlocking {
            check.check(inputWithContext(context, turnId = 3, nudgeAttempt = 2, texts = arrayOf(prose)))
        }
        assertEquals(CompletionCheckResult.Done, result)
    }

    @Test
    fun `nudge cap covers the forced phase plus the validation retries`() {
        val check = OutputSchemaCompletionCheck(OutputSchemaValidator(), maxRetries = 2)
        assertEquals(3, check.maxNudges())
    }
}
