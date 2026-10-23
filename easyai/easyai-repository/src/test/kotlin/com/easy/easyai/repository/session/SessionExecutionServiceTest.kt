package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.ChatSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SessionExecutionService].
 * Uses MockK to verify DB interaction patterns and lifecycle guarantees.
 */
class SessionExecutionServiceTest {

    private lateinit var sessionStore: AsyncSessionStore
    private lateinit var service: SessionExecutionService

    @BeforeEach
    fun setup() {
        sessionStore = mockk(relaxed = true)
        // Service is created inside runTest with the test dispatcher injected
        // (see individual test methods)
    }

    private fun createService(scheduler: TestCoroutineScheduler): SessionExecutionService =
        SessionExecutionService(sessionStore, StandardTestDispatcher(scheduler))

    @Nested
    inner class `beginExecution` {

        @Test
        fun `marks streaming and resets endReason`() = runTest {
            service = createService(testScheduler)
            service.beginExecution("s1", "user1")

            // Allow fire-and-forget coroutine to complete
            testScheduler.advanceUntilIdle()

            coVerify { sessionStore.updateStatus("s1", "streaming", "user1") }
            coVerify { sessionStore.saveEndReason("s1", "normal", "user1") }
        }

        @Test
        fun `resetEndReason=false skips saveEndReason`() = runTest {
            service = createService(testScheduler)
            service.beginExecution("s1", "user1", resetEndReason = false)

            testScheduler.advanceUntilIdle()

            coVerify { sessionStore.updateStatus("s1", "streaming", "user1") }
            coVerify(exactly = 0) { sessionStore.saveEndReason(any(), any(), any()) }
        }

        @Test
        fun `registers in local registry`() = runTest {
            service = createService(testScheduler)
            service.beginExecution("s1", "user1")

            assertTrue(service.isLocallyExecuting("s1"))
            assertEquals(1, service.getActiveSessionCount())
        }

        @Test
        fun `overwrite semantics - second begin replaces first`() = runTest {
            service = createService(testScheduler)
            val handle1 = service.beginExecution("s1", "user1")
            val handle2 = service.beginExecution("s1", "user1")

            assertTrue(service.isLocallyExecuting("s1"))
            assertEquals(1, service.getActiveSessionCount())
            // handle1 is no longer registered
            assertFalse(handle1 === handle2)
        }

        @Test
        fun `beginExecutionIfIdle refuses a second registration without touching the first`() = runTest {
            service = createService(testScheduler)
            val first = service.beginExecutionIfIdle("s1", "user1")
            val second = service.beginExecutionIfIdle("s1", "user1")

            assertTrue(first != null)
            assertNull(second)
            assertTrue(service.isLocallyExecuting("s1"))
            testScheduler.advanceUntilIdle()
            coVerify(exactly = 1) { sessionStore.updateStatus("s1", "streaming", "user1") }

            // The original handle still owns the session and can end it.
            service.endExecution(first!!)
            testScheduler.advanceUntilIdle()
            assertFalse(service.isLocallyExecuting("s1"))
            coVerify(exactly = 1) { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
        }
    }

    @Nested
    inner class `endExecution` {

        @Test
        fun `saves endReason and marks active in order`() = runTest {
            service = createService(testScheduler)
            val handle = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            service.endExecution(handle, "max_iterations")
            testScheduler.advanceUntilIdle()

            coVerify(ordering = io.mockk.Ordering.ORDERED) {
                sessionStore.saveEndReason("s1", "max_iterations", "user1")
                sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming")
            }
            assertFalse(service.isLocallyExecuting("s1"))
        }

        @Test
        fun `null endReason skips saveEndReason`() = runTest {
            service = createService(testScheduler)
            val handle = service.beginExecution("s1", "user1", resetEndReason = false)
            testScheduler.advanceUntilIdle()

            service.endExecution(handle, endReason = null)
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { sessionStore.saveEndReason(any(), any(), any()) }
            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
        }

        @Test
        fun `no-op when handle already removed by cancel`() = runTest {
            service = createService(testScheduler)
            val handle = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            // Cancel removes the handle first
            service.cancelExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            // Clear verification state
            io.mockk.clearMocks(sessionStore, answers = false)
            coEvery { sessionStore.updateStatus(any(), any(), any(), any()) } returns Unit
            coEvery { sessionStore.saveEndReason(any(), any(), any()) } returns Unit

            // endExecution should be no-op
            service.endExecution(handle, "normal")
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { sessionStore.saveEndReason(any(), any(), any()) }
            coVerify(exactly = 0) { sessionStore.updateStatus(any(), any(), any(), any()) }
        }

        @Test
        fun `stale handle after re-register is no-op`() = runTest {
            service = createService(testScheduler)
            val handle1 = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            // Re-register (SSE reconnect)
            val handle2 = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            io.mockk.clearMocks(sessionStore, answers = false)
            coEvery { sessionStore.updateStatus(any(), any(), any(), any()) } returns Unit
            coEvery { sessionStore.saveEndReason(any(), any(), any()) } returns Unit

            // Old handle's endExecution is no-op
            service.endExecution(handle1, "normal")
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { sessionStore.updateStatus(any(), any(), any(), any()) }

            // New handle's endExecution works normally
            service.endExecution(handle2, "normal")
            testScheduler.advanceUntilIdle()

            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
        }
    }

    @Nested
    inner class `cancelExecution` {

        @Test
        fun `aborts session and persists cancelled state`() = runTest {
            service = createService(testScheduler)
            val session = mockk<ChatSession>(relaxed = true)
            service.beginExecution("s1", "user1", session = session)
            testScheduler.advanceUntilIdle()

            val verdict = service.cancelExecution("s1", "user1")
            assertTrue(verdict.aborted)

            verify { session.abort() }
            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
            coVerify { sessionStore.saveEndReason("s1", "cancelled", "user1") }
            assertFalse(service.isLocallyExecuting("s1"))
            // A confirmed stop proves itself, so the row state is not read on this path.
            coVerify(exactly = 0) { sessionStore.findStatus(any(), any()) }
        }

        @Test
        fun `sessionless run with an abort hook is a confirmed cancel`() = runTest {
            service = createService(testScheduler)
            var aborted = false
            service.beginExecution("s1", "user1", session = null, onCancel = { aborted = true })
            testScheduler.advanceUntilIdle()

            // Manual compaction registers without a ChatSession; the hook is what makes "cancelled"
            // true — without it the client would be told the run stopped while the job ran on.
            assertTrue(service.cancelExecution("s1", "user1").aborted)
            assertTrue(aborted)

            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
            coVerify { sessionStore.saveEndReason("s1", "cancelled", "user1") }
        }

        @Test
        fun `sessionless run without an abort hook reports unconfirmed but still updates DB`() = runTest {
            service = createService(testScheduler)
            coEvery { sessionStore.findStatus("s1", "user1") } returns "streaming"
            service.beginExecution("s1", "user1", session = null)
            testScheduler.advanceUntilIdle()

            val verdict = service.cancelExecution("s1", "user1")
            assertFalse(verdict.aborted)
            // The row said streaming before this call downgraded it: something was running here that
            // could not be stopped, which is what the client warns about.
            assertTrue(verdict.rowWasStreaming)

            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
            coVerify { sessionStore.saveEndReason("s1", "cancelled", "user1") }
        }

        @Test
        fun `unknown session - attempts DB update for cross-instance semantics`() = runTest {
            service = createService(testScheduler)

            assertFalse(service.cancelExecution("unknown", "user1").aborted)

            coVerify { sessionStore.updateStatus("unknown", "active", "user1", expectedStatus = "streaming") }
            coVerify { sessionStore.saveEndReason("unknown", "cancelled", "user1") }
        }

        @Test
        fun `cancel waits for the admission's streaming write before downgrading`() = runBlocking {
            val streamingStarted = CountDownLatch(1)
            val releaseStreaming = CountDownLatch(1)
            coEvery { sessionStore.updateStatus("s1", "streaming", "user1") } coAnswers {
                streamingStarted.countDown()
                releaseStreaming.await()
                Unit
            }
            val threaded = SessionExecutionService(sessionStore, Dispatchers.IO)

            val handle = threaded.beginExecutionIfIdle("s1", "user1", session = null, onCancel = {})
            assertNotNull(handle)
            assertTrue(streamingStarted.await(5, TimeUnit.SECONDS))

            val cancel = async(Dispatchers.IO) { threaded.cancelExecution("s1", "user1") }
            // Let cancel reach the join while the streaming write is still blocked. It must not
            // downgrade the row yet; otherwise the late streaming write would leave it stuck.
            delay(100)
            releaseStreaming.countDown()
            assertTrue(cancel.await().aborted)
            threaded.destroy()

            coVerify(ordering = io.mockk.Ordering.ORDERED) {
                sessionStore.updateStatus("s1", "streaming", "user1")
                sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming")
            }
        }

        @Test
        fun `second cancel of the same session reports no local run`() = runTest {
            service = createService(testScheduler)
            val session = mockk<ChatSession>(relaxed = true)
            service.beginExecution("s1", "user1", session = session)
            testScheduler.advanceUntilIdle()

            assertTrue(service.cancelExecution("s1", "user1").aborted)
            // The run is gone, so a repeat cancel cannot claim to have stopped anything — this is
            // the signal the client uses instead of showing "已结束" unconditionally.
            assertFalse(service.cancelExecution("s1", "user1").aborted)
            verify(exactly = 1) { session.abort() }
        }

        @Test
        fun `row state is read before the downgrade, not after`() = runTest {
            service = createService(testScheduler)
            var downgraded = false
            coEvery { sessionStore.findStatus("s1", "user1") } answers {
                if (downgraded) "active" else "streaming"
            }
            coEvery { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") } answers {
                downgraded = true
                Unit
            }

            // Reads the row before writing it: a verdict computed afterwards would always say false,
            // which is the exact blindness the client used to hit.
            assertTrue(service.cancelExecution("s1", "user1").rowWasStreaming)
            assertTrue(downgraded)
        }

        @Test
        fun `idle row means nothing was running, so no false warning`() = runTest {
            service = createService(testScheduler)
            coEvery { sessionStore.findStatus("s1", "user1") } returns "active"

            val verdict = service.cancelExecution("s1", "user1")
            assertFalse(verdict.aborted)
            assertFalse(verdict.rowWasStreaming)
        }

        @Test
        fun `unreadable row state degrades to no evidence and still downgrades`() = runTest {
            service = createService(testScheduler)
            coEvery { sessionStore.findStatus(any(), any()) } throws IllegalStateException("db down")

            val verdict = service.cancelExecution("s1", "user1")
            assertFalse(verdict.aborted)
            assertFalse(verdict.rowWasStreaming)

            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
        }
    }

    @Nested
    inner class `query methods` {

        @Test
        fun `isLocallyExecuting returns correct state`() = runTest {
            service = createService(testScheduler)
            assertFalse(service.isLocallyExecuting("s1"))

            val handle = service.beginExecution("s1", "user1")
            assertTrue(service.isLocallyExecuting("s1"))

            service.endExecution(handle)
            assertFalse(service.isLocallyExecuting("s1"))
        }

        @Test
        fun `getActiveSession returns session or null`() = runTest {
            service = createService(testScheduler)
            val session = mockk<ChatSession>(relaxed = true)
            service.beginExecution("s1", "user1", session = session)

            assertEquals(session, service.getActiveSession("s1"))
            assertNull(service.getActiveSession("unknown"))
        }

        @Test
        fun `getActiveSessionCount tracks registrations`() = runTest {
            service = createService(testScheduler)
            assertEquals(0, service.getActiveSessionCount())

            val h1 = service.beginExecution("s1", "user1")
            assertEquals(1, service.getActiveSessionCount())

            service.beginExecution("s2", "user2")
            assertEquals(2, service.getActiveSessionCount())

            service.endExecution(h1)
            assertEquals(1, service.getActiveSessionCount())
        }
    }

    @Nested
    inner class `destroy` {

        @Test
        fun `clears registry and resets DB status`() = runTest {
            service = createService(testScheduler)
            service.beginExecution("s1", "user1")
            service.beginExecution("s2", "user2")
            testScheduler.advanceUntilIdle()

            service.destroy()

            assertEquals(0, service.getActiveSessionCount())
            assertFalse(service.isLocallyExecuting("s1"))
            assertFalse(service.isLocallyExecuting("s2"))
        }
    }

    @Nested
    inner class `null sessionStore` {

        @Test
        fun `works without DB - registry only`() = runTest {
            val noDbService = SessionExecutionService(null, StandardTestDispatcher(testScheduler))

            val handle = noDbService.beginExecution("s1", "user1")
            assertTrue(noDbService.isLocallyExecuting("s1"))

            noDbService.endExecution(handle, "normal")
            assertFalse(noDbService.isLocallyExecuting("s1"))
        }
    }

    @Nested
    inner class `SessionCompletedEvent publishing` {

        private lateinit var publisher: ApplicationEventPublisher

        @BeforeEach
        fun setupPublisher() {
            publisher = mockk(relaxed = true)
        }

        @Test
        fun `endExecution with endReason publishes event with correct fields`() = runTest {
            service = createService(testScheduler)
            service.setApplicationEventPublisher(publisher)
            val handle = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            service.endExecution(handle, "normal")
            testScheduler.advanceUntilIdle()

            val eventSlot = slot<SessionCompletedEvent>()
            verify(exactly = 1) { publisher.publishEvent(capture(eventSlot)) }
            assertEquals("s1", eventSlot.captured.sessionId)
            assertEquals("user1", eventSlot.captured.userId)
            assertEquals("normal", eventSlot.captured.endReason)
        }

        @Test
        fun `endExecution with null endReason does not publish event`() = runTest {
            service = createService(testScheduler)
            service.setApplicationEventPublisher(publisher)
            val handle = service.beginExecution("s1", "user1", resetEndReason = false)
            testScheduler.advanceUntilIdle()

            service.endExecution(handle, endReason = null)
            testScheduler.advanceUntilIdle()

            verify(exactly = 0) { publisher.publishEvent(any()) }
        }

        @Test
        fun `no-op endExecution after cancel does not publish event`() = runTest {
            service = createService(testScheduler)
            service.setApplicationEventPublisher(publisher)
            val handle = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            service.cancelExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            service.endExecution(handle, "normal")
            testScheduler.advanceUntilIdle()

            verify(exactly = 0) { publisher.publishEvent(any()) }
        }

        @Test
        fun `null publisher does not throw`() = runTest {
            service = createService(testScheduler)
            // publisher not injected — remains null
            val handle = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            service.endExecution(handle, "normal")
            testScheduler.advanceUntilIdle()

            // No exception, DB writes still happen
            coVerify { sessionStore.saveEndReason("s1", "normal", "user1") }
            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
        }

        @Test
        fun `event published after DB writes`() = runTest {
            service = createService(testScheduler)
            service.setApplicationEventPublisher(publisher)
            val handle = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            service.endExecution(handle, "max_iterations")
            testScheduler.advanceUntilIdle()

            coVerify(ordering = io.mockk.Ordering.ORDERED) {
                sessionStore.saveEndReason("s1", "max_iterations", "user1")
                sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming")
            }
            verify(exactly = 1) { publisher.publishEvent(any()) }
        }

        @Test
        fun `publishes event even without sessionStore`() = runTest {
            val noDbService = SessionExecutionService(null, StandardTestDispatcher(testScheduler))
            noDbService.setApplicationEventPublisher(publisher)

            val handle = noDbService.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            noDbService.endExecution(handle, "normal")
            testScheduler.advanceUntilIdle()

            val eventSlot = slot<SessionCompletedEvent>()
            verify(exactly = 1) { publisher.publishEvent(capture(eventSlot)) }
            assertEquals("s1", eventSlot.captured.sessionId)
            assertEquals("user1", eventSlot.captured.userId)
            assertEquals("normal", eventSlot.captured.endReason)
        }
    }
}
