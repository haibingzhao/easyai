# easyai-observability AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
OpenTelemetry/Micrometer observability: @Tracked AOP, tracing, metrics, MDC propagation for agent operations.

## STRUCTURE

```
easyai-observability/src/main/kotlin/com/easy/easyai/observability/
├── annotation/
│   ├── Tracked.kt                    # Method-level tracking annotation
│   ├── TrackType.kt                  # Track type enum
│   └── TrackedAspect.kt             # AOP aspect for @Tracked
├── config/
│   └── ObservabilityProperties.kt   # Spring Boot configuration properties
├── listener/
│   ├── MdcPropagationListener.kt    # MDC context propagation across coroutines
│   ├── MetricsEventListener.kt      # Micrometer metrics emission
│   └── TracingEventListener.kt      # OpenTelemetry span emission
├── observation/
│   ├── ChatModelObservationFilter.kt          # Enriches chat model observations
│   ├── EasyAiObservationContext.kt            # Custom observation context
│   ├── EasyAiTracingObservationHandler.kt     # Tracing for EasyAI spans
│   ├── NonEasyAiTracingObservationHandler.kt  # Tracing for non-EasyAI spans
│   ├── ObservationKeys.kt                     # Low/high cardinality keys
│   └── ObservationUtils.kt                    # Helper utilities
└── servlet/
    ├── HttpBodyCachingFilter.kt               # Request/response body caching
    └── HttpRequestObservationFilter.kt        # HTTP observation wrapper
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Custom annotations | `annotation/Tracked` + `TrackedAspect` | AOP-based automatic tracking |
| Event listeners | `listener/` | MDC, metrics, tracing — one per concern |
| Observation handlers | `observation/` | EasyAi vs non-EasyAi span separation |
| HTTP filters | `servlet/` | Request body caching for tracing |

## CONVENTIONS
- Micrometer Observation API: `Observation.createStarted()` pattern
- AOP: `@Tracked` methods auto-wrapped by `TrackedAspect`
- MDC must be propagated across coroutine boundaries via `MdcPropagationListener`
- Low vs high cardinality keys defined in `ObservationKeys`

## ANTI-PATTERNS
- Don't skip MDC propagation — breaks tracing in async contexts
- Don't mix EasyAI and non-EasyAI observation handlers
- Desktop server excludes observability — don't add OTel dependencies there
