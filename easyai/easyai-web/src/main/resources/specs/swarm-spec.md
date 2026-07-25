# Swarm Configuration Specification

This document describes all fields, constraints, and behavioral semantics for creating an EasyAI Swarm Preset.
A Swarm Preset defines a multi-agent DAG (Directed Acyclic Graph) workflow where tasks execute in dependency order.
Use this as the authoritative reference when generating swarm JSON configurations.

## Top-Level Fields (SwarmPreset)

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `name` | string | **yes** | — | Unique preset identifier (e.g. `code-review-pipeline`). Used in API calls to launch runs. |
| `title` | string | **yes** | — | Human-readable display title (e.g. `Code Review Pipeline`). |
| `description` | string | no | `""` | Description of what this swarm workflow does. |
| `agents` | SwarmAgentSpec[] | **yes** | — | Agent role definitions. **At least 1 required.** See **SwarmAgentSpec** below. |
| `tasks` | SwarmTask[] | **yes** | — | DAG task definitions. **At least 1 required.** See **SwarmTask** below. |
| `variables` | SwarmVariable[] | no | `[]` | User-provided input variables. See **SwarmVariable** below. |

## SwarmAgentSpec — Agent Role Definitions

Each entry defines an agent **role** within the swarm. Supports two modes:

- **Global agent**: `agentDefinitionId` is non-blank; tools, skills, system prompt, and model config are loaded from an existing `AgentDefinition` in the database.
- **Inline custom agent**: `agentDefinitionId` is blank (or omitted); `name`, `description`, `systemPrompt`, `toolNames`, and `mcpConfigs` define the agent behavior directly within the preset.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | string | **yes** | — | Unique role identifier within this swarm (e.g. `reviewer`, `writer`). Referenced by tasks. |
| `agentDefinitionId` | string | conditional | `""` | **Must reference an existing Agent ID** for global agents. Leave blank/empty for inline custom agents. |
| `role` | string | **yes** | — | Descriptive role label (e.g. `Senior Code Reviewer`). Used in event reporting. |
| `maxIterations` | integer | no | 50 | Max ReAct loop iterations for this agent per task execution. |
| `timeoutSeconds` | integer | no | 300 | Per-task execution timeout in seconds. Exceeding this aborts the task. |
| `modelName` | string or null | no | null | Override the model (e.g. `gpt-4o`). `null` = inherit from AgentDefinition or system default. |
| `maxRetries` | integer | no | 2 | Max retry attempts on task failure. Retries use exponential backoff (1s, 2s, 4s... capped at 30s). |
| `name` | string | conditional | `""` | Display name for the inline agent. **Required when `agentDefinitionId` is blank.** |
| `description` | string | no | `""` | Description of the inline agent's purpose. |
| `systemPrompt` | string | no | `""` | System prompt template (Jinja2). Becomes the agent's promptTemplate. Only used for inline agents. |
| `toolNames` | string[] | no | `[]` | Built-in tool names available to this inline agent. Only used for inline agents. **Must NOT include `load_skill`, `task`, or `run_swarm`** (unavailable in swarm runtime). |
| `mcpConfigs` | SwarmMcpBinding[] | no | `[]` | MCP server bindings available to this inline agent. Only used for inline agents. See **SwarmMcpBinding** below. |

### SwarmMcpBinding — MCP Server Binding

Defines which MCP server tools/prompts are available to an inline agent.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `serverName` | string | **yes** | — | Name of a connected MCP server (must match an existing MCP server config). |
| `toolNames` | string[] | no | `[]` | Specific tool names to expose from this server. **Empty = all tools from this server are allowed.** |
| `promptNames` | string[] | no | `[]` | Specific prompt names to expose from this server. **Empty = all prompts from this server are allowed.** |

#### mcpConfigs Example

```json
{
  "mcpConfigs": [
    { "serverName": "github", "toolNames": ["search_code", "get_file_contents"], "promptNames": [] },
    { "serverName": "trading", "toolNames": [], "promptNames": [] }
  ]
}
```

