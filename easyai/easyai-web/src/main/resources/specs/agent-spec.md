# Agent Configuration Specification

This document describes all fields, constraints, and behavioral semantics for creating an EasyAI Agent.
Use this as the authoritative reference when generating agent JSON configurations.

## Fields Reference

| Field | Type | Required | Default | Constraints | Description |
|-------|------|----------|---------|-------------|-------------|
| `id` | string | **yes** | — | 1–50 chars | Unique agent identifier. Use `kebab-case` (e.g. `code-reviewer`). Immutable after creation. |
| `name` | string | **yes** | — | 1–20 chars | Human-readable display name shown in the UI. |
| `agentType` | string | no | `PRIMARY` | enum: `PRIMARY`, `SUBAGENT`, `TEAM`, `ALL` | Controls visibility. See **Agent Type Semantics** below. |
| `agentContext` | string | no | `CHAT` | enum: `CHAT`, `SWARM`, `BOTH` | Controls execution environment compatibility. See **Agent Context Semantics** below. |
| `description` | string | no | null | ≤ 200 chars | Short description of the agent's purpose. |
| `promptTemplate` | string | **yes** | — | Valid Jinja2 | System prompt template. When provided, it **fully replaces** the built-in system prompt. See **Prompt Template** below. |
| `customInstructions` | string | no | null | — | Free-text instructions injected into the template variable `custom_instructions`. |
| `toolNames` | string[] | no | `[]` | Each name must exist in the available tools list | Tool whitelist. **Empty array = ALL tools available.** Non-empty = only listed tools. |
| `subAgentIds` | string[] | no | `[]` | Each ID must reference an existing agent | Sub-agents this agent can delegate tasks to. |
| `memberIds` | string[] | no | `[]` | Each ID must reference an existing non-TEAM agent | Team members. **Required when `agentType=TEAM`** (≥2 recommended). Members execute the work; the TEAM leader only coordinates. |
| `skillNames` | string[] | no | `[]` | Each name must exist in the available skills list | Skill whitelist. Skills are discovered from markdown files. |
| `mcpConfigs` | object[] | no | `[]` | `serverName` must be a connected MCP server | MCP server bindings. See **MCP Configs** below. |
| `commandNames` | string[] | no | `[]` | — | Command whitelist. |
| `maxIterations` | integer | no | 50 | ≥ 1 | Maximum ReAct loop iterations per conversation turn. Too low = incomplete complex tasks. Too high = wasted tokens. |
| `maxSubAgentDepth` | integer | no | 1 | ≥ 0 | Max nesting depth for sub-agent delegation. `0` = no sub-agents allowed. `1` = one level of delegation. `2+` = multi-level nesting. |
| `color` | string | no | null | Pattern: `^#[0-9A-Fa-f]{6}$` | UI display color (e.g. `#3B82F6`). Purely cosmetic. |
| `enabled` | boolean | no | `true` | — | Whether the agent is active and selectable. |
| `instructionsEnabled` | boolean | no | `true` | — | When `false`, project-level instructions (AGENTS.md) are NOT loaded into this agent's context. |
| `inputSchema` | object or null | no | null | Valid JSON Schema | Structured input validation. Applied only to the top-level (root) agent at the start of the run. |
| `outputSchema` | object or null | no | null | Valid JSON Schema | Structured output enforcement. Injected into the model's `response_format` for supported providers (OpenAI, Anthropic). Also appended to the system prompt as an `## Output Format` section. |

## Agent Type Semantics

| Value | Behavior |
|-------|----------|
| `PRIMARY` | Visible in the user-facing agent selector. Cannot be used as a sub-agent delegation target. |
| `SUBAGENT` | Hidden from the user-facing selector. Can ONLY be invoked via delegation from another agent. `task` and `run_swarm` are blocked at runtime for sub-agents — do NOT add them to `toolNames`. |
| `TEAM` | Team leader visible in the agent selector. Coordinates member agents (from `memberIds`) via `delegate_to_member` / `wait_for_member_events` / `resume_member` tools. Members must be existing non-TEAM agents; nested teams are not allowed. `toolNames` is optional — the leader may keep its own tools (e.g. read/search) in addition to the auto-injected coordination tools. The three coordination tools are auto-injected (do NOT add them to `toolNames`); `task` is NOT usable for a TEAM leader (its sub-agent whitelist is always empty). |
| `ALL` | Visible in the selector AND available as a delegation target. Use for versatile agents. |

