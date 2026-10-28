package com.easy.easyai.core.watch

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.ErrorEvent
import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.TaskHandler
import com.easy.easyai.core.task.TaskOutcome
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.validation.JsonExtractor
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.ZoneId

/**
 * Runs one watch firing: an ordinary check, a reminder delivery, or an expiry review.
 *
 * The handler owns every write to the watch definition. The model only produces a schema-conforming
 * JSON answer; a malformed, empty or out-of-universe answer fails the run instead of half-applying.
 *
 * Ownership rules this class enforces before writing anything:
 * - the run remembers the [Watch.version] it started with and refuses to apply a finding when the
 *   row changed underneath it (the user edited, paused or cancelled);
 * - a review task whose watch no longer has `pending_action=EXPIRY_REVIEW` is stale on arrival;
 * - a watch owned by another application sharing this database is refused: its agent's tools resolve
 *   on that host, and running here would quietly use a degraded toolset;
 * - a timeout is booked as a watch failure (so the schedule backs off) before it is rethrown for the
 *   task runner to park the row TIMED_OUT. A user cancel is never booked as a failure.
 */
class WatchTaskHandler(
    private val store: WatchStore,
    private val host: HostIdentity,
    private val properties: WatchProperties = WatchProperties(),
    private val sessionManager: SessionManager? = null,
    private val agentService: AgentService? = null,
    private val modelConfigStore: ModelProviderConfigStore? = null,
    private val toolsetResolver: WatchToolsetResolver? = null,
    private val notifier: WatchNotifier? = null,
    private val taskStore: AsyncTaskStore? = null,
    private val clock: () -> Instant = { Instant.now() }
) : TaskHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: AsyncTaskType = AsyncTaskType.WATCH

    override suspend fun execute(task: AsyncTask): TaskOutcome {
        val watchId = WatchTaskPayload.watchIdOf(task)
            ?: return TaskOutcome.Failure("Watch task ${task.id} has no watch id in its payload")
        val watch = store.findById(watchId)
            ?: return TaskOutcome.Failure("The watch this task belongs to no longer exists")
        if (watch.userId != task.userId) {
            return TaskOutcome.Failure("Watch task ${task.id} belongs to a different user")
        }
        // Defence in depth: the scheduling query already filters on ownership, so reaching this means a task
        // row outlived the assumption (written before ownership was recorded, or submitted by hand).
        // Fail loudly instead of running with a toolset this process cannot resolve. Deliberately
        // before claimRunSlot, so the watch keeps its schedule and books no failure backoff.
        val watchOwner = watch.ownerApp
        if (!host.owns(watchOwner)) {
            return TaskOutcome.Failure(
                if (watchOwner == null) {
                    "Watch ${watch.id} has no owning application; refusing to run it on '${host.appName}'"
                } else {
                    "Watch ${watch.id} belongs to application '$watchOwner'; this process is '${host.appName}'"
                }
            )
        }
        if (watch.status != WatchStatus.ACTIVE) {
            logger.info("Watch {} is {}; skipping task {}", watch.id, watch.status, task.id)
            return TaskOutcome.Success(
                mapOf(
                    "watchId" to watch.id,
                    "kind" to "skipped",
                    "reason" to "watch is ${watch.status.name.lowercase()}"
                )
            )
        }
        val kind = WatchTaskPayload.kindOf(task)
        if (kind == WatchRunKind.EXPIRY_REVIEW && watch.pendingAction != WatchPendingAction.EXPIRY_REVIEW) {
            logger.info("Expiry review for watch {} is stale: the definition changed", watch.id)
            return TaskOutcome.Success(
                mapOf(
                    "watchId" to watch.id,
                    "kind" to "expiry_review",
                    "stale" to true,
                    "applied" to false,
                    "reason" to "the watch definition changed before the review ran"
                )
            )
        }

        val version = watch.version
        if (!store.claimRunSlot(task, version, clock())) {
            return TaskOutcome.Failure("The watch run slot is no longer available for task ${task.id}")
        }

        return try {
            when {
                kind == WatchRunKind.EXPIRY_REVIEW -> runExpiryReview(task, watch, version)
                watch.mode == WatchMode.REMIND -> runRemind(task, watch, version)
                else -> runCheck(task, watch, version)
            }
        } catch (e: TimeoutCancellationException) {
            // Book the watch-level delay before handing the timeout to the runner, which parks the
            // task row TIMED_OUT. Without NonCancellable the booking itself would be cancelled.
            recordFailure(task, watch, version, e.message ?: "Watch run timed out")
            throw e
        } catch (e: CancellationException) {
            // A user cancel (or shutdown) says nothing about the watch; the row keeps its schedule.
            throw e
        } catch (e: Exception) {
            val message = (e.message ?: e.javaClass.simpleName).take(MAX_ERROR_CHARS)
            recordFailure(task, watch, version, message)
            TaskOutcome.Failure(message)
        }
    }

    private suspend fun runCheck(task: AsyncTask, watch: Watch, version: Int): TaskOutcome {
        val now = clock()
        val scheduledFor = scheduledFor(task, watch)
        val lateByMs = (now.toEpochMilli() - scheduledFor.toEpochMilli()).coerceAtLeast(0)
        val tools = toolsetResolver?.resolve(watch, watch.runSessionId).orEmpty()
        val prompt = WatchPrompts.checkPrompt(watch, now, scheduledFor, lateByMs, tools.map { it.name })
        val text = runModel(
            watch = watch,
            task = task,
            instructions = WatchPrompts.CHECK_INSTRUCTIONS,
            schema = WatchPrompts.findingSchema(watch.tags),
            prompt = prompt,
            maxIterations = properties.maxIterations,
            tools = tools
        )
        val parsed = parseFinding(text, watch, version, scheduledFor, lateByMs)

        val current = store.findById(watch.id)
            ?: throw WatchException("The watch was deleted while its run was in progress")
        if (current.version != version || current.status != WatchStatus.ACTIVE) {
            logger.info(
                "Discarding finding for watch {}: definition changed ({} -> {}) or status {}",
                watch.id, version, current.version, current.status
            )
            val stale = parsed.copy(applied = false, stale = true, notified = false)
            return TaskOutcome.Success(findingResult(current, stale))
        }
        val changed = changedSince(current.lastFinding, parsed)
        // "Check now" must not consume a one-shot's only scheduled firing: only a scheduled run is
        // the anchor's last chance. A manual run still completes on `met` when completeOnMet is set.
        val scheduledRun = task.trigger != TaskTrigger.FRONTEND
        val terminal = (current.schedule.kind == WatchScheduleKind.AT && scheduledRun) ||
            (parsed.verdict == WatchVerdict.MET && current.completeOnMet)
        val status = if (terminal) WatchStatus.COMPLETED else WatchStatus.ACTIVE
        // The notify policy is judged against the finding as it will be stored: `changed` is
        // computed here, so passing the pre-copy value would make the first finding look unchanged.
        val finding = parsed.copy(changed = changed)
        val notify = notifier != null && shouldNotify(current, finding, status)
        val stored = finding.copy(notified = notify)
        val applied = store.applyFinding(
            id = current.id,
            expectedVersion = version,
            expectedStatus = WatchStatus.ACTIVE,
            status = status,
            pendingAction = WatchPendingAction.CHECK,
            nextDueAt = current.nextDueAt.toEpochMilli(),
            finding = stored,
            taskId = task.id,
            at = now.toEpochMilli()
        )
        if (applied == null) {
            logger.info("Finding for watch {} lost the version guard at apply time", watch.id)
            return TaskOutcome.Success(findingResult(current, stored.copy(applied = false, stale = true)))
        }
        if (notify) publishFinding(applied, task, stored)
        logger.info(
            "Watch check done: id={}, verdict={}, tags={}, changed={}, notified={}, status={}",
            watch.id, stored.verdict, stored.tags.map { it.key }, changed, notify, status
        )
        return TaskOutcome.Success(findingResult(applied, stored))
    }

    private suspend fun runRemind(task: AsyncTask, watch: Watch, version: Int): TaskOutcome {
        val now = clock()
        val scheduledFor = scheduledFor(task, watch)
        val lateByMs = (now.toEpochMilli() - scheduledFor.toEpochMilli()).coerceAtLeast(0)
        val current = store.findById(watch.id)
            ?: throw WatchException("The watch was deleted while its reminder was in progress")
        if (current.version != version || current.status != WatchStatus.ACTIVE) {
            return TaskOutcome.Success(
                findingResult(
                    current,
                    remindFinding(current, version, scheduledFor, lateByMs)
                        .copy(applied = false, stale = true, notified = false)
                )
            )
        }
        val until = current.untilAt
        val missedOneShot = current.schedule.kind == WatchScheduleKind.AT &&
            lateByMs > properties.catchUpWindowMs
        val expired = missedOneShot || (until != null && now.isAfter(until))
        val reason = when {
            missedOneShot ->
                "The reminder was missed by more than ${properties.catchUpWindowMs / 60_000} minutes"
            until != null && now.isAfter(until) -> "The reminder's until date has passed"
            else -> null
        }
        val finding = remindFinding(
            watch = current,
            version = version,
            scheduledFor = scheduledFor,
            lateByMs = lateByMs
        ).copy(
            conclusion = reason ?: current.reminderText ?: current.title,
            notified = notifier != null && current.notifyPolicy != WatchNotifyPolicy.NEVER
        )
        val status = if (expired) WatchStatus.EXPIRED
        else if (current.schedule.kind == WatchScheduleKind.AT && task.trigger != TaskTrigger.FRONTEND) {
            WatchStatus.COMPLETED
        } else WatchStatus.ACTIVE
        val applied = store.applyFinding(
            id = current.id,
            expectedVersion = version,
            expectedStatus = WatchStatus.ACTIVE,
            status = status,
            pendingAction = WatchPendingAction.CHECK,
            nextDueAt = current.nextDueAt.toEpochMilli(),
            finding = finding,
            taskId = task.id,
            at = now.toEpochMilli()
        ) ?: return TaskOutcome.Success(findingResult(current, finding.copy(applied = false, stale = true)))
        if (finding.notified) {
            publish(
                WatchNotice(
                    userId = applied.userId,
                    watchId = applied.id,
                    title = applied.title,
                    body = reason ?: finding.conclusion,
                    kind = if (expired) "status" else "finding",
                    dedupeKey = "watch:${applied.id}:${task.id}"
                )
            )
        }
        return TaskOutcome.Success(findingResult(applied, finding))
    }

    private suspend fun runExpiryReview(task: AsyncTask, watch: Watch, version: Int): TaskOutcome {
        val now = clock()
        val tools = toolsetResolver?.resolve(watch, watch.runSessionId).orEmpty()
        val prompt = WatchPrompts.expiryReviewPrompt(
            watch = watch,
            now = now,
            recentFindings = recentFindings(watch, REVIEW_HISTORY_LIMIT, task.id),
            extensionCount = watch.extensionCount,
            maxAutoExtensions = properties.maxAutoExtensions,
            horizonDays = properties.maxExtensionHorizonDays
        )
        val text = runModel(
            watch = watch,
            task = task,
            instructions = WatchPrompts.EXPIRY_REVIEW_INSTRUCTIONS,
            schema = WatchPrompts.EXPIRY_REVIEW_SCHEMA,
            prompt = prompt,
            maxIterations = properties.expiryReviewMaxIterations,
            tools = tools
        )
        val output = parseReview(text)
        var decision = output.decision
        var reason = output.reason.trim()
        var untilAt: Instant? = watch.untilAt
        var anchorAt: Instant? = watch.schedule.anchorAt
        var nextDue: Instant = watch.nextDueAt

        if (decision == WatchExpiryDecision.RENEW) {
            when {
                watch.extensionCount >= properties.maxAutoExtensions -> {
                    reason = "Automatic renewal limit reached; renew it manually if it still matters. " +
                        "The model wanted to renew: $reason"
                    decision = WatchExpiryDecision.EXPIRE
                }
                else -> runCatching {
                    val renewal = resolveRenewal(watch, output, now)
                    untilAt = renewal.untilAt
                    anchorAt = renewal.anchorAt
                    nextDue = renewal.nextDueAt
                }.onFailure { error ->
                    reason = "The renewal date was unusable (${error.message}); expiry was applied instead. $reason"
                    decision = WatchExpiryDecision.EXPIRE
                }
            }
        }
        val status = when (decision) {
            WatchExpiryDecision.RENEW -> WatchStatus.ACTIVE
            WatchExpiryDecision.COMPLETE -> WatchStatus.COMPLETED
            WatchExpiryDecision.EXPIRE -> WatchStatus.EXPIRED
        }
        val pendingAction = WatchPendingAction.CHECK
        val statusReason = when (decision) {
            WatchExpiryDecision.RENEW -> "Renewed: $reason"
            WatchExpiryDecision.COMPLETE -> "Completed at expiry review: $reason"
            WatchExpiryDecision.EXPIRE -> "Expired at expiry review: $reason"
        }
        val applied = store.applyExpiryReview(
            id = watch.id,
            expectedVersion = version,
            expectedPendingAction = WatchPendingAction.EXPIRY_REVIEW,
            status = status,
            pendingAction = pendingAction,
            untilAt = untilAt?.toEpochMilli(),
            anchorAt = anchorAt?.toEpochMilli(),
            nextDueAt = nextDue.toEpochMilli(),
            extensionCount = if (decision == WatchExpiryDecision.RENEW) watch.extensionCount + 1
            else watch.extensionCount,
            statusReason = statusReason,
            taskId = task.id,
            at = now.toEpochMilli()
        )
        if (applied == null) {
            logger.info("Expiry review for watch {} lost the version guard; keeping task history only", watch.id)
            return TaskOutcome.Success(
                expiryResult(watch, task, decision, reason, output.conclusion, untilAt, anchorAt,
                    applied = false, stale = true)
            )
        }
        publish(
            WatchNotice(
                userId = applied.userId,
                watchId = applied.id,
                title = applied.title,
                body = expiryNoticeBody(decision, output.conclusion),
                kind = "expiry_review",
                dedupeKey = "watch:${applied.id}:${task.id}:review"
            )
        )
        logger.info(
            "Watch expiry review done: id={}, decision={}, status={}, extensionCount={}",
            applied.id, decision, status, applied.extensionCount
        )
        return TaskOutcome.Success(
            expiryResult(applied, task, decision, reason, output.conclusion, untilAt, anchorAt,
                applied = true, stale = false)
        )
    }

    /**
     * Run one model turn against the watch's hidden session.
     *
     * Tools come from the resolver, not from the current chat: a watch created weeks ago must still
     * resolve the agent's current bindings. The session id is shared by every firing, so the
     * transcript of a watch is one audit trail — but each turn carries only its own prompt, exactly
     * like memory consolidation, so the hidden session does not grow a replay of old answers.
     */
    private suspend fun runModel(
        watch: Watch,
        task: AsyncTask,
        instructions: String,
        schema: String,
        prompt: String,
        maxIterations: Int,
        tools: List<ToolDefinition>
    ): String {
        val sessionManager = sessionManager ?: throw WatchUnavailableException(
            "Watch runs need a SessionManager, which this deployment does not provide"
        )
        val agentService = agentService ?: throw WatchUnavailableException(
            "Watch runs need an AgentService, which this deployment does not provide"
        )
        val configStore = modelConfigStore ?: throw WatchUnavailableException(
            "Watch runs need a model configuration store, which this deployment does not provide"
        )
        val modelConfigId = watch.modelConfigId ?: throw WatchException(
            "This watch has no model configuration; edit it from a chat with a model selected"
        )
        val owners = watch.owners.ifEmpty { setOf(watch.userId) }
        // Read through the same store the chat path uses, so a model the user can select there is
        // also one a watch can run with (the service facade filters some custom rows out).
        val modelConfig = configStore.getConfig(modelConfigId, owners)
            ?: throw WatchException(
                "The model configuration this watch was created with has been deleted; " +
                    "edit the watch from a chat to pick a current model"
            )
        val factory = agentService.chatModelFactories.firstOrNull { it.supports(modelConfig.protocol) }
            ?: throw WatchUnavailableException(
                "No chat model factory supports the watch's protocol '${modelConfig.protocol}'"
            )
        val baseContext = AgentContext(
            agentId = AGENT_ID,
            modelConfig = modelConfig,
            sessionId = watch.runSessionId,
            userId = watch.userId,
            systemKind = AgentContext.SYSTEM_KIND_ASYNC_TASK,
            sessionScope = "watch:${watch.id}",
            owners = owners,
            projectId = watch.projectId,
            projectPath = watch.projectPath?.let { Path.of(it) },
            projectKind = watch.projectKind,
            tools = tools,
            maxIterations = maxIterations,
            customInstructions = instructions,
            outputSchema = schema,
            outputSchemaMultiTurn = true,
            timeZone = watch.schedule.zone,
            unattended = true
        )
        val session = sessionManager.getOrCreateSession(baseContext, modelConfig, factory)
        session.updateTurnOutputContract(customInstructions = instructions, outputSchema = schema)
        val userMessage = UserMessage(
            content = listOf(TextContent(prompt)),
            metadata = mapOf(
                "watchId" to watch.id,
                "watchRun" to task.id,
                "watchKind" to WatchTaskPayload.kindOf(task).name.lowercase()
            )
        )
        sessionManager.saveSessionMessages(session.agentContext, listOf(userMessage))
        val stream = session.promptWithHistory(listOf(userMessage))
        val text = stream.result().lastOrNull()?.text().orEmpty()
        if (session.lastEndReason == AgentCompletionCheck.END_REASON_STALLED) {
            throw WatchException("The model did not produce an answer matching the required schema")
        }
        if (session.lastEndReason == END_REASON_LLM_ERROR) {
            val reason = stream.asFlow().filterIsInstance<ErrorEvent>().firstOrNull()?.error?.message
            throw WatchException("The model call failed: ${reason ?: "no error message"}")
        }
        if (text.isBlank()) throw WatchException("The model returned an empty answer")
        return text
    }

    private fun parseFinding(
        text: String,
        watch: Watch,
        version: Int,
        scheduledFor: Instant,
        lateByMs: Long
    ): WatchFinding {
        val json = JsonExtractor.extract(text)
            ?: throw WatchException("The model answered without a JSON object")
        val output = runCatching {
            SharedObjectMapper.instance.readValue(json, CheckOutput::class.java)
        }.getOrElse { throw WatchException("The finding JSON could not be read: ${it.message}") }
        val conclusion = output.conclusion?.trim().orEmpty()
        if (conclusion.isEmpty()) throw WatchException("The finding has no conclusion")
        if (conclusion.length > WatchPrompts.MAX_CONCLUSION_CHARS) {
            throw WatchException(
                "The finding conclusion is longer than ${WatchPrompts.MAX_CONCLUSION_CHARS} characters"
            )
        }
        val verdict = output.verdict?.trim()?.let { raw ->
            WatchVerdict.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: throw WatchException("The finding verdict '${output.verdict}' is not one of met/not_met/unknown/note")
        val universe = watch.tags.associateBy { it.key }
        val tags = output.tags.orEmpty()
            .map { it.trim().lowercase() }
            .distinct()
            .map { key ->
                val tag = universe[key]
                    ?: throw WatchException("Tag '$key' is not part of this watch's tag universe")
                WatchFoundTag(key = tag.key, label = tag.label)
            }
        val sources = output.sources.orEmpty().mapNotNull { raw ->
            val label = raw.label?.trim().orEmpty()
            val url = raw.url?.trim().orEmpty()
            if (label.isEmpty() || !isHttpUrl(url)) {
                logger.warn("Dropping an unusable finding source (label='{}', url='{}')", label, url)
                null
            } else {
                WatchSource(label = label.take(MAX_SOURCE_LABEL_CHARS), url = url)
            }
        }.take(WatchPrompts.MAX_SOURCES)
        return WatchFinding(
            conclusion = conclusion,
            verdict = verdict,
            tags = tags,
            sources = sources,
            definitionVersion = version,
            scheduledFor = scheduledFor.toEpochMilli(),
            lateByMs = lateByMs
        )
    }

    private fun parseReview(text: String): ReviewAnswer {
        val json = JsonExtractor.extract(text)
            ?: throw WatchException("The expiry review answered without a JSON object")
        val output = runCatching {
            SharedObjectMapper.instance.readValue(json, ReviewOutput::class.java)
        }.getOrElse { throw WatchException("The expiry review JSON could not be read: ${it.message}") }
        val decision = output.decision?.trim()?.let { raw ->
            WatchExpiryDecision.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: throw WatchException("The expiry review decision '${output.decision}' is not valid")
        val reason = output.reason?.trim().orEmpty()
        if (reason.isEmpty()) throw WatchException("The expiry review has no reason")
        val conclusion = output.conclusion?.trim().orEmpty()
        if (conclusion.isEmpty()) throw WatchException("The expiry review has no conclusion")
        return ReviewAnswer(
            decision = decision,
            reason = reason.take(MAX_REVIEW_REASON_CHARS),
            conclusion = conclusion.take(MAX_REVIEW_CONCLUSION_CHARS),
            newUntil = output.newUntil,
            rescheduleAt = output.rescheduleAt
        )
    }

    /** The concrete dates a `renew` decision implies, validated against the deployment limits. */
    private fun resolveRenewal(watch: Watch, output: ReviewAnswer, now: Instant): Renewal {
        val horizon = now.plusSeconds(properties.maxExtensionHorizonDays * SECONDS_PER_DAY)
        if (watch.schedule.kind == WatchScheduleKind.AT) {
            val raw = output.rescheduleAt?.trim()
                ?: throw WatchException("A one-shot renewal needs 'reschedule_at'")
            val anchor = parseInstant(raw, watch.schedule.zone)
            if (!anchor.isAfter(now)) throw WatchException("'reschedule_at' must be in the future")
            if (anchor.isAfter(horizon)) {
                throw WatchException(
                    "'reschedule_at' must be within ${properties.maxExtensionHorizonDays} days"
                )
            }
            val until = watch.untilAt?.takeIf { it.isAfter(anchor) }
            return Renewal(untilAt = until, anchorAt = anchor, nextDueAt = anchor)
        }
        val raw = output.newUntil?.trim()
            ?: throw WatchException("A recurring renewal needs 'new_until' as YYYY-MM-DD")
        val date = runCatching { LocalDate.parse(raw) }.getOrElse {
            throw WatchException("'new_until' must be a date like 2026-12-31")
        }
        val until = WatchScheduleCalculator.endOfDay(date, watch.schedule.zone)
        if (!until.isAfter(now)) throw WatchException("'new_until' must be in the future")
        if (until.isAfter(horizon)) {
            throw WatchException("'new_until' must be within ${properties.maxExtensionHorizonDays} days")
        }
        val next = WatchScheduleCalculator.nextAfter(watch.schedule, now)
        return Renewal(
            untilAt = until,
            anchorAt = null,
            nextDueAt = if (next.isAfter(until)) until else next
        )
    }

    private fun parseInstant(raw: String, zone: ZoneId): Instant {
        fun attempt(block: () -> Instant): Instant? = try {
            block()
        } catch (_: DateTimeParseException) {
            null
        }
        return attempt { OffsetDateTime.parse(raw).toInstant() }
            ?: attempt { Instant.parse(raw) }
            ?: attempt { LocalDateTime.parse(raw).atZone(zone).toInstant() }
            ?: attempt { LocalDate.parse(raw).atTime(9, 0).atZone(zone).toInstant() }
            ?: throw WatchException("The date '$raw' is not an ISO-8601 date or date-time")
    }

    private fun remindFinding(
        watch: Watch,
        version: Int,
        scheduledFor: Instant,
        lateByMs: Long
    ): WatchFinding = WatchFinding(
        conclusion = watch.reminderText ?: watch.title,
        verdict = WatchVerdict.NOTE,
        definitionVersion = version,
        changed = true,
        scheduledFor = scheduledFor.toEpochMilli(),
        lateByMs = lateByMs
    )

    private fun changedSince(previous: WatchFinding?, finding: WatchFinding): Boolean {
        if (previous == null) return true
        if (previous.definitionVersion != finding.definitionVersion) return true
        if (previous.verdict != finding.verdict) return true
        return previous.tags.map { it.key }.toSet() != finding.tags.map { it.key }.toSet()
    }

    private fun shouldNotify(watch: Watch, finding: WatchFinding, status: WatchStatus): Boolean {
        val tagged = finding.tags.any { it.key in watch.notifyTags }
        return when (watch.notifyPolicy) {
            WatchNotifyPolicy.NEVER -> false
            WatchNotifyPolicy.ON_CHANGE -> finding.changed || tagged
            WatchNotifyPolicy.ON_COMPLETE -> status == WatchStatus.COMPLETED || tagged
            WatchNotifyPolicy.EVERY -> true
        }
    }

    private suspend fun publishFinding(watch: Watch, task: AsyncTask, finding: WatchFinding) {
        publish(
            WatchNotice(
                userId = watch.userId,
                watchId = watch.id,
                title = watch.title,
                body = finding.conclusion.take(MAX_NOTICE_BODY_CHARS),
                kind = "finding",
                dedupeKey = "watch:${watch.id}:${task.id}:finding"
            )
        )
    }

    private suspend fun publish(notice: WatchNotice) {
        val notifier = notifier ?: return
        runCatching { notifier.publish(notice) }
            .onFailure { logger.warn("Watch notice delivery failed for {}: {}", notice.watchId, it.message) }
    }

    /**
     * Book a failed run: bump the streak, delay the next attempt with exponential backoff, and pause
     * after the configured number of consecutive failures.
     *
     * Runs under [NonCancellable] because the timeout path reaches it with the run's context already
     * cancelled; without it the delay would never be written and the same broken watch would be due
     * again on the next tick.
     */
    private suspend fun recordFailure(task: AsyncTask, watch: Watch, version: Int, error: String) {
        withContext(NonCancellable) {
            val now = clock()
            val failures = watch.consecutiveFailures + 1
            val paused = failures >= properties.maxFailuresBeforePause
            // A manual "check now" says nothing about the schedule: moving the due instant would turn
            // one failed click into an extra unscheduled firing (and, for a one-shot, replace the
            // user's moment with the backoff instant).
            val nextDue = if (task.trigger == TaskTrigger.FRONTEND) {
                watch.nextDueAt
            } else {
                now.plusMillis(failureBackoffMs(failures))
            }
            val statusReason = if (paused) {
                "Paused after $failures consecutive failures"
            } else {
                watch.statusReason
            }
            val updated = runCatching {
                store.recordFailure(
                    id = watch.id,
                    expectedVersion = version,
                    expectedStatus = WatchStatus.ACTIVE,
                    error = error,
                    nextDueAt = nextDue.toEpochMilli(),
                    status = if (paused) WatchStatus.PAUSED else WatchStatus.ACTIVE,
                    consecutiveFailures = failures,
                    statusReason = statusReason,
                    taskId = task.id,
                    at = now.toEpochMilli()
                )
            }.onFailure {
                logger.warn("Failed to record watch failure for {}: {}", watch.id, it.message)
            }.getOrNull()
            if (updated == null) {
                logger.info("Watch failure for {} was not recorded: the definition changed", watch.id)
                return@withContext
            }
            logger.warn(
                "Watch run failed: id={}, failures={}, paused={}, nextDue={}, error={}",
                watch.id, failures, paused, nextDue, error
            )
            if (paused) {
                publish(
                    WatchNotice(
                        userId = updated.userId,
                        watchId = updated.id,
                        title = updated.title,
                        body = "Paused after $failures consecutive failures: $error",
                        kind = "status",
                        dedupeKey = "watch:${updated.id}:${task.id}:paused"
                    )
                )
            }
        }
    }

    private fun failureBackoffMs(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        return minOf(
            properties.failureBackoffBaseMs * (1L shl shift),
            properties.failureBackoffMaxMs
        )
    }

    private fun scheduledFor(task: AsyncTask, watch: Watch): Instant =
        WatchTaskPayload.scheduledForOf(task)?.let { Instant.ofEpochMilli(it) } ?: watch.nextDueAt

    private suspend fun recentFindings(watch: Watch, limit: Int, currentTaskId: String): List<WatchFinding> {
        val store = taskStore ?: return emptyList()
        return runCatching {
            store.listByUser(
                userId = watch.userId,
                type = AsyncTaskType.WATCH,
                sessionId = watch.runSessionId,
                limit = limit + 1
            ).asSequence()
                .filter { it.id != currentTaskId }
                .mapNotNull { findingOf(it.result) }
                .take(limit)
                .toList()
        }.onFailure {
            logger.warn("Failed to list recent findings for watch {}: {}", watch.id, it.message)
        }.getOrDefault(emptyList())
    }

    private fun findingOf(result: Map<String, Any?>?): WatchFinding? {
        if (result == null || result["kind"] != "check") return null
        val conclusion = result["conclusion"] as? String ?: return null
        val verdict = (result["verdict"] as? String)?.let { raw ->
            WatchVerdict.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: return null
        val tags = (result["tags"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val key = map["key"] as? String ?: return@mapNotNull null
            WatchFoundTag(key = key, label = map["label"] as? String ?: key)
        }
        return WatchFinding(
            conclusion = conclusion,
            verdict = verdict,
            tags = tags,
            definitionVersion = (result["definitionVersion"] as? Number)?.toInt() ?: 0
        )
    }

    private fun findingResult(watch: Watch, finding: WatchFinding): Map<String, Any?> = mapOf(
        "watchId" to watch.id,
        "kind" to "check",
        "definitionVersion" to finding.definitionVersion,
        "conclusion" to finding.conclusion,
        "verdict" to finding.verdict.name.lowercase(),
        "tags" to finding.tags.map { mapOf("key" to it.key, "label" to it.label) },
        "sources" to finding.sources.map { mapOf("label" to it.label, "url" to it.url) },
        "changed" to finding.changed,
        "notified" to finding.notified,
        "applied" to finding.applied,
        "stale" to finding.stale,
        "scheduledFor" to finding.scheduledFor,
        "lateByMs" to finding.lateByMs
    )

    private fun expiryResult(
        watch: Watch,
        task: AsyncTask,
        decision: WatchExpiryDecision,
        reason: String,
        conclusion: String,
        untilAt: Instant?,
        anchorAt: Instant?,
        applied: Boolean,
        stale: Boolean
    ): Map<String, Any?> = mapOf(
        "watchId" to watch.id,
        "kind" to "expiry_review",
        "taskId" to task.id,
        "definitionVersion" to watch.version,
        "decision" to decision.name.lowercase(),
        "reason" to reason,
        "conclusion" to conclusion,
        "newUntil" to untilAt?.toEpochMilli(),
        "rescheduleAt" to anchorAt?.toEpochMilli(),
        "applied" to applied,
        "stale" to stale
    )

    private fun expiryNoticeBody(decision: WatchExpiryDecision, conclusion: String): String = when (decision) {
        WatchExpiryDecision.RENEW -> "Renewed: $conclusion"
        WatchExpiryDecision.COMPLETE -> "Completed: $conclusion"
        WatchExpiryDecision.EXPIRE -> "Expired: $conclusion"
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

    private data class CheckOutput(
        val conclusion: String? = null,
        val verdict: String? = null,
        val tags: List<String>? = null,
        val sources: List<RawSource>? = null
    )

    private data class RawSource(
        val label: String? = null,
        val url: String? = null
    )

    private data class ReviewOutput(
        val decision: String? = null,
        val reason: String? = null,
        val conclusion: String? = null,
        @field:JsonProperty("new_until") val newUntil: String? = null,
        @field:JsonProperty("reschedule_at") val rescheduleAt: String? = null
    )

    /** A review answer after semantic validation; [newUntil]/[rescheduleAt] are raw model text. */
    private data class ReviewAnswer(
        val decision: WatchExpiryDecision,
        val reason: String,
        val conclusion: String,
        val newUntil: String?,
        val rescheduleAt: String?
    )

    private data class Renewal(
        val untilAt: Instant?,
        val anchorAt: Instant?,
        val nextDueAt: Instant
    )

    private companion object {
        // Reserved: an agent row with this id would be looked up by the session manager and replace
        // the tools, instructions and output schema this handler builds. AgentController refuses to
        // create user agents with this id.
        const val AGENT_ID = "watch-runner"
        const val END_REASON_LLM_ERROR = "error"
        const val REVIEW_HISTORY_LIMIT = 5
        const val MAX_BACKOFF_SHIFT = 5
        const val MAX_ERROR_CHARS = 1000
        const val MAX_REVIEW_REASON_CHARS = 1000
        const val MAX_REVIEW_CONCLUSION_CHARS = 600
        const val MAX_SOURCE_LABEL_CHARS = 120
        const val MAX_NOTICE_BODY_CHARS = 500
        const val SECONDS_PER_DAY = 86_400L
    }
}
