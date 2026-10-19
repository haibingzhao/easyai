package com.easy.easyai.core.agent

import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.model.*
import com.easy.easyai.api.llm.ChatModel
import kotlinx.coroutines.Job

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

    // Written on the producer coroutine (Dispatchers.Default), read by abort() on whatever thread
    // handles the cancel request — needs the same visibility guarantee as abortRequested above.
    @Volatile
    private var currentRunJob: Job? = null

    /** End reason from the last agent execution (e.g. "normal", "max_iterations"). */
    @Volatile
    var lastEndReason: String = "normal"

    /** Optional callback invoked when abort() is called. Used for cleanup of external resources. */
    var onAbortCallback: (() -> Unit)? = null

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

    /** Return the current editable snapshot from either queue, without consuming it. */
    fun getQueuedMessage(id: String): UserMessage? {
        return steeringQueue.get(id) ?: followUpQueue.get(id)
    }

    /**
     * Atomically replace a previously read snapshot with an upstream-prepared user message.
     * Returns false after consumption or any concurrent edit; never re-enqueues a message.
     */
    fun updateQueuedMessage(id: String, expectedSnapshot: UserMessage, replacement: UserMessage): Boolean {
        return steeringQueue.updateMessage(id, expectedSnapshot, replacement) ||
            followUpQueue.updateMessage(id, expectedSnapshot, replacement)
    }

    /**
     * Update text, invalidating command metadata. Command edits must use the snapshot overload.
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
        // Invoke cleanup callback (e.g., cancel background tasks)
        onAbortCallback?.invoke()
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
                workingMessages.add(
                    UserMessage(
                        content = listOf(TextContent("The previous response was interrupted. Please continue from where you left off or re-evaluate your approach.")),
                        metadata = mapOf(UserMessage.SYSTEM_ORIGIN_KEY to UserMessage.ORIGIN_RESUME_GUIDANCE)
                    )
                )
            } else if (lastEndReason == "max_iterations") {
                lastEndReason = "normal" // consume the reason to avoid re-triggering on subsequent resumes
                workingMessages.add(
                    UserMessage(
                        content = listOf(TextContent("[System: The previous execution reached the maximum iteration limit. Please continue the task efficiently and aim to complete it within this new execution cycle.]")),
                        metadata = mapOf(UserMessage.SYSTEM_ORIGIN_KEY to UserMessage.ORIGIN_RESUME_GUIDANCE)
                    )
                )
            } else if (lastEndReason == AgentCompletionCheck.END_REASON_STALLED) {
                lastEndReason = "normal" // consume the reason to avoid re-triggering on subsequent resumes
                workingMessages.add(
                    UserMessage(
                        // Which check gave up is in the transcript (its own notice message), so the
                        // guidance stays generic — naming todos here would mislead a run that stalled
                        // on an output schema.
                        content = listOf(TextContent("[System: The previous execution stopped because a completion check ran out of reminders without making progress. Do not answer as if the task were finished: take a different approach, or ask the user what to do next.]")),
                        metadata = mapOf(UserMessage.SYSTEM_ORIGIN_KEY to UserMessage.ORIGIN_RESUME_GUIDANCE)
                    )
                )
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
     * Override the skills list rendered into this turn's system prompt (decision-model routing).
     * Sessions are rebuilt per user message, so the override is naturally turn-scoped.
     */
    fun updateTurnSkills(skills: List<Map<String, Any?>>) {
        if (skills.isNotEmpty()) {
            agent = agent.copy(context = agent.context.copy(skills = skills))
        }
    }

    /**
     * Replace this turn's output contract: the instructions, the JSON schema the answer must take,
     * and optionally whether the model should reason before answering.
     *
     * Memory consolidation asks one session two different questions — group these entries, then write
     * these merged bodies — and both are enforced per call, so a single hidden session can carry both:
     * the loop reads [AgentContext.outputSchema] when it builds each prompt's options, the schema
     * completion check reads it from the context of the turn it is judging, and the system prompt is
     * rebuilt (and persisted) for every run this session starts. One session keeps the task row's single
     * `sessionId`, its retry reuse, and the Tasks page's single transcript link all intact.
     *
     * The grouping pass benefits from thinking (wide-view judgement over many entries); the merge pass
     * rewrites bodies it was already told how to cluster, so thinking there burns tokens for nothing.
     * Pass [thinkingEnabled] to flip it per turn; null inherits the model row's default.
     */
    fun updateTurnOutputContract(
        customInstructions: String,
        outputSchema: String?,
        thinkingEnabled: Boolean? = null
    ) {
        agent = agent.copy(
            context = agent.context.copy(
                customInstructions = customInstructions,
                outputSchema = outputSchema,
                thinkingEnabled = thinkingEnabled
            )
        )
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
            },
            // Every run this session starts records why it ended, including runs nobody forwards
            // the event stream of (memory consolidation, swarm phases). Without this the caller can
            // only re-parse the answer to discover that a completion check had given up on it.
            endReasonSink = { reason -> lastEndReason = reason }
        )
        // Reset abort flag before starting execution (synchronous, before coroutine starts)
        abortRequested = false
        return runner.prompt(emptyList()) // Messages already in transcript
    }
}