## Agent Context Semantics

Controls where the agent's `promptTemplate` can be correctly rendered. Orthogonal to `agentType`.

| Value | Behavior |
|-------|----------|
| `CHAT` | Prompt uses only PromptContext variables (`tools`, `skills`, `memory`, `os`, `cwd`, `model_id`, etc.). Usable in Chat sessions. In Swarm workflows, the task promptTemplate is skipped and the agent's own system prompt drives behavior. |
| `SWARM` | Prompt may use Swarm-specific variables (`user_input`, `deliberation_history`, `rounds`, upstream task summaries). Designed for Swarm workflow tasks. Not shown in Chat agent selector. **Restrictions**: `load_skill`, `task`, `run_swarm` are unavailable in swarm runtime — never include in `toolNames`; `skillNames` and `subAgentIds` must be empty (Skills and Sub-Agents unsupported). |
| `BOTH` | Compatible with both Chat and Swarm environments. Shown in Chat selector and supports custom task promptTemplate in Swarm. |

## Prompt Template

`promptTemplate` is a **Jinja2 template** that generates the agent's system prompt at runtime.

### Available Template Variables

When writing a `promptTemplate`, these variables are available via the Jinja2 rendering context:

| Variable | Type | Source | Description |
|----------|------|--------|-------------|
| `custom_instructions` | string | `customInstructions` field | Free-text instructions set by the user. Always include this in your template. |
| `protocol` | string | Runtime model config | Model protocol identifier, e.g. `openai`, `anthropic`. Useful for protocol-specific instructions. |
| `model_id` | string | Runtime model config | Active model identifier, e.g. `gpt-4o`, `claude-sonnet-4-20250514`. |
| `tools` | list of objects | Tool registry + whitelist | Available tools. Each object has `name` (string) and `description` (string). |
| `skills` | list of objects | Skill registry + whitelist | Available skills. Each object has `name` (string) and `description` (string). |
| `sub_agents` | list of objects | Agent store + whitelist | Delegatable sub-agents. Each object has `name`, `description`, and optionally `inputSchema`. |
| `team_members` | list of objects | Agent store + `memberIds` | Team member agents (TEAM agents only). Each object has `name` and `description`. |
| `instructions` | list of objects | AGENTS.md files (when `instructionsEnabled`) | Project-level instructions. Each object has `name` (string), `content` (string), and `source` (enum: `PROJECT` or `SUBDIR`). |
| `project` | object | Project configuration | Project metadata map. May contain project-specific context. |
| `os` | string | `System.getProperty("os.name")` | Operating system name, e.g. `Mac OS X`, `Linux`, `Windows 11`. |
| `cwd` | string | Session project path | Current working directory absolute path. Empty string when no project is set. |
| `memory` | string | Memory store (loaded once per session) | Formatted memory content including agent memory index and references. Empty string when no memory. |
| `input` | object | API request `inputData` (validated by `inputSchema`) | Structured input variables. Access fields via `{{ input.field_name }}`. Empty object when no input provided. |

### Variable Details

#### `tools` — Tool List

Each item in the `tools` list is a map with two keys:
```jinja2
{% for tool in tools %}
- `{{ tool.name }}`: {{ tool.description }}
{% endfor %}
```

#### `skills` — Skill List

Same structure as `tools`:
```jinja2
{% for skill in skills %}
- `{{ skill.name }}`: {{ skill.description }}
{% endfor %}
```

#### `sub_agents` — Sub-Agent List

Each item has `name`, `description`, and optionally `inputSchema`:
```jinja2
{% for sa in sub_agents %}
- `{{ sa.name }}`: {{ sa.description }}
  {% if sa.inputSchema %}
  Input Schema: {{ sa.inputSchema }}
  {% endif %}
{% endfor %}
```

#### `instructions` — Project Instructions (AGENTS.md)

Each item has `name` (file name), `content` (file content), and `source` (where it was loaded from):
```jinja2
{% for instr in instructions %}
## {{ instr.name }}
{{ instr.content }}
{% endfor %}
```

#### `input` — Structured Input Data

When the agent has an `inputSchema` and the API request provides `inputData`, the validated data is available as the `input` object. This is the primary way to pass structured data into a custom prompt template:
```jinja2
Analyze the following {{ input.language }} code:

```{{ input.language }}
{{ input.code }}
```

Focus areas: {{ input.focus_areas | join(", ") }}
```

