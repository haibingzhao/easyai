package com.easy.easyai.tools.visual

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

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

[ACCESSIBILITY] SVG: role="img" with <title> and <desc> as first children. HTML: open with a visually hidden one-line summary."""

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
        patternKeys = emptyList()
    )

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.render_visual", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        return RenderVisualTool(metadata)
    }
}
