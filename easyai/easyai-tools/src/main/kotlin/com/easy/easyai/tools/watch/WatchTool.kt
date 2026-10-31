package com.easy.easyai.tools.watch

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolCapability
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.easy.easyai.core.watch.Watch
import com.easy.easyai.core.watch.WatchCreateInput
import com.easy.easyai.core.watch.WatchException
import com.easy.easyai.core.watch.WatchFinding
import com.easy.easyai.core.watch.WatchMode
import com.easy.easyai.core.watch.WatchNotifyPolicy
import com.easy.easyai.core.watch.WatchOrigin
import com.easy.easyai.core.watch.WatchPendingAction
import com.easy.easyai.core.watch.WatchPrompts
import com.easy.easyai.core.watch.WatchScheduleInput
import com.easy.easyai.core.watch.WatchService
import com.easy.easyai.core.watch.WatchStatus
import com.easy.easyai.core.watch.WatchTag
import com.easy.easyai.core.watch.WatchTriggerParser
import com.easy.easyai.core.watch.WatchUpdateInput
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The LLM-facing `watch` tool: create a standing intent, list or edit one, pause/resume/cancel it.
 *
 * The model never computes a date. Every trigger form is relative to the server's clock (`within`,
 * `dayOffset`, `weekday`) or an absolute ISO instant, and the tool returns the *resolved* next run
 * so the model can tell the user a time it did not have to guess.
 *
 * Tool set for a watch is approved here, against the tools this session actually has: by default the
 * read-only static tools, never MCP. Explicit names must exist and pass the unattended safety rules;
 * an unsafe name is an error, not silently dropped, because the user would otherwise believe the
 * watch can use something it cannot. Naming an MCP tool is itself the approval: its capabilities
 * cannot be declared here, so the caller is accepting that its remote side effects are unknown.
 */
