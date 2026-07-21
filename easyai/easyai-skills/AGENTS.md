# easyai-skills AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Skill plugin system + command registry + sub-agent execution: discovery, loading, registration, and slash command handling.

## STRUCTURE
```
easyai-skills/src/main/kotlin/com/easy/easyai/skills/
├── SkillConfig.kt           # Skill configuration model
├── SkillDiscovery.kt        # Filesystem scanning for skill directories
├── SkillInfo.kt             # Skill metadata representation
├── SkillLoader.kt           # Loading skills from YAML/markdown files
├── SkillRegistry.kt         # Central registry + lifecycle management
├── SkillTool.kt             # Skill-to-tool adapter for agent loop
├── SkillToolBuilder.kt      # Spring wiring for SkillTool
├── a2a/                     # Agent-to-Agent protocol (AgentSkill, AgentSkillFactory)
├── command/
│   ├── CommandRegistry.kt   # Slash command registration + lookup
│   ├── CommandService.kt    # Command execution orchestration
│   ├── BuiltinCommandHandler.kt # Built-in command implementations
│   ├── CommandInfo.kt       # Command metadata
│   ├── CommandUtils.kt      # Argument parsing helpers
│   └── McpPromptProvider.kt # MCP prompt template provider
└── subagent/
    ├── SubAgentTool.kt          # Spawns child agent as tool
    ├── SubAgentToolBuilder.kt   # Spring wiring
    └── SubAgentExecutorStrategy.kt # Execution strategy interface
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Skill lifecycle | `SkillLoader` + `SkillRegistry` | Load → Register → Activate |
| Skill discovery | `SkillDiscovery` | Scan filesystem for skill directories |
| Tool integration | `SkillTool` | Adapts skills to agent tool interface |
| Slash commands | `command/CommandService` | /command execution flow |
| Sub-agents | `subagent/SubAgentTool` | Spawn child agent with depth limit |

## CONVENTIONS
- Skills discovered via filesystem → YAML/markdown parsing → registry
- Skills converted to agent tools via `SkillTool` adapter
- Commands: registered in `CommandRegistry`, executed via `CommandService`
- Sub-agents respect `maxSubAgentDepth` from agent config

## ANTI-PATTERNS
- Don't bypass `SkillRegistry` for skill activation — use the lifecycle
- Don't hardcode skill paths — rely on `SkillDiscovery`
- Don't mix skill loading with tool execution — separate concerns
- Sub-agents must not exceed depth limit
