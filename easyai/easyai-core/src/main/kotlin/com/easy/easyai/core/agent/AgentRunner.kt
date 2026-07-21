package com.easy.easyai.core.agent

import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.ErrorMessage
import com.easy.easyai.core.util.isRetryableError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory

/**
 * Short-lived execution engine for a single agent run.
 *
 * Created per prompt() call — holds no state across runs.
 * All mutable state (messages, abort control) is passed in from the caller
 * (typically [ChatSession]).
 *
 * Replaces the execution logic previously embedded in [Agent].
 */
class AgentRunner(
    private val agent: Agent,
    private val messages: MutableList<EasyAiMessage>,
    private val steeringQueue: PendingMessageQueue = PendingMessageQueue(),
    private val followUpQueue: PendingMessageQueue = PendingMessageQueue(),
    private val abortSignal: () -> Boolean = { false },
    private val registerJob: (Job?) -> Unit = {}
) {

    private val context: AgentContext get() = agent.context
    private val services: AgentService get() = agent.services

    private val logger = LoggerFactory.getLogger(javaClass)
    private val logPrefix = agentLogPrefix(context.parentAgentId)

    /**
     * Execute the agent loop with new messages appended to the transcript.
     */
    fun prompt(newMessages: List<EasyAiMessage>): EventStream<AgentEvent, List<AssistantMessage>> {
        return runWithLifecycle(newMessages)
    }

    /**
     * Continue the conversation without adding new messages.
     * Used to resume after pending tool calls or other interruptions.
     */
    fun continueConversation(): EventStream<AgentEvent, List<AssistantMessage>> {
        return runWithLifecycle(emptyList())
    }

    private fun runWithLifecycle(
        initialMessages: List<EasyAiMessage>
    ): EventStream<AgentEvent, List<AssistantMessage>> {
        return EventStream.create {
            registerJob(coroutineContext[Job]) // Let caller track the running job for abort/cancel
            runAgentLoop(initialMessages)
        }
    }

    private suspend fun ProducerScope<AgentEvent, List<AssistantMessage>>.runAgentLoop(
        initialMessages: List<EasyAiMessage>
    ) {
        val allListeners = services.eventListeners

        suspend fun pushAndNotify(event: AgentEvent) {
            push(event)
            allListeners.forEach { listener ->
                try {
                    listener.handle(context, event, ::push)
                } catch (e: Exception) {
                    logger.warn("${logPrefix}Listener {} failed on event {}", listener::class.simpleName, event::class.simpleName, e)
                }
            }
        }

        pushAndNotify(AgentStartEvent(context.sessionId ?: "default"))

        // Merge initialMessages into transcript and persist them
        messages.addAll(initialMessages)
        if (initialMessages.isNotEmpty()) {
            services.messageListener?.onMessageAdded(initialMessages)
        }

        val loop = createAgentLoop()
        val innerStream = loop.run(messages)
        try {
            // Forward events from inner EventStream to outer EventStream
            innerStream.asFlow().collect { innerEvent ->
                pushAndNotify(innerEvent)
            }
            val resultMessages = innerStream.result()
            pushAndNotify(AgentEndEvent(
                sessionId = context.sessionId ?: "default",
                reason = "completed",
                messages = messages.toList(),
                endReason = loop.endReason
            ))
            end(resultMessages)
        } catch (_: CancellationException) {
            // Cancel the inner EventStream's independent coroutine to prevent orphaned execution
            // (inner stream runs on SupervisorJob() which is NOT a child of the outer job)
            innerStream.cancel()
            pushAndNotify(AgentEndEvent(
                sessionId = context.sessionId ?: "default",
                reason = "aborted",
                messages = messages.toList(),
                endReason = "cancelled"
            ))
            end(emptyList())
        } catch (e: Exception) {
            innerStream.cancel()
            // Create error message for persistence
            val errorMessage = ErrorMessage(
                error = e.message ?: "Unknown error",
                isRetryable = isRetryableError(e)
            )
            messages.add(errorMessage)
            services.messageListener?.onMessageAdded(listOf(errorMessage))

            pushAndNotify(ErrorEvent(
                error = e,
                sessionId = context.sessionId ?: "default",
                isRetryable = errorMessage.isRetryable,
                messageId = errorMessage.id
            ))
            pushAndNotify(AgentEndEvent(
                sessionId = context.sessionId ?: "default",
                reason = "error: ${e.message}",
                messages = messages.toList(),
                endReason = "error"
            ))
            end(emptyList())
        }
    }

    private fun createAgentLoop(): AgentLoop {
        return AgentLoop(
            context = context,
            config = AgentLoopConfig(
                getSteeringMessages = { steeringQueue.poll(PendingMessageQueue.Mode.ONE_AT_A_TIME) },
                getFollowUpMessages = { followUpQueue.poll(PendingMessageQueue.Mode.ONE_AT_A_TIME) },
                isAbortRequested = abortSignal
            ),
            services = services,
            tools = context.tools,
            chatModel = agent.chatModel
        )
    }
}