class WatchTool(
    metadata: ToolMetadata,
    private val watchService: WatchService
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode: ToolExecutionMode = ToolExecutionMode.SEQUENTIAL

    override fun parameterType(): Class<*> = WatchToolParameter::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val params = try {
            SharedObjectMapper.instance.readValue(
                SharedObjectMapper.instance.writeValueAsString(args),
                WatchToolParameter::class.java
            )
        } catch (e: Exception) {
            return errorResult("Invalid watch parameters: ${e.message}")
        }
        if (agentContext.unattended) {
            return errorResult("A watch run cannot create or edit watches; there is no user to confirm it")
        }
        val userId = agentContext.userId
            ?: return errorResult("Watch management needs an authenticated user")
        return try {
            when (params.action.trim().lowercase()) {
                "create" -> create(agentContext, userId, params)
                "list" -> list(userId, params)
                "update" -> update(agentContext, userId, params)
                "pause" -> setStatus(userId, params, "paused")
                "resume" -> setStatus(userId, params, "resumed")
                "cancel" -> setStatus(userId, params, "cancelled")
                else -> errorResult(
                    "Unknown action '${params.action}'. Use create, list, update, pause, resume or cancel."
                )
            }
        } catch (e: WatchException) {
            // These messages are written for the caller to act on; keep them verbatim.
            errorResult(e.message ?: "The watch request was rejected")
        } catch (e: Exception) {
            logger.error("Watch tool failed for action {}: {}", params.action, e.message, e)
            errorResult("Watch operation failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun create(context: AgentContext, userId: String, params: WatchToolParameter): ToolResult {
        val warnings = mutableListOf<String>()
        val zone = parseZone(params.timezone)
        val schedule = resolveSchedule(params, context, require = true, zone = zone)
            ?: return errorResult(
                "A new watch needs one trigger: 'at', 'within', 'dayOffset', 'weekday' (one-shot), " +
                    "or 'every' with a 'time' (daily/weekly/monthly)"
            )
        val allowedTools = resolveAllowedTools(context, params.allowedTools, warnings)
        val input = WatchCreateInput(
            title = params.title?.trim().orEmpty(),
            condition = params.condition?.trim().orEmpty(),
            schedule = schedule,
            mode = parseMode(params.mode),
            reminder = params.reminder,
            timezone = zone,
            until = parseUntil(params.until),
            tags = parseTags(params.tags),
            completeOnMet = params.completeOnMet,
            notifyPolicy = parseNotifyPolicy(params.notify),
            notifyTags = params.notifyTags?.map { it.trim().lowercase() }?.toSet() ?: emptySet(),
            sourceHints = params.sourceHints,
            allowedTools = allowedTools
        )
        val origin = WatchOrigin(
            userId = userId,
            sourceAgentId = context.agentId,
            modelConfigId = context.modelConfig?.id,
            projectId = context.projectId,
            projectPath = context.projectPath?.toString(),
            projectKind = context.projectKind,
            owners = context.effectiveOwners,
            originSessionId = context.sessionId,
            timeZone = context.timeZone
        )
        val result = watchService.create(origin, input)
        warnings += result.warnings
        val watch = result.watch
        val heading = if (result.created) {
            "Created watch \"${watch.title}\"."
        } else {
            "An active watch with the same condition already exists; returned it instead of creating a duplicate."
        }
        val content = buildString {
            appendLine(heading)
            appendLine("Watch id: ${watch.id}")
            appendLine("Next run: ${format(watch.nextDueAt, watch.schedule.zone)}")
            appendLine("Schedule: ${WatchPrompts.describeSchedule(watch)}")
            appendLine("Complete when met: ${watch.completeOnMet}")
            watch.untilAt?.let { appendLine("Until: ${format(it, watch.schedule.zone)}") }
            if (watch.tags.isNotEmpty()) {
                appendLine("Tags: ${watch.tags.joinToString(", ") { "${it.key} (${it.label})" }}")
            }
            appendLine(
                "Data tools: ${watch.allowedTools.ifEmpty { listOf("(none)") }.joinToString(", ")}"
            )
            warnings.forEach { appendLine("Note: $it") }
            appendLine("Tell the user the next run time above; do not restate this whole block.")
        }
        return ToolResult(
            content = listOf(TextContent(content)),
            details = watchDetails(watch) + buildMap {
                put("created", result.created)
                put("warnings", warnings)
            }
        )
    }

    private suspend fun list(userId: String, params: WatchToolParameter): ToolResult {
        if (params.watchId != null) {
            val watch = watchService.get(userId, params.watchId)
            return ToolResult(
                content = listOf(TextContent(watchSummary(watch, detailed = true))),
                details = watchDetails(watch)
            )
        }
        val statuses = parseStatuses(params.status)
        val watches = watchService.list(userId, statuses)
        val content = if (watches.isEmpty()) {
            "No watches match."
        } else {
            watches.joinToString("\n\n") { watchSummary(it, detailed = false) }
        }
        return ToolResult(
            content = listOf(TextContent(content)),
            details = mapOf(
                "watches" to watches.map { watchDetails(it) }
            )
        )
    }

    private suspend fun update(
        context: AgentContext,
        userId: String,
        params: WatchToolParameter
    ): ToolResult {
        val watchId = params.watchId
            ?: return errorResult("'watchId' is required for update; use action=list to find it")
        val warnings = mutableListOf<String>()
        val zone = parseZone(params.timezone)
        val allowedTools = params.allowedTools?.let { requested ->
            resolveAllowedTools(context, requested.ifEmpty { null }, warnings)
        }
        val input = WatchUpdateInput(
            title = params.title,
            condition = params.condition,
            mode = params.mode?.let { parseMode(it) },
            reminder = params.reminder,
            clearReminder = params.clearReminder ?: false,
            schedule = resolveSchedule(params, context, require = false, zone = zone),
            timezone = zone,
            until = parseUntil(params.until),
            clearUntil = params.clearUntil ?: false,
            tags = params.tags?.let(::parseTags),
            completeOnMet = params.completeOnMet,
            notifyPolicy = params.notify?.let { parseNotifyPolicy(it) },
            notifyTags = params.notifyTags?.map { it.trim().lowercase() }?.toSet(),
            sourceHints = params.sourceHints,
            clearSourceHints = params.clearSourceHints ?: false,
            allowedTools = allowedTools
        )
        val result = watchService.update(userId, watchId, input)
        warnings += result.warnings
        val watch = result.watch
        val content = buildString {
            appendLine("Updated watch \"${watch.title}\" (${watch.status.name.lowercase()}).")
            appendLine("Next run: ${format(watch.nextDueAt, watch.schedule.zone)}")
            appendLine("Schedule: ${WatchPrompts.describeSchedule(watch)}")
            appendLine("Complete when met: ${watch.completeOnMet}")
            watch.untilAt?.let { appendLine("Until: ${format(it, watch.schedule.zone)}") }
            warnings.forEach { appendLine("Note: $it") }
            appendLine("Tell the user the next run time above.")
        }
        return ToolResult(
            content = listOf(TextContent(content)),
            details = watchDetails(watch) + mapOf("warnings" to warnings)
        )
    }

    private suspend fun setStatus(userId: String, params: WatchToolParameter, verb: String): ToolResult {
        val watchId = params.watchId
            ?: return errorResult("'watchId' is required for ${params.action}")
        val watch = when (params.action.trim().lowercase()) {
            "pause" -> watchService.pause(userId, watchId)
            "resume" -> watchService.resume(userId, watchId)
            else -> watchService.cancel(userId, watchId)
        }
        return ToolResult(
            content = listOf(
                TextContent(
                    "Watch \"${watch.title}\" is now ${watch.status.name.lowercase()} ($verb)."
                )
            ),
            details = watchDetails(watch)
        )
    }

    /**
     * Resolve one trigger form. A nullable [zone] means "resolve from the request context"; passing
     * the already-parsed zone keeps a single `timezone` field from being interpreted twice.
     */
    private fun resolveSchedule(
        params: WatchToolParameter,
        context: AgentContext?,
        require: Boolean,
        zone: ZoneId? = null
    ): WatchScheduleInput? {
        val effectiveZone = zone ?: context?.timeZone ?: ZoneId.systemDefault()
        return WatchTriggerParser.parse(
            fields = WatchTriggerParser.Fields(
                at = params.at,
                within = params.within,
                dayOffset = params.dayOffset,
                every = params.every,
                time = params.time,
                weekday = params.weekday,
                dayOfMonth = params.dayOfMonth
            ),
            zone = effectiveZone,
            require = require
        )
    }

    private fun parseMode(raw: String?): WatchMode = when (raw?.trim()?.lowercase()) {
        null, "", "check" -> WatchMode.CHECK
        "remind" -> WatchMode.REMIND
        else -> throw WatchException("'mode' must be check or remind")
    }

    private fun parseNotifyPolicy(raw: String?): WatchNotifyPolicy = when (
        raw?.trim()?.lowercase()?.replace('-', '_')
    ) {
        null, "", "on_change" -> WatchNotifyPolicy.ON_CHANGE
        "on_complete" -> WatchNotifyPolicy.ON_COMPLETE
        "every" -> WatchNotifyPolicy.EVERY
        "never" -> WatchNotifyPolicy.NEVER
        else -> throw WatchException("'notify' must be on_change, on_complete, every or never")
    }

    private fun parseUntil(raw: String?): LocalDate? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDate.parse(value) }.getOrElse {
            throw WatchException("'until' must be a date like 2026-12-31")
        }
    }

    private fun parseStatuses(raw: String?): Set<WatchStatus>? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.equals("all", ignoreCase = true)) return null
        return value.split(',').mapNotNull { token ->
            WatchStatus.entries.firstOrNull { it.name.equals(token.trim(), ignoreCase = true) }
                ?: throw WatchException(
                    "Unknown status '${token.trim()}'; use active, paused, completed, expired, cancelled or all"
                )
        }.toSet().takeIf { it.isNotEmpty() }
    }

    private fun parseTags(raw: List<WatchToolTag>?): List<WatchTag> = raw.orEmpty().map { tag ->
        WatchTag(
            key = tag.key?.trim().orEmpty(),
            label = tag.label?.trim().orEmpty(),
            description = tag.description?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun parseZone(raw: String?): ZoneId? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { ZoneId.of(value) }.getOrElse {
            throw WatchException(
                "'timezone' must be an IANA zone like Asia/Shanghai; '$value' is not one"
            )
        }
    }

    /**
     * Resolve the tools a watch run may use, against the tools this session actually carries.
     * Omitted means "the agent's read-only static tools"; MCP tools are never implied. `calc` is
     * appended in both forms: it reads nothing on its own and is the only way a shell-less run can
     * analyze a result the harness spilled to a file.
     */
    private fun resolveAllowedTools(
        agentContext: AgentContext,
        requested: List<String>?,
        warnings: MutableList<String>
    ): List<String> {
        val cleaned = requested.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val available = agentContext.tools
        val byName = available.associateBy { it.name }
        if (cleaned.isEmpty()) {
            val defaults = available.filter { it.isWatchSafe() && !it.name.contains(MCP_SEPARATOR) }
            if (defaults.isEmpty()) {
                warnings += "No read-only data tools are available to this agent; " +
                    "watch runs will report that they could not verify anything."
            }
            return withAnalysisTool(defaults.map { it.name }, byName)
        }
        val accepted = mutableListOf<String>()
        for (name in cleaned) {
            val tool = byName[name]
                ?: throw WatchException(
                    "Tool '$name' is not available to this agent; remove it from 'allowedTools' " +
                        "or bind it to the agent first"
                )
            if (!tool.isWatchSafe()) {
                throw WatchException(
                    "Tool '$name' cannot run unattended: it writes files, executes commands, or needs " +
                        "user interaction. Watches may only gather information."
                )
            }
            accepted += name
        }
        return withAnalysisTool(accepted, byName)
    }

    /**
     * `calc` is a computation primitive, not a data source: it gathers nothing and its host-side file
     * access is bounded, so it rides along even when `allowedTools` narrows the set. Without it, a
     * watch that names an MCP tool would have no way to analyze an oversized result that the harness
     * spilled to a temp file — those runs have no shell.
     */
    private fun withAnalysisTool(names: List<String>, byName: Map<String, ToolDefinition>): List<String> {
        if (ANALYSIS_TOOL in names) return names
        val analysisTool = byName[ANALYSIS_TOOL] ?: return names
        if (!analysisTool.isWatchSafe()) return names
        return names + ANALYSIS_TOOL
    }

    private fun ToolDefinition.isWatchSafe(): Boolean {
        if (tracksFileChanges) return false
        val blocked = setOf(
            ToolCapability.USER_INTERACTIVE,
            ToolCapability.SPAWNS_SUBAGENTS,
            ToolCapability.TEAM_COORDINATION,
            ToolCapability.SHELL_EXECUTION
        )
        if (capabilities.any { it in blocked }) return false
        return if (name.contains(MCP_SEPARATOR)) true else ToolCapability.READ_ONLY in capabilities
    }

    private fun watchSummary(watch: Watch, detailed: Boolean): String = buildString {
        appendLine("${watch.title} (${watch.id})")
        appendLine("Status: ${watch.status.name.lowercase()}")
        if (watch.status == WatchStatus.ACTIVE) {
            appendLine("Next run: ${format(watch.nextDueAt, watch.schedule.zone)}")
        }
        appendLine("Schedule: ${WatchPrompts.describeSchedule(watch)}")
        appendLine("Complete when met: ${watch.completeOnMet}")
        if (watch.pendingAction == WatchPendingAction.EXPIRY_REVIEW) {
            appendLine("Expiry review is pending for this watch.")
        }
        if (detailed) {
            appendLine("Condition: ${watch.condition}")
            if (watch.tags.isNotEmpty()) {
                appendLine("Tags: ${watch.tags.joinToString(", ") { "${it.key} (${it.label})" }}")
            }
            appendLine("Data tools: ${watch.allowedTools.ifEmpty { listOf("(none)") }.joinToString(", ")}")
            watch.consecutiveFailures.takeIf { it > 0 }?.let {
                appendLine("Consecutive failures: $it (${watch.lastError ?: "no error recorded"})")
            }
        }
        watch.lastFinding?.let { finding ->
            appendLine(
                "Last finding (${finding.verdict.name.lowercase()}): ${finding.conclusion.take(400)}"
            )
            if (finding.tags.isNotEmpty()) {
                appendLine("  tags: ${finding.tags.joinToString(", ") { it.key }}")
            }
        }
    }

    private fun watchDetails(watch: Watch): Map<String, Any> = buildMap {
        put("watchId", watch.id)
        put("title", watch.title)
        put("status", watch.status.name.lowercase())
        put("pendingAction", watch.pendingAction.name.lowercase())
        put("mode", watch.mode.name.lowercase())
        put("scheduleKind", watch.schedule.kind.name.lowercase())
        put("timezone", watch.schedule.zone.id)
        put("schedule", WatchPrompts.describeSchedule(watch))
        put("completeOnMet", watch.completeOnMet)
        put("nextRunAt", watch.nextDueAt.toEpochMilli())
        put("nextRunLocal", format(watch.nextDueAt, watch.schedule.zone))
        watch.untilAt?.let {
            put("untilAt", it.toEpochMilli())
            put("untilLocal", format(it, watch.schedule.zone))
        }
        put("tags", watch.tags.map { mapOf("key" to it.key, "label" to it.label) })
        put("allowedTools", watch.allowedTools)
        put("version", watch.version)
        put("extensionCount", watch.extensionCount)
        put("consecutiveFailures", watch.consecutiveFailures)
        watch.sourceHints?.let { put("sourceHints", it) }
        watch.lastError?.let { put("lastError", it) }
        watch.statusReason?.let { put("statusReason", it) }
        watch.lastFinding?.let { put("lastFinding", findingDetails(it)) }
    }

    private fun findingDetails(finding: WatchFinding): Map<String, Any> = buildMap {
        put("conclusion", finding.conclusion)
        put("verdict", finding.verdict.name.lowercase())
        put("tags", finding.tags.map { mapOf("key" to it.key, "label" to it.label) })
        put("sources", finding.sources.map { mapOf("label" to it.label, "url" to it.url) })
        put("definitionVersion", finding.definitionVersion)
        put("stale", finding.stale)
        put("applied", finding.applied)
        finding.scheduledFor?.let { put("scheduledFor", it) }
    }

    private fun format(instant: Instant, zone: ZoneId): String =
        DISPLAY_FORMAT.withZone(zone).format(instant)

    private companion object {
        const val MCP_SEPARATOR = "__"
        const val ANALYSIS_TOOL = "calc"
        val DISPLAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
    }
}

