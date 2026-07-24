# EasyAI

A Kotlin/Spring Boot LLM Agent framework with a manual ReAct loop, multi-agent orchestration, and a full-stack desktop/web client.

## Features

- **ReAct Agent Loop** — Manual `call → extract toolCalls → execute → repeat` cycle (not using Spring AI's built-in tool calling)
- **Tool System** — Extensible tools organized by category: file, search, shell, web, MCP, memory, goal, todo, question, calc
- **Swarm Multi-Agent** — DAG-based task orchestration supporting SINGLE tasks, TEAM (parallel workers), and DELIBERATION (multi-round debate with judge)
- **SSE Streaming** — Kotlin Flow → Reactor Flux bridge for real-time event streaming
- **Context Compaction** — Intelligent context window management for long conversations
- **Permission System** — Rule-based tool permission evaluation (auto-approve, ask-user, or deny)
- **Memory System** — File-based memory store with LLM-powered flush, scoped to project
- **Snapshot/Revert** — Git-based checkpoint system with file-level revert
- **Desktop Client** — Electron shell that spawns a self-contained backend fat jar with bundled JRE
- **JWT Authentication** — Token-based auth with refresh token rotation

## Project Structure

| Project | Path | Tech | Purpose |
|---------|------|------|---------|
| easyai | `easyai/` | Kotlin 2.3 / Spring Boot 4.1 + Maven | LLM harness framework (agent loop, tools, skills, swarm, SSE, persistence) |
| easyai-console | `easyai-console/` | React 18 + TypeScript 5.9 + Vite 6 + TailwindCSS 4 | Frontend console (chat, agents, swarm workflow, MCP, memories) |
| easyai-apps | `easyai-apps/` | Kotlin/Spring Boot + Maven | Runnable apps: `easyai-web-server` (dev server) + `easyai-desktop-server` (desktop backend) |
| easyai-desktop | `easyai-desktop/` | Electron 33 + TypeScript + esbuild | Desktop client shell: spawns backend fat jar with bundled JRE |

## Prerequisites

- **JDK 21+**
- **Maven 3.9+** (or use the bundled `./mvnw` wrapper)
- **Node.js 20+** (for frontend)

## Quick Start

### 1. Build the framework

```bash
cd easyai
./mvnw clean install
```

### 2. Run the web server

```bash
cd easyai-apps
./mvnw spring-boot:run -pl easyai-web-server
```

The server starts on `http://localhost:8080`.

### 3. Run the frontend (development)

```bash
cd easyai-console
npm ci
npm run dev
```

The Vite dev server proxies `/api` requests to `http://localhost:8080`.

### 4. Configure via Web UI

Open `http://localhost:5173` in your browser. Navigate to **Settings** to configure your LLM API keys, database connections, and other options — no manual file editing required.

### 5. Desktop client

```bash
cd easyai-desktop
scripts/build-backend.sh    # Build console + fat jar
npm run desktop:dev         # Launch Electron
```

## Configuration

All configuration is managed through the **web UI** (Settings page). After starting the server and frontend, open the Settings page to configure:

| Setting | Description |
|---------|-------------|
| LLM API Key | Model provider API key (DashScope/Anthropic/OpenAI compatible) |
| Database | PostgreSQL connection (or use embedded H2 for desktop mode) |
| Search API Key | EXA search API key (optional) |

## Tech Stack

- **Backend**: Kotlin 2.3, Spring Boot 4.1, Spring AI 2.0, Exposed R2DBC, Kotlin Coroutines
- **Frontend**: React 18, TypeScript 5.9, Vite 6, TailwindCSS 4, Zustand
- **Desktop**: Electron 33, esbuild
- **Database**: PostgreSQL (R2DBC async) / H2 (embedded for desktop)

## License

[Apache License 2.0](LICENSE)