When `inputSchema` is not set or no `inputData` was provided, `input` is an empty object and `{{ input.anything }}` renders as empty string.

#### `agent` — Agent Metadata

A map containing the agent's own metadata (id, name, etc.):
```jinja2
You are {{ agent.name }}, a specialized assistant.
```

### Example Templates

**Simple agent with custom instructions:**
```jinja2
You are a helpful assistant. {{ custom_instructions }}

OS: {{ os }}
Working directory: {{ cwd }}
```

**Full-featured agent with tools, skills, and instructions:**
```jinja2
You are {{ custom_instructions }}

{% if tools %}
## Available Tools
{% for tool in tools %}
- **{{ tool.name }}**: {{ tool.description }}
{% endfor %}
{% endif %}

{% if skills %}
## Available Skills
{% for skill in skills %}
- **{{ skill.name }}**: {{ skill.description }}
{% endfor %}
Use the `load_skill` tool to load a skill when a task matches its description.
{% endif %}

{% if sub_agents %}
## Available Sub-Agents
You can delegate tasks using the `task` tool.
{% for sa in sub_agents %}
- `{{ sa.name }}`: {{ sa.description }}
{% endfor %}
{% endif %}

{% if instructions %}
## Project Instructions
{% for instr in instructions %}
{{ instr.content }}
{% endfor %}
{% endif %}

{% if memory %}
## Memory
{{ memory }}
{% endif %}
```

**Structured input agent (requires `inputSchema`):**
```jinja2
You are a code review specialist. {{ custom_instructions }}

## Task
Review the following {{ input.language }} code for bugs, performance issues, and style violations:

```{{ input.language }}
{{ input.code }}
```

{% if input.context %}
Additional context: {{ input.context }}
{% endif %}
```

### Important Notes

- When `promptTemplate` is `null`, the built-in `SystemPromptBuilder` generates a comprehensive default prompt with all available sections automatically included.
- When `promptTemplate` is provided, it **completely replaces** the default prompt — you are responsible for including all necessary sections (tools, skills, sub-agents, instructions, memory, etc.).
- Always include `{{ custom_instructions }}` somewhere in the template to honor the `customInstructions` field.
- Use Jinja2 conditionals (`{% if %}`) to handle optional sections gracefully — many variables may be empty depending on the agent configuration.
- `{{ input.xxx }}` variables are only populated when `inputSchema` is set AND the API request includes matching `inputData`.

## MCP Configs

Each entry in `mcpConfigs` binds an MCP (Model Context Protocol) server to the agent:

```json
{
  "serverName": "browser-use",
  "toolNames": ["navigate_page", "click"],
  "promptNames": []
}
```

| Sub-field | Type | Required | Description |
|-----------|------|----------|-------------|
| `serverName` | string | **yes** | Must match a connected MCP server name |
| `toolNames` | string[] | no | Tool whitelist from this server. Empty = all tools allowed. |
| `promptNames` | string[] | no | Prompt whitelist from this server. Empty = all prompts allowed. |

## Behavioral Rules

### Iteration Loop

The agent follows a ReAct (Reason → Act → Observe) loop:
1. Receive user message
2. Think and decide on action
3. Call tool(s) or respond
4. If tools were called, observe results and go to step 2
5. `maxIterations` caps the total number of step-2 iterations

### Sub-Agent Delegation

- The agent can delegate tasks to agents listed in `subAgentIds` via the sub-agent tool.
- `maxSubAgentDepth` controls how deep the delegation chain can go:
  - `0`: sub-agent tool is disabled regardless of `subAgentIds`
  - `1`: this agent can delegate, but its sub-agents cannot delegate further
  - `2+`: allows multi-level delegation chains

### Tool Whitelist Semantics

- `toolNames: []` → ALL registered tools are available (no filtering)
- `toolNames: ["file_read", "file_write"]` → ONLY `file_read` and `file_write` are available
- Skills listed in `skillNames` are loaded as additional tools
- MCP tools from `mcpConfigs` are added to the available tool set

### Output Schema Enforcement

When `outputSchema` is set:
1. **API-level enforcement**: Models supporting `StructuredOutputChatOptions` (OpenAI, Anthropic) will produce JSON conforming to the schema at the API level.
2. **Prompt-level enforcement**: An `## Output Format` section with the schema is appended to the system prompt for ALL models.
3. The schema should be a valid JSON Schema object describing the expected output structure.