In this example:
- The `github` server exposes only `search_code` and `get_file_contents` tools.
- The `trading` server exposes **all** its tools (empty `toolNames` = no filtering).

### Key Points

- **Global mode** (`agentDefinitionId` non-blank): The referenced agent's `promptTemplate`, `toolNames`, `skillNames`, `mcpConfigs`, etc. are all loaded automatically from the database.
- **Inline mode** (`agentDefinitionId` blank): You must provide `name` and optionally `systemPrompt`, `toolNames`, `mcpConfigs` to define the agent behavior directly.
- **`id` is local to the swarm**: Tasks reference agents by this local `id`, NOT by `agentDefinitionId`.
- **`modelName` overrides the model**: Useful when you want a specific agent to use a more capable or cheaper model than the default.
- Multiple swarm agents can reference the same `agentDefinitionId` with different roles/parameters.
- **MCP tools go in `mcpConfigs`, NEVER in `toolNames`**: The `toolNames` field is only for built-in tools.
- **Swarm-unsupported tools**: `load_skill`, `task`, and `run_swarm` are NOT available in swarm runtime — never include them in `toolNames`. Skills and Sub-Agents are not supported for swarm agents.

## SwarmTask — DAG Task Definitions

Each task is a node in the execution DAG. Tasks can be **SINGLE** (one agent executes) or **DELIBERATION** (multiple agents collaborate iteratively).

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | string | **yes** | — | Unique task identifier within this swarm (e.g. `analyze-code`, `write-tests`). |
| `agentId` | string | **yes** for SINGLE | `""` | References a `SwarmAgentSpec.id`. **Ignored for DELIBERATION tasks** (use `deliberation.participants` instead). |
| `promptTemplate` | string | **yes** | — | Jinja2 template for the task prompt. See **Template Variable Resolution** below. |
| `dependsOn` | string[] | no | `[]` | Task IDs this task depends on. Forms the DAG edges. All listed tasks must complete before this task starts. |
| `inputFrom` | object | no | `{}` | Map of `{variableName: upstreamTaskId}`. Injects upstream task summaries as named variables. See **Input Routing** below. |
| `type` | string | no | `SINGLE` | enum: `SINGLE`, `DELIBERATION`. Determines execution mode. |
| `deliberation` | object or null | **yes** when `type=DELIBERATION` | null | Multi-agent deliberation configuration. See **DeliberationSpec** below. |
| `maxRetries` | integer | no | 2 | Max retry attempts for this specific task. Overrides agent-level `maxRetries`. |

### Task Type: SINGLE

A single agent executes the task prompt and produces a summary. This is the most common task type.

```json
{
  "id": "analyze-code",
  "agentId": "analyzer",
  "promptTemplate": "Analyze the following code for issues: {{ user_input }}",
  "dependsOn": [],
  "type": "SINGLE"
}
```

### Task Type: DELIBERATION

Multiple agents engage in iterative discussion, then the judge renders the final verdict. The judge acts as an Orchestrator that dynamically generates prompts for participants. See **DeliberationSpec** for configuration details.

```json
{
  "id": "review-proposal",
  "type": "DELIBERATION",
  "promptTemplate": "",
  "deliberation": {
    "participants": ["reviewer-a", "reviewer-b"],
    "judge": "senior-reviewer",
    "maxRounds": 3,
    "contextTemplate": "Review this proposal: {{ user_input }}"
  }
}
```

## DeliberationSpec — Multi-Agent Collaboration

