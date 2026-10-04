package com.easy.easyai.tools.visual

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolContextProjector
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

private const val RENDER_VISUAL_DESCRIPTION = """Render a self-contained HTML or SVG fragment inline in the conversation flow, for diagrams, charts, comparison cards and interactive demos.

[WHEN TO USE] When the user needs to SEE rather than READ: explaining a concept, comparing options, showing an architecture, presenting the shape of data, describing a process, building an operable demo.

[WHEN NOT TO USE]
- Plain text, a list or a markdown table says it well enough
- The user explicitly asked for a file (use the file tools instead)
- A dedicated tool already covers the need (e.g. generate_image for photographic images)

[INPUT CONTRACT] code is a BARE fragment: never include <!DOCTYPE>, <html>, <head> or <body>. SVG starts with <svg> and sets viewBox plus width="100%". HTML starts directly with a container element.

[STYLE CONTRACT]
- Outer background must stay transparent; the host provides the card surface.
- Colors ONLY through contract CSS variables, never hard-coded hex/rgb:
  base: var(--color-background-primary|secondary|tertiary), var(--color-text-primary|secondary|tertiary), var(--color-border-tertiary|secondary|primary), var(--color-background-info|danger|success|warning), var(--color-text-info|danger|success|warning), var(--font-sans|serif|mono), var(--border-radius-md|lg|xl)
  palette: var(--viz-{family}-{role}) with family in neutral|blue|green|red|amber|purple|cyan|pink|teal and role in fill|stroke|title|subtitle
- SVG presentation attributes do not resolve var(): put colors in style="fill:var(--viz-blue-fill)" or in a <style> rule.
- The same fragment must work in light AND dark theme; never assume the current mode.

[LAYOUT CONTRACT] Body text 13px, font weight only 400 or 500, never below 11px. No gradients, shadows, blur or glow. No HTML/CSS comments. No position:fixed; the root sizes itself via viewBox or width:100%.

[SVG TEXT FIT] SVG never wraps or scrolls: anything past the viewBox edges is clipped away. Keep >=16 units clear on the left, right and bottom of every <text> (bottom means baseline + descender, so a 10px text needs its baseline at viewBox height - 12). Estimate width before placing it: CJK/full-width ≈ 1.0 × font-size per character, latin/digits/spaces ≈ 0.55 ×, and a 50-character CJK sentence at 11px is already ~550 units. For titles and footnotes: split into short <tspan> lines and grow the viewBox height to fit; in an HTML fragment put the sentence in a <p> below the chart so it wraps. Never solve an overflow by shrinking the font.

[ACCESSIBILITY] SVG: role="img" with <title> and <desc> as first children. HTML: open with a visually hidden one-line summary."""

/** Static guidance for mid-narrative inline visuals (cache-stable), appended to the system prompt. */
private const val RENDER_VISUAL_SEGMENT = """
## Inline Visuals

You can show HTML/SVG fragments inline in the conversation with the `render_visual` tool: the
client renders the fragment as a visual card at the exact position where you call it.

Call it in the MIDDLE of your narrative, not once at the end:
1. Write the text that leads into the visual.
2. Call `render_visual` with the fragment for that point in the story.
3. Continue with the text that follows it, and repeat steps 1-3 for each further visual.

The tool returns only an acknowledgement — the fragment itself reaches the user through the
rendered card, so never repeat or summarize the fragment code in your text reply. Follow the
input and style contract in the tool description (bare fragment, contract variables for colors).
"""

/**
 * Strips the bulky HTML/SVG fragment out of replayed render_visual tool calls.
 *
 * The fragment is display-only: the client renders it inline and the model never needs to
 * re-read its own markup, so replaying it every turn wastes context (up to 2MB per call) and
 * can trigger premature compaction. Only the send-to-LLM path is affected — the persisted
 * message keeps the full arguments, so the UI still renders the card on history load. The
 * title is preserved so the model retains awareness of what it produced.
 *
 * Arguments shorter than `ELIDE_THRESHOLD_CHARS` are returned without being parsed: this runs
 * once per historical render_visual call on every LLM turn, so small calls are cheaper to
 * replay verbatim than to parse, and their context cost is negligible anyway.
 */
private class RenderVisualContextProjector : ToolContextProjector {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = SharedObjectMapper.instance

    override fun project(argumentsJson: String): String {
        if (argumentsJson.length <= ELIDE_THRESHOLD_CHARS) return argumentsJson
        return try {
            val node = objectMapper.readTree(argumentsJson)
            val code = node.get(CODE_ARG)
            if (node is ObjectNode && code != null && code.isString) {
                val bytes = code.stringValue().toByteArray(Charsets.UTF_8).size
                node.put(CODE_ARG, "[fragment elided from context: $bytes bytes, rendered inline in the UI]")
                objectMapper.writeValueAsString(node)
            } else {
                argumentsJson
            }
        } catch (e: Exception) {
            logger.warn("Failed to elide render_visual fragment, sending arguments as-is: {}", e.message)
            argumentsJson
        }
    }

    private companion object {
        const val CODE_ARG = "code"
        const val ELIDE_THRESHOLD_CHARS = 4 * 1024
    }
}

/**
 * Builder for [RenderVisualTool].
 *
 * Rendering happens client-side; execution only validates and acknowledges,
 * so the tool is safe to auto-allow.
 */
@Component
class RenderVisualToolBuilder : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "render_visual",
        description = RENDER_VISUAL_DESCRIPTION,
        permissionCategory = "render_visual",
        isDefaultTool = true,
        tracksFileChanges = false,
        patternKeys = emptyList(),
        systemPromptSegment = RENDER_VISUAL_SEGMENT.trimIndent(),
        promptSegmentOrder = 50,
        contextProjector = RenderVisualContextProjector()
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.render_visual", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        return RenderVisualTool(metadata)
    }
}
