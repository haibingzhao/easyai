# easyai-dashscope-autoconfigure AGENTS.md

Guidance for working on the DashScope (Aliyun Bailian) native-protocol adapter.

## OVERVIEW
`ChatModelFactory` + Spring AI `ChatModel` on top of `com.alibaba:dashscope-sdk-java`.
Third supported LLM protocol next to OpenAI and Anthropic; selected by
`chatModelFactories.firstOrNull { it.supports(protocol) }`, so there is no `when(protocol)` dispatch to update.

## STRUCTURE
```
easyai-dashscope-autoconfigure/
├── DashScopeAutoConfiguration        # @ConditionalOnClass(Generation) bean registration
├── DashScopeChatModelFactory        # create() picks text vs multimodal client, maps thinking/effort
├── DashScopeChatOptions             # ToolCallingChatOptions + StructuredOutputChatOptions + SDK-only fields
├── DashScopeChatModel               # call()/stream(), options merging, error normalization
├── DashScopeRequestBuilder          # Prompt → GenerationParam / MultiModalConversationParam
├── DashScopeMessageConverter        # Spring AI Message → role/toolCall/media specs
├── DashScopeResponseMapper          # SDK result → DashScopeChunk (usage incl. cache + reasoning tokens)
└── DashScopeStreamingAccumulator    # cross-chunk tool-call assembly
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Thinking / effort request fields | `DashScopeChatModelFactory.optionsFor` | `enableThinking`/`thinkingBudget` are first-class; `reasoning_effort` uses `builder.parameter(...)` |
| Streaming emission shape | `DashScopeChatModel.toResponses` | drives what `AgentLoopRunner` can parse |
| Token accounting incl. cache hit | `DashScopeResponseMapper` | `promptTokensDetails` → `DefaultUsage.cacheRead/cacheWrite` |
| Structured output gate | `DashScopeChatModelFactory.build` | honours `capabilities.structuredOutput` |
| baseUrl handling | `DashScopeChatModelFactory.resolveBaseUrl` | SDK wants the full `/api/v1` path |

## CONVENTIONS
- Follows parent `easyai-autoconfigure/AGENTS.md` patterns (`@ConditionalOnClass` guard, `.imports` registration).
- SDK inner classes (`ToolCallFunction.CallFunction`, `GenerationOutput.Choice`) are non-static, so Kotlin cannot
  construct them: build/parse them through `JsonUtils.fromJsonObject` instead.
- Tests stay offline: feed real-shaped SSE JSON through the SDK's own Gson rather than hand-building SDK objects.

## ANTI-PATTERNS
- **Never invoke a `ToolCallback`.** The ReAct loop in easyai-core is the only tool executor; this adapter only
  reads `getToolDefinition()` and sends the schemas. No `ToolCallingAdvisor`/`ChatClient` tool loop.
- **Never emit a partial tool call on an intermediate chunk.** The loop harvests tool calls from the *last*
  content-bearing chunk, so each frame carries the complete accumulated snapshot, and a `finish_reason` frame with
  no text still emits one empty-content chunk so usage survives.
- **Thinking and effort are mutually exclusive.** `thinking_budget` alongside `reasoning_effort` is a 400 on Bailian
  (same invariant as the Anthropic factory); when thinking is off, send `enableThinking(false)` explicitly, because
  qwen3 hybrid models reason by default.
- **Do not replay `reasoning_content` into history.** `preserve_thinking` requires it verbatim, and compaction
  truncates it.
- Provider failures must leave through Spring AI's retry types. The SDK's `ApiException` is translated in
  `DashScopeChatModel`; `easyai-core` must not gain a compile dependency on the SDK.
