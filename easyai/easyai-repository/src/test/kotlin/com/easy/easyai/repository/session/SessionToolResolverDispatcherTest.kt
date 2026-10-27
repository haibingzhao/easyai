package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class SessionToolResolverDispatcherTest {
    private val tool = mockk<ToolDefinition> {
        every { name } returns "tool-a"
        every { alwaysInclude } returns false
    }
    private val toolFactory = mockk<ToolFactory>()
    private val resolver = SessionToolResolver(
        projectStore = null,
        toolFactory = toolFactory,
        agentService = mockk<AgentService>(relaxed = true)
    )
    private val context = AgentContext(agentId = "agent-1", sessionId = "session-1", userId = "alice")
    private val agentDef = AgentDefinition.create(
        id = "agent-1", name = "Agent", toolNames = listOf(tool.name), instructionsEnabled = false
    )

    init {
        // Catalog lookup behind the configured-but-unbuilt warning; empty keeps the path quiet.
        every { toolFactory.getBuilders() } returns emptyList()
    }

    @Nested
    inner class `Configured but unbuilt tools` {
        @Test
        fun `a whitelisted name the catalog offers but no builder produces is not returned`() = runTest {
            val factory = mockk<ToolFactory>()
            val builder = mockk<ToolBuilder> { every { name } returns "watch" }
            every { factory.getBuilders() } returns listOf(builder)
            coEvery { factory.createTools(any(), any(), any()) } returns emptyList()
            val isolated = SessionToolResolver(
                projectStore = null,
                toolFactory = factory,
                agentService = mockk<AgentService>(relaxed = true)
            )

            assertTrue(
                isolated.resolveToolsForAgent(agentDef.copy(toolNames = listOf("watch")), context).isEmpty()
            )
        }
    }

    @Nested
    inner class Suspension {
        @Test
        fun `session tools suspend in the caller context`() = runTest(CoroutineName("session")) {
            val callerContext = currentCoroutineContext()
            coEvery { toolFactory.createTools(any(), any(), any()) } coAnswers {
                assertEquals(callerContext, currentCoroutineContext())
                delay(1)
                assertEquals(callerContext, currentCoroutineContext())
                listOf(tool)
            }

            assertEquals(listOf(tool), resolver.createSessionTools(context))
        }

        @Test
        fun `agent tools suspend before filtering in the caller context`() = runTest(CoroutineName("agent")) {
            val callerContext = currentCoroutineContext()
            coEvery { toolFactory.createTools(any(), any(), any()) } coAnswers {
                assertEquals(callerContext, currentCoroutineContext())
                delay(1)
                assertEquals(callerContext, currentCoroutineContext())
                listOf(tool)
            }

            assertSame(tool, resolver.resolveToolsForAgent(agentDef, context).single())
            assertTrue(resolver.resolveToolsForAgent(agentDef.copy(toolNames = emptyList()), context).isEmpty())
            every { tool.alwaysInclude } returns true
            assertSame(tool, resolver.resolveToolsForAgent(agentDef.copy(toolNames = emptyList()), context).single())
        }
    }

    @Nested
    inner class Cancellation {
        @Test
        fun `cancelling session creation cancels suspended factory`() = runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            coEvery { toolFactory.createTools(any(), any(), any()) } coAnswers {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val creation = async { resolver.createSessionTools(context) }
            started.await()
            assertFalse(creation.isCompleted)
            creation.cancelAndJoin()

            assertTrue(cancelled.isCompleted)
            coVerify(exactly = 1) { toolFactory.createTools(context, any(), any()) }
        }

        @Test
        fun `cancelling agent creation cancels suspended factory`() = runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            coEvery { toolFactory.createTools(any(), any(), any()) } coAnswers {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val creation = async { resolver.resolveToolsForAgent(agentDef, context) }
            started.await()
            creation.cancelAndJoin()

            assertTrue(cancelled.isCompleted)
        }
    }
}
