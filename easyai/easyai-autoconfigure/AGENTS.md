# easyai-autoconfigure AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
8 Spring Boot auto-configuration sub-modules, each with @ConditionalOnClass guards + EnvironmentPostProcessors.

## STRUCTURE

```
easyai-autoconfigure/
├── easyai-core-autoconfigure/          # Core beans: AgentService, SessionManager, ToolExecutionEngine, EasyAiProperties
├── easyai-web-autoconfigure/           # Web layer: controllers, ChatStreamService, SecurityConfig
├── easyai-observability-autoconfigure/ # Micrometer, OTel SDK, tracing listeners
├── easyai-openai-autoconfigure/        # OpenAI ChatModelFactory + OptionsBuilder
├── easyai-anthropic-autoconfigure/     # Anthropic ChatModelFactory + OptionsBuilder
├── easyai-r2dbc-autoconfigure/         # R2DBC init, DatabaseMigration, repository beans
├── easyai-compaction-autoconfigure/    # Compaction orchestrator + strategy beans
└── easyai-swarm-autoconfigure/         # SwarmRuntime, SwarmEventBridge, stores
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Core wiring | `easyai-core-autoconfigure/` | Main framework beans, `easyai.*` properties |
| Env post-processing | `*EnvironmentPostProcessor` | Pre-startup property injection |
| Provider-specific | `easyai-openai/anthropic-autoconfigure/` | ChatModel factories + options |
| Observability | `easyai-observability-autoconfigure/` | Micrometer, OTel SDK, listeners |
| R2DBC beans | `easyai-r2dbc-autoconfigure/` | DatabaseMigration, stores, SessionManager |
| Swarm beans | `easyai-swarm-autoconfigure/` | SwarmRuntime, preset/run stores |

## CONVENTIONS
- `@Configuration` + `@ConditionalOnClass` for conditional bean registration
- `EnvironmentPostProcessor` runs before refresh — no bean DI available
- Configuration properties: `@ConfigurationProperties(prefix = "easyai.*")`
- Spring Boot 4.x: `EnvironmentPostProcessor` registered via `META-INF/spring.factories`

## ANTI-PATTERNS
- Don't add beans without `@ConditionalOn*` guards — breaks optional dependencies
- Don't skip `spring-configuration-metadata.json` when adding properties
- EnvironmentPostProcessors run early — no Spring DI available
