# Swarm Configuration Quick Reference

## Top-Level Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | **yes** | Unique preset identifier (e.g. `code-review-pipeline`) |
| `title` | string | **yes** | Human-readable display title |
| `description` | string | no | What this workflow does |
| `agents` | SwarmAgentSpec[] | **yes** | Agent role definitions (min 1) |
| `tasks` | SwarmTask[] | **yes** | DAG task definitions (min 1) |
| `variables` | SwarmVariable[] | no | User input variables |

## SwarmAgentSpec

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | **yes** | Local role ID (referenced by tasks) |
| `agentDefinitionId` | string | **yes** | **Must reference an existing Agent ID** |
| `role` | string | **yes** | Descriptive role label |
| `maxIterations` | integer | no | Default 50 |
| `timeoutSeconds` | integer | no | Default 300 |
| `modelName` | string/null | no | Override model |
| `maxRetries` | integer | no | Default 2 |

## SwarmTask

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | **yes** | Unique task ID |
| `agentId` | string | **yes** for SINGLE | References SwarmAgentSpec.id |
| `promptTemplate` | string | **yes** | Jinja2 template |
| `dependsOn` | string[] | no | Upstream task IDs (forms DAG) |
| `inputFrom` | object | no | `{varName: upstreamTaskId}` routing |
| `type` | string | no | `SINGLE` (default) or `DELIBERATION` |
| `deliberation` | object | for DELIBERATION | See below |
| `maxRetries` | integer | no | Default 2 |

## DeliberationSpec (when type=DELIBERATION)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `participants` | string[] | **yes** | Agent IDs for discussion |
| `judge` | string | **yes** | Orchestrator agent ID |
| `maxRounds` | integer | no | Default 3 |
| `order` | string | no | `SEQUENTIAL` or `ROUND_ROBIN` |
| `contextTemplate` | string | no | Jinja2 deliberation topic |

## Critical Rules

1. **agentDefinitionId MUST reference an existing Agent** — use `list_resources type="agents"` to find valid IDs
2. **dependsOn forms a DAG** — no cycles allowed
3. **inputFrom routes upstream results** — `{varName: taskId}` makes `{{ varName }}` available in promptTemplate
4. **Task IDs in dependsOn/inputFrom must exist** in the tasks array
5. **Agent IDs in agentId/participants/judge must exist** in the agents array

## Example

```json
{
  "name": "code-review",
  "title": "Code Review Pipeline",
  "agents": [
    { "id": "analyzer", "agentDefinitionId": "code-analyzer", "role": "Code Analyzer" },
    { "id": "reviewer", "agentDefinitionId": "senior-reviewer", "role": "Senior Reviewer" }
  ],
  "tasks": [
    { "id": "analyze", "agentId": "analyzer", "promptTemplate": "Analyze: {{ user_input }}", "type": "SINGLE" },
    { "id": "review", "agentId": "reviewer", "promptTemplate": "Review: {{ analysis }}", "dependsOn": ["analyze"], "inputFrom": { "analysis": "analyze" }, "type": "SINGLE" }
  ],
  "variables": [{ "name": "user_input", "required": true }]
}
```
