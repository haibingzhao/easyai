# easyai-r2dbc-autoconfigure AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
R2DBC database auto-configuration: DB initialization, connection factory, repository wiring.
Supports any R2DBC-compatible database (H2, PostgreSQL, etc.).

## STRUCTURE
```
easyai-r2dbc-autoconfigure/
├── R2dbcDatabaseInitializer          # R2DBC DB setup + migration
├── R2dbcProperties                   # @ConfigurationProperties("easyai.r2dbc.*")
├── AgentSeedInitializer              # Default agent seeding
└── R2dbcRepositoryAutoConfiguration  # Repository beans
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| DB init | `R2dbcDatabaseInitializer` | Database lifecycle (SmartLifecycle) |
| Properties | `R2dbcProperties` | `easyai.r2dbc.*` config prefix |
| Repository beans | `R2dbcRepositoryAutoConfiguration` | R2DBC repository wiring |

## PROVIDED BEANS
| Bean | Type | Description |
|------|------|-------------|
| `databaseMigration` | `DatabaseMigration` | Default table definitions for migration |
| `r2dbcDatabaseInitializer` | `R2dbcDatabaseInitializer` | R2DBC setup + migration execution |
| `asyncSessionStore` | `AsyncSessionStore` → `R2dbcAsyncSessionStore` | Async session storage via R2DBC |
| `sessionManager` | `SessionManager` → `DatabaseSessionManager` | Session lifecycle management with ChatModel |
| `asyncAgentStore` | `AsyncAgentStore` → `R2dbcAgentStore` | Async agent definition storage via R2DBC |
| `modelProviderConfigStore` | `ModelProviderConfigStore` → `R2dbcModelConfigStore` | Async model config storage via R2DBC |
| `modelConfigService` | `ModelConfigService` → `DefaultModelConfigService` | Model config service layer |

## CONVENTIONS
- Follows parent `easyai-autoconfigure/AGENTS.md` patterns
- `@Configuration` + `@ConditionalOnClass` for conditional bean registration
