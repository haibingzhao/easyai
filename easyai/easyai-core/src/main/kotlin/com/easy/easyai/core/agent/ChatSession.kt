package com.easy.easyai.core.agent

import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.model.*
import kotlinx.coroutines.Job
import org.springframework.ai.chat.model.ChatModel

/**
 * Manages a conversation session.
 *
 * Session state holder:
 * - Message history is passed from DB on each prompt (stateless regarding messages)
 * - Holds runtime state: steering/followUp queues, abort control
 *
 * Creates [AgentRunner] per prompt execution — Agent is now a pure data class.
 */
class ChatSession(
    val id: String,
    private var agent: Agent
) {
    val agentContext: AgentContext get() = agent.context

    /**
     * Get the ChatModel instance for this session.
     * Used by compaction and other components that need session-specific LLM access.
     */
    fun getChatModel(): ChatModel = agent.chatModel

    @Volatile
    private var _status: SessionStatus = SessionStatus.ACTIVE
    val status: SessionStatus get() = _status

    // Session-level runtime state (moved from Agent)
    private val steeringQueue = PendingMessageQueue()
    private val followUpQueue = PendingMessageQueue()
    @Volatile
    private var abortRequested = false
    private var currentRunJob: Job? = null

    /** End reason from the last agent execution (e.g. "normal", "max_iterations"). */
    @Volatile
    var lastEndReason: String = "normal"

    /**
     * Add a steering message to be injected at the next agent loop iteration.
     */
    fun steer(message: String) {
        steer(UserMessage(message))
    }

    /**
     * Add a steering message (with content blocks) to be injected at the next agent loop iteration.
     */
    fun steer(userMessage: UserMessage) {
        steeringQueue.enqueueWithType(userMessage, "steer")
    }

    /**
     * Add a steering message and return the generated queue ID.
     */
    fun steerWithId(message: String): String = steerWithId(UserMessage(message))

    /**
     * Add a steering message (with content blocks) and return the generated queue ID.
     */
    fun steerWithId(userMessage: UserMessage): String {
        return steeringQueue.enqueueWithType(userMessage, "steer")
    }

    /**
     * Add a follow-up message to be processed after the current agent response.
     */
    fun followUp(message: String) {
        followUp(UserMessage(message))
    }

    /**
     * Add a follow-up message (with content blocks) to be processed after the current agent response.
     */
    fun followUp(userMessage: UserMessage) {
        followUpQueue.enqueueWithType(userMessage, "followUp")
    }

    /**
     * Add a follow-up message and return the generated queue ID.
     */
    fun followUpWithId(message: String): String = followUpWithId(UserMessage(message))

    /**
     * Add a follow-up message (with content blocks) and return the generated queue ID.
     */
    fun followUpWithId(userMessage: UserMessage): String {
        return followUpQueue.enqueueWithType(userMessage, "followUp")
    }

    /**
     * Remove a queued message by ID from either queue.
     * Returns true if the message was found and removed.
     */
    fun removeQueuedMessage(id: String): Boolean {
        return steeringQueue.remove(id) || followUpQueue.remove(id)
    }

    /**
     * Update the content of a queued message by ID.
     * Returns true if the message was found and updated.
     */
    fun updateQueuedMessage(id: String, newContent: String): Boolean {
        return steeringQueue.update(id, newContent) || followUpQueue.update(id, newContent)
    }

    /**
     * Reorder queued messages by the given ID list.
     * IDs are matched against the combined steering + followUp queues.
     */
    fun reorderQueuedMessages(ids: List<String>) {
        // Reorder is only meaningful within the followUp queue (steering is consumed first anyway).
        // However, we apply it to both for correctness.
        steeringQueue.reorder(ids)
        followUpQueue.reorder(ids)
    }

    /**
     * Return a snapshot of all queued messages (steering + followUp combined).
     * Steering messages appear first (matching consumption order).
     */
    fun getQueuedMessages(): List<QueuedMessageInfo> {
        return steeringQueue.peekAll() + followUpQueue.peekAll()
    }

    /**
     * Send a user message with the given conversation history.
     * Appends the user message to history before calling the agent.
     */
    fun prompt(message: String, history: List<EasyAiMessage>): EventStream<AgentEvent, List<AssistantMessage>> {
        val userMessage = UserMessage(message)
        val contextMessages = history + userMessage
        return createRunner(contextMessages)
    }

    /**
     * Prompt with the full session history (used for retry).
     * The user message is NOT added - this replays with existing history.
     */
    fun promptWithHistory(messages: List<EasyAiMessage>): EventStream<AgentEvent, List<AssistantMessage>> {
        return createRunner(messages)
    }

    /**
     * Abort the current agent execution.
     * Sets session status to CANCELLED, triggers graceful abort,
     * and clears any pending steering/followUp queued messages.
     */
    fun abort() {
        _status = SessionStatus.CANCELLED
        abortRequested = true
        currentRunJob?.cancel()
        // Clear pending queued messages — they will never be consumed
        // after abort and would otherwise linger as orphaned state.
        steeringQueue.poll(PendingMessageQueue.Mode.ALL)
        followUpQueue.poll(PendingMessageQueue.Mode.ALL)
    }

    /**
     * Resume a cancelled or errored session.
     * Optionally adds a user message before resuming.
     *
     * Logic:
     * - If user provides a message: directly add UserMessage → LLM will decide based on new instruction
     * - If no user message: detect interrupted context and inject resumption guidance,
     *   and AgentLoop will execute pending toolCalls if needed
     */
    fun resume(message: String? = null, messages: List<EasyAiMessage>): EventStream<AgentEvent, List<AssistantMessage>> {
        _status = SessionStatus.ACTIVE

        val workingMessages = messages.toMutableList()

        if (!message.isNullOrBlank()) {
            // User provided a clear instruction — just add the message
            workingMessages.add(UserMessage(message))
        } else {
            // No user message — detect interrupted context and inject resumption guidance
            val lastMessage = workingMessages.lastOrNull()
            val hasInterruptedContext = when {
                lastMessage is AssistantMessage && lastMessage.stopReason == StopReason.ABORTED -> true
                lastMessage?.role == Role.TOOL -> true
                else -> false
            }

            if (hasInterruptedContext) {
                workingMessages.add(UserMessage("The previous response was interrupted. Please continue from where you left off or re-evaluate your approach."))
            } else if (lastEndReason == "max_iterations") {
                lastEndReason = "normal" // consume the reason to avoid re-triggering on subsequent resumes
                workingMessages.add(UserMessage("[System: The previous execution reached the maximum iteration limit. Please continue the task efficiently and aim to complete it within this new execution cycle.]"))
            }
        }

        return createRunner(workingMessages.toList())
    }

    /**
     * Reset session state (steering/followUp queues).
     */
    fun reset() {
        steeringQueue.poll(PendingMessageQueue.Mode.ALL)
        followUpQueue.poll(PendingMessageQueue.Mode.ALL)
    }

    /**
     * Update input variables in the agent context without recreating the agent.
     * Used to inject fresh inputData from API requests into cached sessions.
     */
    fun updateInputVariables(inputVariables: Map<String, Any?>) {
        if (inputVariables.isNotEmpty()) {
            agent = agent.copy(context = agent.context.copy(inputVariables = inputVariables))
        }
    }

    /**
     * Create an AgentRunner for this prompt execution.
     * Registers job for abort/cancel support without resetting abort flag.
     */
    private fun createRunner(newMessages: List<EasyAiMessage>): EventStream<AgentEvent, List<AssistantMessage>> {
        val transcript = newMessages.toMutableList()
        // Inject abort signal into AgentContext so sub-agents can inherit it
        val agentWithAbortSignal = agent.copy(context = agent.context.copy(abortSignal = { abortRequested }))
        val runner = AgentRunner(
            agent = agentWithAbortSignal,
            messages = transcript,
            steeringQueue = steeringQueue,
            followUpQueue = followUpQueue,
            abortSignal = { abortRequested },
            registerJob = { job ->
                // Register job and check if abort was requested while starting
                currentRunJob = job
                if (abortRequested) {
                    // Abort was requested before job was registered — cancel immediately
                    job?.cancel()
                }
            }
        )
        // Reset abort flag before starting execution (synchronous, before coroutine starts)
        abortRequested = false
        return runner.prompt(emptyList()) // Messages already in transcript
    }
}