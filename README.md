<div align="center">

# 🧠 EasyAI

### Use AI to Create AI — 用自然语言构建、编排、运行多 Agent 协作系统

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)](https://spring.io)
[![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black)](https://react.dev)

![EasyAI Hero Banner](assets/hero-banner.png)

[⬇️ Quick Start](#-quick-start) · [✨ Examples](easyai-examples/) · [🏗️ Architecture](#-architecture)

</div>

---

## 🤔 What Is EasyAI?

EasyAI is a **full-stack LLM Agent framework** (Kotlin / Spring Boot) with desktop and web clients. You describe what you want in plain language — EasyAI generates the agents, assigns tools, orchestrates the team, and streams every step back to you in real time.

**Core components:**

- 🔄 **ReAct Agent Loop** — fully controllable `call → tool → observe → repeat` cycle
- 🐝 **Swarm Runtime** — DAG-based multi-agent orchestration: parallel teams + adversarial debates with judge
- 🪄 **AI Config Generator** — natural language → complete Agent / Workflow configs, with built-in validate → fix loop
- 🖥️ **Desktop + Web Client** — one download: bundled JRE, embedded database, zero runtime setup

**Why EasyAI?**

- Other frameworks: write YAML/JSON configs → EasyAI: **describe in natural language, AI generates & validates**
- Other frameworks: sequential agent chains → EasyAI: **DAG orchestration + multi-round judge-ruled debates**
- Other frameworks: CLI-only → EasyAI: **visual workflow editor + full chat console + desktop app**

---

## ✨ Key Features

<table>
<tr>
<td width="50%">

### 🏛️ AI Deliberation Debates

![Deliberation](assets/card-deliberation.png)

- Multi-round **Bull vs Bear** adversarial debates
- Judge autonomously decides when consensus converges
- Personalized prompts per participant, per round
- Eliminates single-agent analysis bias

</td>
<td width="50%">

### 🪄 AI Creates AI

![AI Creates AI](assets/card-ai-creates-ai.png)

- Describe needs → AI generates complete agent configs
- Built-in validate → fix loop guarantees correctness
- Resource-aware: discovers your tools & MCP servers
- Iterative refinement via conversation ("remove QA, add a DBA")

</td>
</tr>
<tr>
<td width="50%">

### 🌐 DAG Swarm Orchestration

![DAG Orchestration](assets/card-dag-orchestration.png)

- Visual drag-and-drop workflow editor
- SINGLE / TEAM / DELIBERATION task types
- Parallel execution with dependency resolution
- Real-time progress streaming via SSE

</td>
<td width="50%">

### 📦 One Download, Zero Setup

![Desktop](assets/card-desktop.png)

- Bundled JRE + fat jar — no JDK required
- Embedded H2 database — no PostgreSQL required
- macOS (DMG) / Windows (NSIS) / Linux (AppImage)
- Auto-update with native notifications

</td>
</tr>
</table>

---

## 🚀 What You Can Do

| Task | How | Output |
|------|-----|--------|
| Run a 14-agent investment analysis | Paste one prompt → AI generates the full DAG | 4D reports + Bull/Bear debate + structured trade plan |
| Deploy a 6-expert coding team | Paste one prompt → AI generates a TEAM agent | Research → Implement → Test → Review, in parallel |
| Create a custom agent in 30 seconds | Describe the role in natural language | Complete agent with tools + permissions |
| 100+ turn deep research sessions | Memory system + context compaction | Context never lost, auto-summarized |
| Integrate any external tool | Connect an MCP server in the UI | Agent instantly gains new capabilities |
| Modify code with a safety net | Git snapshot before every change | One-click revert to any checkpoint |

---

## ⚡ Quick Start

### Option A: Desktop App (Recommended · ~5 minutes)

```bash
# 1. Build the desktop app (requires JDK 21+, Node 20+)
cd easyai-desktop
scripts/build-backend.sh          # console dist → fat jar
scripts/fetch-jre.sh              # bundle Temurin JRE 21
npm ci && npm run desktop:build   # → DMG / NSIS / AppImage

# 2. Install & launch — embedded JRE + H2 database, zero runtime config
# 3. Open Settings → configure your LLM API key (DashScope / OpenAI / Anthropic)
```

### Try the Examples ✨

Every example is a **carefully crafted prompt** — copy, paste, generate, done. See [easyai-examples/](easyai-examples/) for full guides.

**🧑‍💻 Coding Team** — 6 experts (Researcher, Engineer, QA, Reviewer, UI Operator, Debugger) collaborate on your code:

> Agents → Create Agent → ✨ AI Panel → paste the [prompt](easyai-examples/coding-team/) → Generate → Apply → Save

**📈 Investment Analysis** — 14 AI analysts form an investment committee with bull-vs-bear debates:

> Workflow → Create Preset → ✨ AI Panel → paste the [prompt](easyai-examples/investment-analysis/) → Generate → Apply → Save → Run

### Option B: Dev Server from Source

```bash
# Backend (JDK 21+)
cd easyai && ./mvnw clean install
cd ../easyai-apps && ./mvnw spring-boot:run -pl easyai-web-server   # → :8080

# Frontend (Node 20+)
cd easyai-console && npm ci && npm run dev                          # → :5173
```

Open `http://localhost:5173` → Settings → configure your LLM API key → start chatting.

---

## 🏗️ Architecture

```
┌───────────────────────────────────────────────────────────┐
│                Desktop Shell (Electron)                     │
│  ┌─────────────────────────────────────────────────────┐  │
│  │          Web Console (React + Vite + TailwindCSS)    │  │
│  │   Chat │ Agents │ Workflow DAG │ MCP │ Memories      │  │
│  └─────────────────────────────────────────────────────┘  │
├───────────────────────────────────────────────────────────┤
│              Spring Boot Backend (Kotlin)                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────────┐  │
│  │  ReAct   │ │  Swarm   │ │  Skills  │ │  Context    │  │
│  │  Agent   │ │  Runtime │ │  & Sub-  │ │  Compaction │  │
│  │  Loop    │ │  (DAG)   │ │  Agents  │ │             │  │
│  └──────────┘ └──────────┘ └──────────┘ └─────────────┘  │
│  ┌─────────────────────────────────────────────────────┐  │
│  │  Tools: file │ search │ shell │ web │ MCP │ memory   │  │
│  │         goal │ todo │ question │ calc                │  │
│  └─────────────────────────────────────────────────────┘  │
│  SSE Streaming │ JWT Auth │ Permission │ Exposed R2DBC    │
└───────────────────────────────────────────────────────────┘
```

| Project | Path | Tech | Purpose |
|---------|------|------|---------|
| easyai | `easyai/` | Kotlin 2.3 / Spring Boot 4.1 | LLM harness framework (agent loop, tools, swarm, SSE, persistence) |
| easyai-console | `easyai-console/` | React 18 / TypeScript / Vite 6 | Web console (chat, agents, workflow, MCP, memories) |
| easyai-apps | `easyai-apps/` | Kotlin / Spring Boot | Runnable servers (web + desktop backend) |
| easyai-desktop | `easyai-desktop/` | Electron 33 / esbuild | Desktop shell: spawns fat jar with bundled JRE |
| easyai-examples | `easyai-examples/` | Prompts | Learn-by-doing: paste a prompt, AI builds the rest |

---

## 🛠️ Tech Stack

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot" />
  <img src="https://img.shields.io/badge/Spring_AI-2.0-6DB33F?logo=spring&logoColor=white" alt="Spring AI" />
  <img src="https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black" alt="React" />
  <img src="https://img.shields.io/badge/TypeScript-5.9-3178C6?logo=typescript&logoColor=white" alt="TypeScript" />
  <img src="https://img.shields.io/badge/Vite-6-646CFF?logo=vite&logoColor=white" alt="Vite" />
  <img src="https://img.shields.io/badge/TailwindCSS-4-06B6D4?logo=tailwindcss&logoColor=white" alt="TailwindCSS" />
  <img src="https://img.shields.io/badge/Electron-33-47848F?logo=electron&logoColor=white" alt="Electron" />
  <img src="https://img.shields.io/badge/PostgreSQL-R2DBC-4169E1?logo=postgresql&logoColor=white" alt="PostgreSQL" />
</p>

---

## 🤝 Contributing

Contributions are welcome! Please read [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style, and PR process.

## 📄 License

[Apache License 2.0](LICENSE)
