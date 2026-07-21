# easyai-web-autoconfigure AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
WebFlux auto-configuration: SSE endpoints, reactive beans, web properties.

## STRUCTURE
```
easyai-web-autoconfigure/
├── WebAutoConfiguration    # WebFlux/SSE bean wiring
└── WebProperties           # @ConfigurationProperties("easyai.web.*")
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Web beans | `WebAutoConfiguration` | WebFlux + SSE setup |
| Properties | `WebProperties` | `easyai.web.*` config prefix |

## CONVENTIONS
- Follows parent `easyai-autoconfigure/AGENTS.md` patterns
- `@Configuration` + `@ConditionalOnClass` for conditional bean registration
