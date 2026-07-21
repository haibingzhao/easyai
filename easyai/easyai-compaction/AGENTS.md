# easyai-compaction AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Context compaction: reduces conversation history token count via configurable strategies (summary, LLM-based, composite).

## STRUCTURE

```
easyai-compaction/src/main/kotlin/com/easy/easyai/compaction/
├── ContextCompactionOrchestrator.kt      # Main orchestrator: load→estimate→compact→replace
├── CompactionConfig.kt                   # Config: thresholds, strategy selection
├── CompactionTriggerChecker.kt           # When to compact: token/message count
├── CompactionListener.kt                 # Agent lifecycle listener integration
├── CompactionTransformContextService.kt  # Transforms compacted ranges into context
├── OriginalMessageLoader.kt             # Interface: loads original messages
├── estimator/
│   └── TokenEstimator.kt                # Approximate token counting
├── model/
│   ├── CompactedRange.kt                # Range metadata (start/end, replacement)
│   └── CompactionContext.kt             # Pipeline state object
└── strategy/
    ├── CompactionStrategy.kt            # Interface: compact(messages) → result
    ├── SummaryStrategy.kt               # Rule-based summarization
    ├── LlmSummaryStrategy.kt           # LLM-powered summarization
    ├── CompositeCompactionStrategy.kt   # Chains multiple strategies
    └── StrategyOutput.kt               # Strategy result type
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Entry point | `ContextCompactionOrchestrator.kt` | Coordinates full pipeline |
| Trigger logic | `CompactionTriggerChecker.kt` | Token/message count thresholds |
| Strategy interface | `strategy/CompactionStrategy.kt` | Implement for new strategies |
| Token estimation | `estimator/TokenEstimator.kt` | Approximate counting |

## CONVENTIONS
- Strategy pattern: each strategy implements `CompactionStrategy` interface
- `CompactionContext` carries state through pipeline
- `CompactedRange` is immutable value object
- Strategy selection via `CompactionConfig`
- Integrates with agent via `CompactionListener` (lifecycle hook)

## ANTI-PATTERNS
- Don't bypass `ContextCompactionOrchestrator` — it manages lifecycle
- Don't mutate `CompactedRange` — create new instances
- Token estimation is approximate, not exact
