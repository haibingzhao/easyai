# easyai-common-util AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
General utilities: ANSI color builder, logger factory, text formatting, process execution, reflection, JSON schema helpers.

## STRUCTURE
```
easyai-common-util/
├── AnsiBuilder           # ANSI color code builder for CLI output
├── GetLogger             # Logger factory shorthand
├── NamingUtils           # Name generation utilities
├── TextWrapper           # Text formatting/wrapping
├── formatUtils.kt        # String formatting helpers (top-level functions)
├── reflectionUtils.kt    # Java reflection helpers (top-level functions, violation: println at :87,92)
├── time/                 # Time utilities
└── VisualizableTask      # Task visualization model
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| CLI colors | `AnsiBuilder` | Terminal color codes for shell output |
| Logger creation | `GetLogger` | `GetLogger(...)` shorthand for `LoggerFactory.getLogger(...)` |
| Text formatting | `TextWrapper`, `formatUtils` | String manipulation, indentation |
| Reflection | `reflectionUtils` | Java reflection helpers for Kotlin interop |

## CONVENTIONS
- Top-level functions preferred over classes (`formatUtils.kt`, `reflectionUtils.kt`)
- Utilities are pure — no side effects, no state
- `internal` visibility unless cross-module usage required

## ANTI-PATTERNS
- Don't add business logic — these are pure utilities only
- Don't use `println` — use `GetLogger` for logging (current violation: reflectionUtils:87,92)
- Don't create stateful utilities — keep them pure and stateless
