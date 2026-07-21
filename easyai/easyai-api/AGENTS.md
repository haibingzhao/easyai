# easyai-api AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Model provider abstraction: ChatModelFactory, ModelConfigService, provider config management.

## STRUCTURE
```
easyai-api/src/main/kotlin/com/easy/easyai/api/
├── config/
│   ├── ChatModelFactory.kt             # Interface: creates ChatModel from config
│   ├── ChatOptionsBuilderFactory.kt    # Interface: creates ChatOptions builder
│   ├── ModelConfigService.kt           # Interface: model config CRUD + resolution
│   ├── DefaultModelConfigService.kt    # Default implementation
│   └── ModelProviderConfigStore.kt     # Interface: provider config persistence
└── model/
    ├── ModelProviderConfig.kt          # Provider config data class (protocol, apiKey, baseUrl, etc.)
    ├── ModelProviderInfo.kt            # Provider metadata
    └── ModelInfo.kt                    # Model metadata
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Create ChatModel | `config/ChatModelFactory` | Implemented per provider (OpenAI, Anthropic) |
| Config resolution | `config/DefaultModelConfigService` | Resolves active model config |
| Provider config | `model/ModelProviderConfig` | Data class: protocol, apiKey, baseUrl, params |

## CONVENTIONS
- Data classes for models — immutable, `copy()` for variants
- `internal` visibility unless exposed to clients
- No business logic in model classes — pure DTOs
- Factory pattern: `ChatModelFactory` creates provider-specific ChatModel instances

## ANTI-PATTERNS
- Don't add business logic to models — these are DTOs/interfaces
- Don't hardcode provider specifics — use factory pattern
- Don't bypass `ModelConfigService` for config resolution
