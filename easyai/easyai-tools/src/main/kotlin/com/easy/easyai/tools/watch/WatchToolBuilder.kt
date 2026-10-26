package com.easy.easyai.tools.watch

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.watch.WatchService
import org.springframework.stereotype.Component

private const val WATCH_TOOL_DESCRIPTION = """Create and manage watches: standing intents that are checked
later, automatically, and report a conclusion with tags.

Use a watch when the user asks to be told something later, or when you would otherwise end the reply
with a future check you cannot perform now. Do not create one for work you can finish in this turn,
and do not invent follow-ups the user did not ask for.

Trigger forms — use exactly one:
- "within": relative duration such as 30m, 2h, 3d. Prefer this for anything soon.
- "dayOffset" + "time": 0 = today, 1 = tomorrow, at a local HH:mm.
- "weekday" + "time" (without "every"): the next occurrence of that weekday, one-shot.
- "every": daily, weekly or monthly (+ "time"; weekly needs "weekday"; monthly may take "dayOfMonth").
- "at": absolute ISO-8601 date-time.
Never compute today's date yourself — relative forms are resolved by the server. The result contains
the absolute next run; report that time to the user.

mode="check" (default) gathers evidence with the agent's read-only tools and returns a conclusion;
define "tags" as the vocabulary findings may use. mode="remind" just delivers "reminder" text.
"until" (YYYY-MM-DD) sets the last day; when it passes, a review decides whether to renew, complete
or expire the watch. "sourceHints" records where to look (account, URL, table) and is shown to every
run. "allowedTools" narrows the read-only tools; omit it for the agent's defaults, and name MCP tools
explicitly if they are needed. The sandboxed `calc` tool is always available so a large result can be
analyzed later; do not list it.

Lifecycle: action="list" shows watches and their last finding; "update" edits or renews one (pass
"watchId" plus the fields to change; a completed or expired watch needs a new schedule); "pause",
"resume" and "cancel" take "watchId". When the user changes a time, edit the existing watch instead
of creating a duplicate."""

private const val WATCH_PROMPT_SEGMENT = """Future commitments: never end a reply with a promise to check
something later. Either do it now or create a watch. Create one only when the user asked to follow up
or you owe a check that cannot be done in this turn — not for hypotheticals. When the user moves a
time, changes the condition or drops the follow-up, update or cancel the existing watch instead of
creating another, and state the next run time the tool returned."""

/**
 * Builder for [WatchTool].
 *
 * Only the interactive top-level chat may create a watch: a sub-agent, a swarm worker or an
 * unattended watch run has no user to confirm what is being promised, and a watch created there
 * would outlive the request that justified it.
 */
@Component
class WatchToolBuilder(
    // Optional on purpose: easyai-tools is shared by hosts that wire no watch service (no R2DBC),
    // and a hard dependency would fail their context instead of leaving the tool unavailable.
    private val watchService: WatchService? = null
) : ToolBuilder {

    override val metadata = ToolMetadata(
        name = "watch",
        description = WATCH_TOOL_DESCRIPTION,
        permissionCategory = "watch",
        uiRenderer = "watch",
        isDefaultTool = true,
        systemPromptSegment = WATCH_PROMPT_SEGMENT,
        promptSegmentOrder = 60
    )

    override val mainAgentOnly: Boolean = true

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.watch", "*", PermissionAction.ALLOW)
    )

    override suspend fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val service = watchService ?: return null
        if (context.unattended) return null
        if (context.parentAgentId != null || context.swarmRunId != null) return null
        if (context.sessionId.isNullOrBlank() || context.userId.isNullOrBlank()) return null
        return WatchTool(metadata, service)
    }
}
