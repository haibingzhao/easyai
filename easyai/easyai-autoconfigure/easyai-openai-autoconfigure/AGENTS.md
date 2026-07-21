# easyai-openai-autoconfigure AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
OpenAI auto-configuration: OpenAIChatModel factory + options builders.

## STRUCTURE
```
easyai-openai-autoconfigure/
├── OpenAiAutoConfiguration           # Main auto-config
├── OpenAiChatModelFactory            # ChatModel factory for OpenAI
└── OpenAiChatOptionsBuilderFactory  # Options builder factory
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| ChatModel creation | `OpenAiChatModelFactory` | Factory for GPT models |
| Options building | `OpenAiChatOptionsBuilderFactory` | Typed builder for OpenAI options |

## CONVENTIONS
- Follows parent `easyai-autoconfigure/AGENTS.md` patterns
- `@Configuration` + `@ConditionalOnClass` for conditional bean registration
