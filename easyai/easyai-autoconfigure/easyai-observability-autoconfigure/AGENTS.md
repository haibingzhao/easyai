# easyai-observability-autoconfigure AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Observability auto-configuration: Micrometer tracing, OpenTelemetry SDK, listener wiring.

## STRUCTURE
```
easyai-observability-autoconfigure/
├── MicrometerTracingAutoConfiguration    # Micrometer tracing beans
├── ObservabilityAutoConfiguration        # Observability listener beans
└── OpenTelemetrySdkAutoConfiguration     # OpenTelemetry SDK setup
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Tracing | `MicrometerTracingAutoConfiguration` | Micrometer integration |
| Listeners | `ObservabilityAutoConfiguration` | Event listener wiring |
| OTel SDK | `OpenTelemetrySdkAutoConfiguration` | OpenTelemetry setup |

## CONVENTIONS
- Conditional on Micrometer/OpenTelemetry classes being on classpath
- Beans registered via `@Configuration` classes
- Follows parent `easyai-autoconfigure/AGENTS.md` patterns