data class WatchToolParameter(
    @param:JsonPropertyDescription("Action: create, list, update, pause, resume or cancel")
    val action: String,

    @param:JsonPropertyDescription("Watch id, for update/pause/resume/cancel (and for listing one watch)")
    val watchId: String? = null,

    @param:JsonPropertyDescription(
        "list only: filter by status (active, paused, completed, expired, cancelled) or all"
    )
    val status: String? = null,

    @param:JsonPropertyDescription("Short user-facing title of the standing intent")
    val title: String? = null,

    @param:JsonPropertyDescription(
        "What to verify when the watch fires, or (mode=remind) what the reminder is about. " +
            "Write it as a self-contained instruction: the run has no chat history."
    )
    val condition: String? = null,

    @param:JsonPropertyDescription("check (gather evidence and judge, default) or remind (deliver text)")
    val mode: String? = null,

    @param:JsonPropertyDescription("Reminder text for mode=remind, shown as the conclusion")
    val reminder: String? = null,

    @param:JsonPropertyDescription("Update only: clear the reminder text")
    val clearReminder: Boolean? = null,

    @param:JsonPropertyDescription(
        "One-shot trigger: ISO-8601 date-time, e.g. 2026-11-01T09:00:00+08:00. " +
            "If you only know a relative moment, prefer within/dayOffset/weekday."
    )
    val at: String? = null,

    @param:JsonPropertyDescription("One-shot trigger relative to now, e.g. 30m, 2h, 3d")
    val within: String? = null,

    @param:JsonPropertyDescription("One-shot trigger: 0=today, 1=tomorrow, at 'time' in the watch timezone")
    val dayOffset: Int? = null,

    @param:JsonPropertyDescription(
        "daily, weekly or monthly recurrence. Requires 'time'; weekly also requires 'weekday'; " +
            "monthly optionally takes 'dayOfMonth' (defaults to today's day, clamped to short months)."
    )
    val every: String? = null,

    @param:JsonPropertyDescription("Local time HH:mm used by dayOffset, weekday or every")
    val time: String? = null,

    @param:JsonPropertyDescription(
        "Weekday MONDAY..SUNDAY. With 'every'=weekly it is the recurrence day; without 'every' it " +
            "means the next occurrence of that weekday at 'time' (one-shot)."
    )
    val weekday: String? = null,

    @param:JsonPropertyDescription("Day of month 1..31 for every=monthly; omit for the current day")
    val dayOfMonth: Int? = null,

    @param:JsonPropertyDescription(
        "IANA time zone (e.g. Asia/Shanghai). Omit to use the user's request time zone; the result " +
            "warns when the server default had to be used."
    )
    val timezone: String? = null,

    @param:JsonPropertyDescription(
        "Optional last date of the watch as YYYY-MM-DD in the watch timezone. When it passes, a " +
            "review decides whether to renew, complete or expire it."
    )
    val until: String? = null,

    @param:JsonPropertyDescription("Update only: clear the existing until date")
    val clearUntil: Boolean? = null,

    @param:JsonPropertyDescription(
        "Tag universe this watch may report, at most 8. Keys must match ^[a-z][a-z0-9_]{1,31}$."
    )
    val tags: List<WatchToolTag>? = null,

    @param:JsonPropertyDescription(
        "Mark the watch completed when the verdict is met. If omitted, one-shot watches default " +
            "to true and recurring watches default to false so monitoring continues."
    )
    val completeOnMet: Boolean? = null,

    @param:JsonPropertyDescription("When to notify: on_change (default), on_complete, every or never")
    val notify: String? = null,

    @param:JsonPropertyDescription("Tag keys that always notify, in addition to the policy")
    val notifyTags: List<String>? = null,

    @param:JsonPropertyDescription(
        "Where to look at run time (accounts, URLs, tables). Recorded with the watch and shown to " +
            "the run; it is not a credential store."
    )
    val sourceHints: String? = null,

    @param:JsonPropertyDescription("Update only: clear the stored source hints")
    val clearSourceHints: Boolean? = null,

    @param:JsonPropertyDescription(
        "Explicit tool names the run may use. Omit for the agent's read-only tools; MCP tools must " +
            "be named explicitly, which accepts that their side effects are unknown. Tools that " +
            "write files, run commands or ask the user are refused."
    )
    val allowedTools: List<String>? = null
)

data class WatchToolTag(
    @param:JsonPropertyDescription("Stable machine key, lowercase, e.g. crossed_threshold")
    val key: String? = null,

    @param:JsonPropertyDescription("Short human-readable label, e.g. 已突破")
    val label: String? = null,

    @param:JsonPropertyDescription("Optional one-line meaning of the tag")
    val description: String? = null
)