### Input Schema

`inputSchema` defines a JSON Schema that validates structured input data passed to the agent at runtime.

**When is validation triggered?**
- Validation runs **before the ReAct loop starts**, only when `inputVariables` are explicitly provided in the API request.
- Follow-up messages (without `inputData`) **skip validation** — only the first call is validated.
- **Only applies to top-level agents** — sub-agents invoked via delegation do NOT have their `inputSchema` validated (they receive input via the LLM's prompt text instead).

**What happens on validation failure?**
- The agent immediately returns an error message with details and stops (endReason = `input_schema_validation_failed`).
- No tools are called, no LLM inference occurs.

**How does inputSchema interact with promptTemplate?**
- Validated input variables are injected into the Jinja2 template context as the `input` object.
- In your `promptTemplate`, access them via `{{ input.field_name }}`.
- Example: if `inputSchema` requires `{"code": "...", "language": "..."}`, the template can use `{{ input.code }}` and `{{ input.language }}`.

**How does inputSchema affect sub-agent delegation?**
- When a sub-agent has an `inputSchema`, the parent agent's system prompt automatically includes the schema definition.
- The parent agent is instructed to provide matching `inputData` when calling the sub-agent's `task` tool.
- This enables type-safe structured delegation between agents.

**Example inputSchema:**
```json
{
  "type": "object",
  "required": ["code", "language"],
  "properties": {
    "code": { "type": "string", "description": "The source code to analyze" },
    "language": { "type": "string", "description": "Programming language", "enum": ["kotlin", "java", "python", "typescript"] }
  }
}
```

**When to use inputSchema:**
- Agents designed as specialized processors (code review, translation, analysis) that receive structured input from other agents or external systems.
- Agents used as sub-agent delegation targets where the parent needs clear contract for what data to provide.
- NOT needed for conversational agents that receive free-form user messages.

## Best Practices

1. **Keep `id` descriptive and short**: Use `kebab-case` like `code-reviewer`, `test-writer`, `research-agent`.
2. **Minimalism for tools, MCP, and skills — only include what the task requires, not more**:
   - Every tool, MCP server, and skill listed in the config is injected into the agent's system prompt, consuming context window and adding decision noise.
   - **`toolNames`**: Only list tools the agent will actually use. A code-review agent that only reads code should have `["file_read", "code_search"]`, not `["file_read", "file_write", "shell_exec", "code_search", "web_fetch"]`. Leaving `toolNames: []` (all tools) is appropriate only for general-purpose agents.
   - **`mcpConfigs`**: Only bind MCP servers whose tools are relevant. If the agent never browses the web, don't add `browser-use`. When binding, whitelist only the needed tools via the nested `toolNames` — don't expose all 20 tools from a server when only 2 are needed.
   - **`skillNames`**: Only list skills that match the agent's domain. A translation agent doesn't need a `code-generation` skill.
   - **Rule of thumb**: If you can't describe a concrete scenario where the agent would use a tool/MCP/skill during its task, remove it. Fewer tools → faster reasoning → fewer wrong tool calls → lower token cost.
3. **Set appropriate `maxIterations`**: Simple Q&A agents can use 10–20; complex coding agents may need 50–100.
4. **Use `agentType: SUBAGENT`** for specialized workers (e.g., a linter agent) that should only be invoked via delegation.
5. **Use `agentType: TEAM`** to coordinate multiple existing agents: set `memberIds` referencing existing non-TEAM agents (discover via `list_resources`). The delegate/wait/resume coordination tools are auto-injected, so `toolNames` only needs the leader's own extra tools (e.g. read/search) if any — leave it empty when the leader purely coordinates. Focus the `promptTemplate` on coordination strategy with `{% if team_members %}` member listing.
6. **Always include `{{ custom_instructions }}`** in your `promptTemplate` so users can add runtime tweaks.
7. **Prefer `customInstructions` over hardcoding** behavioral tweaks into the prompt — it's easier to iterate on.
8. **Set `instructionsEnabled: false`** for agents that should ignore project-level AGENTS.md (e.g., generic utility agents).
9. **Use `outputSchema`** when you need guaranteed JSON structure (e.g., for downstream parsing or UI rendering).
10. **Use `inputSchema` for structured agents**: When an agent is a delegation target or processes structured data, define `inputSchema` to enforce a clear contract and use `{{ input.field }}` in the template.
