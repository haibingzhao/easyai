# Agent Configuration Quick Reference

<!-- Derived from agent-spec.md — keep in sync -->

## Fields Reference

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | **yes** | Unique identifier, `kebab-case` (1–50 chars) |
| `name` | string | **yes** | Display name (1–20 chars) |
| `promptTemplate` | string | **yes** | Jinja2 system prompt template — role/workflow only; runtime sections are appended automatically |
| `agentType` | string | no | `PRIMARY` (default) / `SUBAGENT` / `TEAM` / `ALL` |
| `agentContext` | string | no | `CHAT` (default) / `SWARM` / `BOTH` |
| `description` | string | no | Purpose description (≤200 chars) |
| `customInstructions` | string | no | Optional free text, appended to the system prompt automatically |
| `toolNames` | string[] | no | Built-in tool whitelist. **Empty = ALL tools** |
| `skillNames` | string[] | no | Skill whitelist (NEVER put in toolNames) |
| `mcpConfigs` | object[] | no | MCP server bindings (NEVER put MCP tools in toolNames) |
| `subAgentIds` | string[] | no | Delegatable sub-agents (must reference existing agents) |
| `customSubAgents` | object[] | no | Inline custom sub-agents (self-contained, no external reference needed) |
| `memberIds` | string[] | no | Team members (**required when agentType=TEAM**, must reference existing non-TEAM agents) |
| `customMembers` | object[] | no | Inline custom team members (self-contained, no external reference needed) |
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
| `SUBAGENT` | Hidden, only via delegation. `task` and `run_swarm` are blocked at runtime — do NOT add them to `toolNames` |
| `TEAM` | Team leader: coordinates member agents via delegate/wait/resume tools. Requires `memberIds`. `toolNames` is optional — the leader may keep its own tools (e.g. read/search) in addition to the auto-injected coordination tools. The coordination tools are auto-injected (do NOT add to `toolNames`); `task` is NOT usable (empty sub-agent whitelist) |
| `ALL` | Visible + delegatable |

| agentContext | Behavior |
|--------------|----------|
| `CHAT` | Uses Chat variables (tools, skills, instructions, os, cwd) |
| `SWARM` | Uses Swarm variables (user_input, deliberation_history). **Restrictions**: `load_skill`, `task`, `run_swarm` unavailable in toolNames; skillNames and subAgentIds must be empty |
| `BOTH` | Compatible with both environments |

## Prompt Template

`promptTemplate` defines the agent's role, goals, workflow, and output rules. Write plain text
(or Jinja2 only when the prompt needs a runtime variable such as `{{ input.xxx }}` or `{{ os }}`);
the runtime renders it as the base system prompt and appends these sections automatically when the
template does not reference their variable:

| Auto-appended variable | Description |
|------------------------|-------------|
| `skills` | Authorized skills (list of {name, description}) |
| `sub_agents` | Delegatable sub-agents (list of {name, description, inputSchema?}) |
| `team_members` | Team member agents (TEAM type) |
| `instructions` | Project AGENTS.md content |
| `custom_instructions` | `customInstructions` field |

Tools are provided to the model as native tool definitions — do NOT list them in the template.
There is no `memory` template variable (memory is retrieved through the memory tools).
`input` is only available when `inputSchema` is set.

## InlineAgentSpec Schema

Each entry in `customSubAgents` / `customMembers`:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | **yes** | Unique name within the parent agent |
| `description` | string | no | Purpose description |
| `systemPrompt` | string | no | Jinja2 system prompt for this inline agent |
| `toolNames` | string[] | no | Tool whitelist (empty = all tools) |
| `skillNames` | string[] | no | Skill whitelist |
| `mcpConfigs` | object[] | no | MCP server bindings |

## Critical Rules

1. **toolNames empty = ALL tools**; non-empty = only listed tools
2. **MCP tools via mcpConfigs** — NEVER in toolNames
3. **Skills via skillNames** — NEVER in toolNames
4. **promptTemplate is role/workflow only** — NEVER write tool/skill/sub-agent/member/project-instruction/memory sections or `{{ custom_instructions }}`; the runtime injects them
5. **`{{ input.xxx }}` requires inputSchema** to be set
6. **SWARM context**: when `agentContext` is `SWARM`, tools `load_skill`, `task`, `run_swarm` are unavailable — never include in toolNames; `skillNames` and `subAgentIds` must be empty
7. **TEAM agent requires memberIds** (≥2 recommended); members must be existing non-TEAM agents; the member list is injected automatically — focus the promptTemplate on coordination strategy, not task execution

## Best Practices

- **Tool minimalism**: Only include tools the agent will actually use
- **maxIterations**: Simple Q&A → 10–20; complex coding → 50–100
- Keep `promptTemplate` to role, workflow, and output rules — runtime sections are appended automatically
- Reference a runtime variable only when the prompt must control its placement (advanced use)

## Example: Code Review Agent

```json
{
  "id": "code-reviewer",
  "name": "Code Reviewer",
  "agentType": "PRIMARY",
  "description": "Reviews code for bugs and style",
  "promptTemplate": "You are a code review specialist. Review the given code for bugs, style, and performance; report findings grouped by file with concrete line references.",
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
  "promptTemplate": "Translate the following {{ input.source_lang }} text to {{ input.target_lang }}.\n\n{{ input.text }}",
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

## Example: Team Agent

```json
{
  "id": "fullstack-team",
  "name": "Fullstack Team",
  "agentType": "TEAM",
  "description": "Coordinates backend and frontend agents for full-stack tasks",
  "promptTemplate": "You are a team leader coordinating full-stack development. Plan the work, delegate each part to the right member, and synthesize their results into one answer.",
  "memberIds": ["backend-dev", "frontend-dev"],
  "toolNames": [],
  "maxIterations": 30
}
```

## Example: Agent with Inline Custom Sub-Agents

```json
{
  "id": "research-assistant",
  "name": "Research Assistant",
  "agentType": "PRIMARY",
  "description": "Delegates research tasks to specialized inline agents",
  "promptTemplate": "You are a research coordinator. Decompose the request, delegate focused research, and synthesize the findings into a single answer.",
  "toolNames": ["web_search"],
  "customSubAgents": [
    {
      "name": "summarizer",
      "description": "Summarizes long documents into key points",
      "systemPrompt": "You are a summarization expert. Condense the given text into bullet points.",
      "toolNames": ["file_read"],
      "skillNames": [],
      "mcpConfigs": []
    },
    {
      "name": "fact-checker",
      "description": "Verifies claims against web sources",
      "systemPrompt": "You verify factual claims. Use web search to confirm or refute.",
      "toolNames": ["web_search", "file_read"],
      "skillNames": [],
      "mcpConfigs": []
    }
  ],
  "maxSubAgentDepth": 1,
  "maxIterations": 30
}
```
