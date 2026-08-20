# Contributing to EasyAI

Thank you for your interest in contributing to EasyAI! This guide will help you get started.

## Development Environment

- **JDK 21+** (Temurin recommended)
- **Maven 3.9+** (or use `./mvnw` wrapper)
- **Node.js 20+** (for frontend development)

## Building

### Backend (easyai/)

```bash
cd easyai
./mvnw clean install          # Build all modules
./mvnw test                   # Run all tests
./mvnw test -pl easyai-core   # Run single module tests
```

### Apps (easyai-apps/)

```bash
cd easyai-apps
./mvnw clean install
./mvnw spring-boot:run -pl easyai-web-server  # Run dev server on :8080
```

### Frontend (easyai-console/)

```bash
cd easyai-console
npm ci
npm run lint    # ESLint check
npx tsc -b     # Type check
```

## Code Style

### General

- Write new code in **Kotlin** by default. Keep existing Java files in Java.
- Favor clarity and immutability.
- No builder pattern — use `copy()` / wither methods on data classes.
- No extension functions (not visible from Java) — use `@JvmStatic` companion methods.
- Make all classes `internal` unless clearly part of the public API.
- Use `@ApiStatus.Internal` on technical-public classes.
- No `println()` — use SLF4J logger with placeholders: `logger.info("{} {}", a, b)`.
- Don't comment obvious things. Naming should be self-documenting.

### Kotlin

- Follow Kotlin coding conventions.
- Use `@JvmOverloads` for functions with default parameters when Java interop matters.
- Use MockK for tests.

### Java

- Use modern features: `var`, records, switch expressions, multiline strings.
- Use Mockito for tests.

### Testing

- Use `@Nested` classes + backtick method names (no `@DisplayName`).
- Don't couple tests too tightly to implementation.
- Tests run with `en_US` locale forced (surefire config).

### Frontend

- No `any` types in TypeScript.
- No inline/dynamic imports for types — standard top-level imports only.

## Anti-Patterns to Avoid

- **No `ToolCallingAdvisor` / `ChatClient` tool loop** — conflicts with the custom ReAct loop; use raw `ChatModel.stream()` and parse tool calls manually.
- **No `transaction { }`** in repository — only `asyncTransaction { }` (Exposed R2DBC).
- **No `.block()`, `.blockFirst()`, `runBlocking { }`** in repository layer.

## AGENTS.md Files

You'll notice `AGENTS.md` files throughout the repository. These provide architectural context and coding guidance for AI-assisted development tools. They contain useful information about module boundaries, patterns, and conventions that may also help human contributors understand the codebase structure.

## Pull Request Process

1. Fork the repository and create a feature branch from `main`.
2. Make your changes following the code style above.
3. Ensure all tests pass: `./mvnw test` (backend) and `npm run lint && npx tsc -b` (frontend).
4. Write a clear PR description explaining what and why.
5. Keep PRs focused — one logical change per PR.

## Branch Naming

- `feature/short-description` — new features
- `fix/short-description` — bug fixes
- `refactor/short-description` — refactoring

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
