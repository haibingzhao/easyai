# easyai-core-autoconfigure AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Core Spring Boot auto-configuration: bean wiring, `easyai.*` properties, environment post-processing.

## STRUCTURE
```
easyai-core-autoconfigure/
├── EasyAiCoreAutoConfiguration            # Main bean wiring
├── EasyAiProperties                       # @ConfigurationProperties("easyai.*")
├── EasyAiSystemPropertyEnvironmentPostProcessor  # Pre-startup property injection
└── test/                                  # ConfigurationMetadataTest
```

## WHERE TO LOOK
| Task | Location | Notes |
|------|----------|-------|
| Bean wiring | `EasyAiCoreAutoConfiguration` | Core framework beans |
| Properties | `EasyAiProperties` | `easyai.*` config prefix |
| System props | `EasyAiSystemPropertyEnvironmentPostProcessor` | Runs before ApplicationContext |

## CONVENTIONS
- `@Configuration` + `@ConditionalOnClass` for conditional beans
- `EnvironmentPostProcessor` runs early — no bean injection available
- Tests: `ConfigurationMetadataTest` validates `spring-configuration-metadata.json`

## ANTI-PATTERNS
- Don't add beans without `@ConditionalOn*` guards
- Don't skip `spring-configuration-metadata.json` when adding properties
- EnvironmentPostProcessors run before context — no Spring DI available
