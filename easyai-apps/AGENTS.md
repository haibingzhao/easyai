# easyai-apps AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Runnable Spring Boot applications: web development server and desktop client backend. Separate Maven project from easyai library.

- `easyai-web-server`: Web development server with SSE streaming for the EasyAI console frontend.
- `easyai-desktop-server`: Self-contained backend bundled with the Electron desktop client. Serves console UI from `classpath:/static`, defaults to H2 file database, excludes observability/OTel dependencies.

## STRUCTURE

```
easyai-apps/
├── pom.xml                        # Parent POM, version 2026.0.1-SNAPSHOT
├── easyai-web-server/             # Web development server
│   ├── src/main/kotlin/.../WebExampleApplication.kt
│   └── src/main/resources/        # Application properties, prompts
└── easyai-desktop-server/         # Desktop client backend (fat jar + static console)
    ├── src/main/kotlin/.../DesktopServerApplication.kt
    └── src/main/resources/        # Clean properties (H2 file default), logback
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Run web server | `mvn spring-boot:run -pl easyai-web-server` | Starts on localhost:8080 |
| Desktop config | `easyai-desktop-server/src/main/resources/` | H2 default, no OTel |

## CONVENTIONS
- Both apps: `@SpringBootApplication` in `com.easy.easyai.example` package
- Prompts in `src/main/resources/prompts/` as Jinja templates
- Application properties in `src/main/resources/application.properties`
- No tests in apps module

## COMMANDS
```bash
mvn clean install                 # Build (separate from easyai/)
mvn spring-boot:run -pl easyai-web-server   # Run web server
```

## ANTI-PATTERNS
- Don't add business logic to apps — they are thin runners only
- Don't add tests — zero test coverage by design
- Don't depend on apps in easyai library code — apps depend on library, not vice versa
- Don't put credentials or OTel endpoints in `easyai-desktop-server` — it ships to end users