Configures iterative multi-agent discussion within a single DAG node. The **Judge acts as an Orchestrator** that dynamically generates prompts for participants at runtime, eliminating the need for manual prompt configuration.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `participants` | string[] | **yes** | — | Agent IDs (referencing `SwarmAgentSpec.id`) that participate in the discussion. Must be defined in the `agents` array. |
| `judge` | string | **yes** | — | Agent ID that acts as orchestrator and renders the final verdict. Must be defined in the `agents` array. |
| `maxRounds` | integer | no | 3 | Maximum deliberation rounds before the judge renders the verdict. |
| `order` | string | no | `SEQUENTIAL` | Speaker ordering: `SEQUENTIAL` = fixed order every round. `ROUND_ROBIN` = rotate starting speaker each round. |
| `contextTemplate` | string | no | `""` | Jinja2 template for the deliberation context/topic. Supports workflow variables and `inputFrom` aliases. |

### Deliberation Flow

1. **Opening Round**: The Judge generates an opening prompt based on the deliberation context and participant profiles. All participants receive this prompt and produce their initial response.
2. **Subsequent Rounds**: The Judge reviews the full deliberation history and generates personalized prompts for each participant. The Judge also autonomously decides whether consensus has been reached — if so, it skips remaining rounds and proceeds to the verdict.
3. **Verdict**: The Judge reviews the complete deliberation history and renders a final verdict summarizing key conclusions and any remaining open points.

### How the Judge Orchestrator Works

The Judge agent is called multiple times during deliberation:
- **Opening generation**: Generates the initial prompt for all participants
- **Per-round generation**: Reviews history and generates personalized prompts (or signals convergence)
- **Verdict**: Produces the final summary

Participants use their own Agent system prompts (loaded from their `AgentDefinition`). No manual prompt configuration is needed beyond the optional `contextTemplate`.

### Deliberation History Format

The deliberation history is wrapped in XML tags for clear structural separation from prompt instructions:
```xml
<deliberation_history>
  <entry agent="agent-id" round="1">
    <response text in Markdown>
  </entry>
  <entry agent="agent-id-2" round="1">
    <response text in Markdown>
  </entry>
  <entry agent="agent-id" round="2">
    <response text in Markdown>
  </entry>
</deliberation_history>
```

XML tags prevent Markdown content (headings, lists, bold) in participant responses from visually blending with the surrounding prompt structure, improving LLM comprehension of data boundaries.

### Deliberation Context Template Variables

| Variable | Description |
|----------|-------------|
| Workflow variables (`{{ user_input }}`, etc.) | All swarm-level user input variables and `inputFrom` aliases are available in `contextTemplate`. |

## TeamSpec — Leader-Member Coordination

Configures iterative leader-member coordination within a single DAG node.

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `leader` | string | **yes** | — | Agent ID that coordinates task delegation and progress tracking. |
| `members` | string[] | **yes** | — | Agent IDs available for task execution (candidate pool). |
| `maxRounds` | integer | no | 5 | Maximum coordination rounds before forced conclusion. |
| `maxDynamicTasks` | integer | no | 10 | Maximum dynamic tasks the leader can create per round. |
| `roundTimeoutSeconds` | integer | no | 600 | Timeout per round in seconds. |
| `contextTemplate` | string | no | `""` | Jinja2 template for the team task context. The Leader uses this to auto-generate planning and coordination prompts at runtime. |

### Team Coordination Flow

1. **Context Rendering**: The `contextTemplate` is rendered with all available variables (user inputs, task summaries, `inputFrom` aliases). If empty, falls back to `user_input`.
2. **Planning Round (Round 1)**: The Leader receives an auto-generated planning prompt containing the rendered context, available member profiles, and a structured JSON output format. It analyzes the task and delegates work.
3. **Member Execution**: Members execute their assignments in parallel. Each member is injected with an `escalate` tool for explicit escalation signaling.
4. **Coordination Round (Round 2+)**: The Leader receives an auto-generated coordination prompt with progress summaries, escalation history, delegation history, and previous analyses. It reviews progress and decides next steps.
5. **Convergence**: Leader signals completion by setting `isComplete: true` in its structured decision output.

### EscalationTool

Each team member is injected with an `escalate` tool at execution time. When a member is blocked, lacks resources, or cannot complete its task, it calls this tool with a `reason` parameter. The escalation is recorded in a thread-safe `AtomicReference` that `TeamTaskExecutor` reads after execution completes.

