# easyai-common-core AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Model interfaces (17 marker interfaces) + thinking extraction API for LLM reasoning.

## STRUCTURE
```
easyai-common-core/
├── model/          # 17 marker interfaces (Named, Identified, Versioned, etc.)
├── streaming/
│   └── StreamingEvent  # Generic streaming event type
└── thinking/
    ├── InternalThinkingApi  # Internal thinking API
    ├── ThinkingBlock        # Thinking content block wrapper
    ├── ThinkingTags         # Tag definitions
    └── *Extraction          # Dynamic tag extraction, thinking block extraction
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Marker interfaces | `model/` | Small interfaces (1-3 properties each), compose via multiple inheritance |
| Thinking extraction | `thinking/` | Parse `<thinking>` tags from LLM output |
| Streaming events | `streaming/StreamingEvent` | Generic event type for async streams |

## CONVENTIONS
- Marker interfaces stay small (1-3 properties max) — no business logic
- `internal` visibility unless public API
- Data classes for state aggregation
- Standard Kotlin conventions (see parent `easyai-common/AGENTS.md`)

## ANTI-PATTERNS
- Don't add business logic here — pure model definitions only
- Don't grow marker interfaces beyond 1-3 properties — split instead
- Don't use `println` — SLF4J only (use `GetLogger`)
