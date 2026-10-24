package com.easy.easyai.tools

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.media.MediaProviderResolver
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.tools.media.FetchMediaToolBuilder
import com.easy.easyai.tools.media.ImageGenToolBuilder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class SpringToolFactoryTest {
    private val context = AgentContext(agentId = "agent", userId = "alice", owners = setOf("alice", "group"))
    private val agentService = mockk<AgentService>()
    private val tool = mockk<ToolDefinition>()

    @Nested
    inner class Creation {
        private val first = mockk<ToolBuilder> { every { name } returns "first" }
        private val second = mockk<ToolBuilder> { every { name } returns "second" }
        private val factory = SpringToolFactory(listOf(first, second))

        @Test
        fun `filters builders before invoking suspend build`() = runTest {
            coEvery { first.build(context, agentService) } coAnswers {
                delay(1)
                tool
            }

            assertEquals(listOf(tool), factory.createTools(context, agentService, listOf("first")))
            coVerify(exactly = 0) { second.build(any(), any()) }
        }

        @Test
        fun `skips unavailable builders`() = runTest {
            coEvery { first.build(context, agentService) } returns null
            coEvery { second.build(context, agentService) } returns tool

            assertEquals(listOf(tool), factory.createTools(context, agentService))
        }

        @Test
        fun `ordinary builder failures do not prevent other tools`() = runTest {
            coEvery { first.build(context, agentService) } throws IllegalStateException("unavailable")
            coEvery { second.build(context, agentService) } returns tool

            assertEquals(listOf(tool), factory.createTools(context, agentService))
        }

        @Test
        fun `cancellation is rethrown without building more tools`() = runTest {
            val cancellation = CancellationException("cancel build")
            coEvery { first.build(context, agentService) } throws cancellation

            assertSame(cancellation, assertFailsWith<CancellationException> {
                factory.createTools(context, agentService)
            })
            coVerify(exactly = 0) { second.build(any(), any()) }
        }
    }

    @Nested
    inner class MediaBuilders {
        private val storage = mockk<ObjectStorage>()
        private val storageResolver = mockk<ObjectStorageResolver>()
        private val mediaResolver = mockk<MediaProviderResolver>()
        private val imageBuilder = ImageGenToolBuilder()
        private val fetchBuilder = FetchMediaToolBuilder()
        private val factory = SpringToolFactory(listOf(imageBuilder, fetchBuilder))

        init {
            every { agentService.objectStorageResolver } returns storageResolver
            every { agentService.mediaProviderResolver } returns mediaResolver
        }

        @Test
        fun `media resolvers suspend in the caller context`() = runTest(CoroutineName("media")) {
            val callerContext = currentCoroutineContext()
            coEvery { storageResolver.resolve(context.effectiveOwners) } coAnswers {
                assertEquals(callerContext, currentCoroutineContext())
                delay(1)
                assertEquals(callerContext, currentCoroutineContext())
                storage
            }
            coEvery { mediaResolver.resolveEntries(context.effectiveOwners, "image") } coAnswers {
                assertEquals(callerContext, currentCoroutineContext())
                delay(1)
                assertEquals(callerContext, currentCoroutineContext())
                listOf(MediaProviderSettings(enabled = true))
            }

            assertEquals(
                listOf(imageBuilder.name, fetchBuilder.name),
                factory.createTools(context, agentService).map { it.name }
            )
            coVerify(exactly = 2) { storageResolver.resolve(context.effectiveOwners) }
            coVerify(exactly = 1) { mediaResolver.resolveEntries(context.effectiveOwners, "image") }
        }

        @Test
        fun `missing storage hides media tools without querying models`() = runTest {
            coEvery { storageResolver.resolve(context.effectiveOwners) } returns null

            assertTrue(factory.createTools(context, agentService).isEmpty())
            coVerify(exactly = 0) { mediaResolver.resolveEntries(any<Collection<String>>(), any()) }
        }

        @Test
        fun `missing generation models still permits fetching media`() = runTest {
            coEvery { storageResolver.resolve(context.effectiveOwners) } returns storage
            coEvery { mediaResolver.resolveEntries(context.effectiveOwners, "image") } returns emptyList()

            assertEquals(listOf(fetchBuilder.name), factory.createTools(context, agentService).map { it.name })
        }

        @Test
        fun `cancelling creation cancels the media query without building subsequent tools`() = runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            coEvery { storageResolver.resolve(context.effectiveOwners) } returns storage
            coEvery { mediaResolver.resolveEntries(context.effectiveOwners, "image") } coAnswers {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val creation = async { factory.createTools(context, agentService) }
            started.await()
            creation.cancelAndJoin()

            assertTrue(cancelled.isCompleted)
            coVerify(exactly = 1) { storageResolver.resolve(context.effectiveOwners) }
        }
    }
}