An `EscalationCompletionCheck` provides a soft guarantee: if a member’s output contains escalation signal words (BLOCKED, UNABLE, ESCALATE, NEED_HELP) but the tool was not called, the agent loop continues with a nudge prompt asking the member to formally escalate via the tool (max 1 retry).

### Team Template Variables

| Variable | Description |
|----------|-------------|
| Workflow variables (`{{ user_input }}`, etc.) | All swarm-level user input variables and `inputFrom` aliases are available in `contextTemplate`. |

## SwarmVariable — User Input Variables

Variables defined here are requested from the user when launching a swarm run. Their values are available in ALL task prompt templates.

**Convention**: Always define a required variable named `user_input` as the primary entry point. The runtime uses `user_input` as the fallback UserMessage when a task has no promptTemplate, and TEAM leaders reference it directly. Do not use alternative names (e.g. "userProblem", "query").

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `name` | string | **yes** | — | Variable name used in Jinja2 templates as `{{ name }}`. |
| `description` | string | no | `""` | Description shown to the user when requesting input. |
| `required` | boolean | no | `false` | Whether the user must provide this variable. |
| `defaultValue` | string or null | no | null | Default value when the user doesn't provide one. |

### Example

```json
{
  "variables": [
    { "name": "user_input", "description": "The code or text to process", "required": true },
    { "name": "language", "description": "Target programming language", "required": false, "defaultValue": "kotlin" }
  ]
}
```

## DAG Execution Engine

### Topological Execution

1. **Validation**: The task graph is validated using Kahn's algorithm. Cycles cause immediate failure.
2. **Layer Computation**: Tasks are grouped into topological layers. Tasks within a layer have no inter-dependencies.
3. **Layer-by-Layer Execution**: Layers execute sequentially. Within each layer, tasks execute **in parallel** (bounded by `maxConcurrency`, default 4).
4. **Dependency Resolution**: A task starts only after ALL tasks in its `dependsOn` list complete successfully.
5. **Failure Propagation**: If a task fails (all retries exhausted), all downstream tasks (tasks that depend on it, directly or transitively) are marked as `BLOCKED` and skipped.

### Template Variable Resolution

When rendering a task's `promptTemplate`, the following variable sources are merged (later sources override earlier ones):

1. **User Variables** (`variables`): All swarm-level user input variables.
2. **Task Summaries**: Every completed upstream task's summary is available as a variable keyed by its task `id`.
3. **Input From** (`inputFrom`): Named aliases for specific upstream task summaries.

#### inputFrom Example

```json
{
  "id": "synthesize",
  "agentId": "synthesizer",
  "promptTemplate": "Analysis: {{ analysis_result }}\nTests: {{ test_result }}\nCombine these into a report.",
  "dependsOn": ["analyze", "test"],
  "inputFrom": {
    "analysis_result": "analyze",
    "test_result": "test"
  }
}
```

Here:
- `{{ analysis_result }}` resolves to the summary of task `analyze`
- `{{ test_result }}` resolves to the summary of task `test`
- `{{ analyze }}` and `{{ test }}` are ALSO available (task summaries are always accessible by their task ID)

### Task Summaries Truncation

Task summaries are truncated to prevent context overflow when many upstream tasks feed into a downstream task. Very long summaries are cut with a `[Truncated]` marker.

## Complete Example: Code Review Pipeline

