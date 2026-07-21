# easyai-common AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Shared utilities: 4 sub-modules — marker interfaces, thinking extraction, Jinja templates, CLI/text helpers.

## STRUCTURE

```
easyai-common/
├── easyai-common-bom/           # BOM for version alignment
├── easyai-common-core/          # Model interfaces + thinking extraction + streaming
│   ├── model/                   # Marker interfaces: Named, Identified, Versioned, Paginated, etc.
│   ├── streaming/               # StreamingEvent<T> generic type
│   └── thinking/                # ThinkingBlock, ThinkingTags, extraction utilities
├── easyai-common-textio/        # Jinjava template rendering
│   └── template/
│       ├── JinjavaTemplateRenderer  # .jinja file rendering
│       ├── RegistryTemplateProvider # Template registry lookup
│       ├── TemplateProvider         # Provider interface
│       └── TemplateRenderer         # Renderer interface + variable extraction
└── easyai-common-util/          # General utilities
    ├── AnsiBuilder              # ANSI color codes for CLI
    ├── GetLogger                # Logger factory shorthand
    ├── TextWrapper              # Text formatting/wrapping
    ├── ProcessExecutor          # Process execution helper
    ├── SharedObjectMapper       # Shared Jackson ObjectMapper
    ├── DummyInstanceCreator     # Creates dummy instances for schema generation
    ├── reflectionUtils          # Java reflection helpers
    └── formatUtils              # String formatting helpers
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Marker interfaces | `common-core/model/` | Small (1-3 properties): Named, Identified, Versioned, etc. |
| Thinking extraction | `common-core/thinking/` | Parse `<thinking>` tags from LLM output |
| Template rendering | `common-textio/template/` | Jinjava + `.jinja` extension |
| CLI colors | `common-util/AnsiBuilder` | Terminal color code builder |
| JSON schema | `common-util/DummyInstanceCreator` | Creates instances for tool schema generation |

## CONVENTIONS
- common-core: marker interfaces — compose via multiple inheritance
- common-textio: `.jinja` files loaded via RegistryTemplateProvider, must be registered before use
- common-util: top-level functions (formatUtils.kt, reflectionUtils.kt), not classes
- All modules: `internal` visibility, data classes for state

## ANTI-PATTERNS
- Don't add business logic — these are pure utilities
- Marker interfaces stay small (1-3 properties max)
- Don't use `println` in common-util — SLF4J via GetLogger
- Jinjava templates must be registered before use
