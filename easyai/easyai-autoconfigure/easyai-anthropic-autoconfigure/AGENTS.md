# easyai-anthropic-autoconfigure AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Anthropic auto-configuration: AnthropicChatModel factory + options builders.

## STRUCTURE
```
easyai-anthropic-autoconfigure/
├── AnthropicAutoConfiguration        # Main auto-config
├── AnthropicChatModelFactory         # ChatModel factory for Anthropic
└── AnthropicChatOptionsBuilderFactory # Options builder factory
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| ChatModel creation | `AnthropicChatModelFactory` | Factory for Claude models |
| Options building | `AnthropicChatOptionsBuilderFactory` | Typed builder for Claude options |

## CONVENTIONS
- Follows parent `easyai-autoconfigure/AGENTS.md` patterns
- `@Configuration` + `@ConditionalOnClass` for conditional bean registration