```json
{
  "name": "code-review-pipeline",
  "title": "Code Review Pipeline",
  "description": "Analyzes code, writes tests, and produces a review report",
  "agents": [
    {
      "id": "analyzer",
      "agentDefinitionId": "code-analyzer",
      "role": "Code Analyzer",
      "maxIterations": 30,
      "timeoutSeconds": 180
    },
    {
      "id": "test-writer",
      "agentDefinitionId": "test-engineer",
      "role": "Test Engineer",
      "maxIterations": 40,
      "timeoutSeconds": 240
    },
    {
      "id": "reviewer",
      "agentDefinitionId": "senior-reviewer",
      "role": "Senior Reviewer",
      "maxIterations": 20,
      "timeoutSeconds": 120
    }
  ],
  "tasks": [
    {
      "id": "analyze",
      "agentId": "analyzer",
      "promptTemplate": "Analyze this code for bugs and improvements:\n{{ user_input }}",
      "dependsOn": [],
      "type": "SINGLE"
    },
    {
      "id": "write-tests",
      "agentId": "test-writer",
      "promptTemplate": "Write tests for this code:\n{{ user_input }}\n\nAnalysis notes:\n{{ analysis }}",
      "dependsOn": ["analyze"],
      "inputFrom": { "analysis": "analyze" },
      "type": "SINGLE"
    },
    {
      "id": "final-review",
      "agentId": "reviewer",
      "promptTemplate": "Produce a final review report.\n\nAnalysis:\n{{ analysis }}\n\nTests:\n{{ tests }}",
      "dependsOn": ["analyze", "write-tests"],
      "inputFrom": { "analysis": "analyze", "tests": "write-tests" },
      "type": "SINGLE"
    }
  ],
  "variables": [
    { "name": "user_input", "description": "Code to review", "required": true }
  ]
}
```

### Execution Flow for the Example

```
Layer 1: [analyze]        ← no dependencies, runs first
Layer 2: [write-tests]    ← depends on analyze, runs after analyze completes
Layer 3: [final-review]   ← depends on analyze + write-tests, runs last
```

## Complete Example: Deliberation Swarm

```json
{
  "name": "proposal-review",
  "title": "Multi-Perspective Proposal Review",
  "description": "Two reviewers debate a proposal, then a judge delivers the verdict",
  "agents": [
    {
      "id": "security-reviewer",
      "agentDefinitionId": "security-expert",
      "role": "Security Expert"
    },
    {
      "id": "perf-reviewer",
      "agentDefinitionId": "performance-expert",
      "role": "Performance Expert"
    },
    {
      "id": "judge-agent",
      "agentDefinitionId": "tech-lead",
      "role": "Tech Lead"
    }
  ],
  "tasks": [
    {
      "id": "deliberate",
      "type": "DELIBERATION",
      "promptTemplate": "",
      "deliberation": {
        "participants": ["security-reviewer", "perf-reviewer"],
        "judge": "judge-agent",
        "maxRounds": 3,
        "order": "SEQUENTIAL",
        "contextTemplate": "Review this proposal from multiple perspectives:\n{{ user_input }}"
      }
    }
  ],
  "variables": [
    { "name": "user_input", "description": "The proposal to review", "required": true }
  ]
}
```

## Best Practices

1. **Design DAGs for parallelism**: Independent tasks should have no dependency between them so they can execute in parallel.
2. **Use `inputFrom` for clarity**: Named variable aliases make templates more readable than relying on task IDs directly.
3. **Set realistic `timeoutSeconds`**: Complex tasks with many tool calls need more time. Default 300s may be too short for large code analysis.
4. **Use the `contextTemplate` for deliberation topic**: Provide clear context about what should be deliberated — the Judge orchestrator uses this to generate effective prompts.
5. **Keep prompt templates focused**: Each task should have a clear, specific prompt. Avoid asking one task to do too many things.
6. **Reference existing agents**: `agentDefinitionId` must point to an agent that already exists. Create agents first, then build swarms.
7. **Use `variables` for user input**: Define all expected user inputs as variables rather than hardcoding them in prompts.
8. **Test DAG logic**: Ensure `dependsOn` references are correct. A circular dependency will fail validation at launch time.
9. **Set `maxRetries` per task**: Critical tasks (e.g., final synthesis) may warrant higher `maxRetries` values.
10. **Consider token costs**: Each deliberation round multiplies API calls by the number of participants. Use `maxRounds` conservatively.
