# Agent Configuration Quick Reference

<!-- Derived from agent-spec.md — keep in sync -->

## Fields Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | **yes** | Unique identifier, `kebab-case` (1–50 chars) |
| `name` | string | **yes** | Display name (1–20 chars) |
| `promptTemplate` | string | **yes** | Jinja2 system prompt template (replaces built-in prompt) |
| `agentType` | string | no | `PRIMARY` (default) / `SUBAGENT` / `ALL` |
| `agentContext` | string | no | `CHAT` (default) / `SWARM` / `BOTH` |
| `description` | string | no | Purpose description (≤200 chars) |
| `customInstructions` | string | no | Injected into `{{ custom_instructions }}` variable |
| `toolNames` | string[] | no | Built-in tool whitelist. **Empty = ALL tools** |
| `skillNames` | string[] | no | Skill whitelist (NEVER put in toolNames) |
| `mcpConfigs` | object[] | no | MCP server bindings (NEVER put MCP tools in toolNames) |
| `subAgentIds` | string[] | no | Delegatable sub-agents (must reference existing agents) |
| `maxIterations` | integer | no | ReAct loop cap (default 50) |
| `maxSubAgentDepth` | integer | no | Delegation depth (default 1, 0=disabled) |
| `inputSchema` | object/null | no | JSON Schema for structured input (`{{ input.xxx }}`) |
| `outputSchema` | object/null | no | JSON Schema for structured output enforcement |
| `color` | string | no | UI color `#RRGGBB` |
| `enabled` | boolean | no | Active state (default true) |

## Agent Type & Context

| agentType | Behavior |
|-----------|----------|
| `PRIMARY` | Visible in agent selector |
| `SUBAGENT` | Hidden, only via delegation |
| `ALL` | Visible + delegatable |

| agentContext | Behavior |
|--------------|----------|
| `CHAT` | Uses Chat variables (tools, skills, memory, os, cwd) |
| `SWARM` | Uses Swarm variables (user_input, deliberation_history). **Restrictions**: `load_skill`, `task`, `run_swarm` unavailable in toolNames; skillNames and subAgentIds must be empty |
| `BOTH` | Compatible with both environments |

## Prompt Template Variables

| Variable | Description |
|----------|-------------|
| `custom_instructions` | From `customInstructions` field — **always include** |
| `tools` | List of {name, description} |
| `skills` | List of {name, description} |
| `sub_agents` | List of {name, description, inputSchema?} |
| `instructions` | Project AGENTS.md content |
| `input` | Structured input (requires `inputSchema`) |
| `memory` | Agent memory content |
| `os` / `cwd` | Operating system / working directory |
| `current_date_time` | Current timestamp |

## Critical Rules

1. **toolNames empty = ALL tools**; non-empty = only listed tools
2. **MCP tools via mcpConfigs** — NEVER in toolNames
3. **Skills via skillNames** — NEVER in toolNames
4. **promptTemplate MUST include `{{ custom_instructions }}`**
5. **`{{ input.xxx }}` requires inputSchema** to be set
6. **SWARM context**: when `agentContext` is `SWARM`, tools `load_skill`, `task`, `run_swarm` are unavailable — never include in toolNames; `skillNames` and `subAgentIds` must be empty

## Best Practices

- **Tool minimalism**: Only include tools the agent will actually use
- **maxIterations**: Simple Q&A → 10–20; complex coding → 50–100
- Use `{% if %}` guards for optional sections (tools, skills, memory)
- Prefer `customInstructions` over hardcoding behavioral tweaks

## Example: Code Review Agent

```json
{
  "id": "code-reviewer",
  "name": "Code Reviewer",
  "agentType": "PRIMARY",
  "description": "Reviews code for bugs and style",
  "promptTemplate": "You are a code review specialist. {{ custom_instructions }}\n\n{% if tools %}\n## Available Tools\n{% for tool in tools %}\n- {{ tool.name }}: {{ tool.description }}\n{% endfor %}\n{% endif %}",
  "toolNames": ["file_read", "code_search"],
  "maxIterations": 30
}
```

## Example: Structured Input Agent

```json
{
  "id": "translator",
  "name": "Translator",
  "agentType": "SUBAGENT",
  "promptTemplate": "Translate the following {{ input.source_lang }} text to {{ input.target_lang }}. {{ custom_instructions }}\n\n{{ input.text }}",
  "toolNames": [],
  "inputSchema": {
    "type": "object",
    "required": ["text", "source_lang", "target_lang"],
    "properties": {
      "text": { "type": "string" },
      "source_lang": { "type": "string" },
      "target_lang": { "type": "string" }
    }
  }
}
```
