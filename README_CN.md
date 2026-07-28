<div align="center">

# 🧠 EasyAI

### Use AI to Create AI — 用自然语言构建、编排、运行多 Agent 协作系统

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white)](https://spring.io)
[![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black)](https://react.dev)

![EasyAI Hero Banner](assets/hero-banner.png)

[⬇️ 快速开始](#-快速开始) · [✨ 示例](easyai-examples/) · [🏗️ 架构](#️-架构) · [🇬🇧 English](README.md)

</div>

---

## 🤔 EasyAI 是什么？

EasyAI 是一个**全栈 LLM 智能体框架**（Kotlin / Spring Boot），提供桌面端和 Web 客户端。你只需用自然语言描述需求 —— EasyAI 自动生成 Agent、分配工具、编排团队协作，并将每一步实时流式回传。

**核心组件：**

- 🔄 **ReAct Agent 循环** — 完全可控的 `call → tool → observe → repeat` 循环
- 🐝 **Swarm 运行时** — 基于 DAG 的多 Agent 编排：并行团队 + 对抗辩论 + 裁判裁决
- 🪄 **AI 配置生成器** — 自然语言 → 完整 Agent / Workflow 配置，内建 validate → fix 循环
- 🖥️ **桌面 + Web 客户端** — 一次下载：内置 JRE、嵌入式数据库，零运行时配置

**为什么选择 EasyAI？**

- 其他框架：手写 YAML/JSON 配置 → EasyAI：**自然语言描述，AI 自动生成并校验**
- 其他框架：顺序 Agent 链 → EasyAI：**DAG 编排 + 多轮裁判制辩论**
- 其他框架：仅 CLI → EasyAI：**可视化工作流编辑器 + 完整 Chat 控制台 + 桌面应用**

---

## ✨ 核心特性

<table>
<tr>
<td width="50%">

### 🏛️ AI 对抗辩论

![Deliberation](assets/card-deliberation.png)

- 多轮 **Bull vs Bear** 对抗辩论
- 裁判自主判断共识何时收敛
- 每位参与者、每轮独立个性化 Prompt
- 消除单 Agent 分析偏见

</td>
<td width="50%">

### 🪄 AI 创造 AI

![AI Creates AI](assets/card-ai-creates-ai.png)

- 描述需求 → AI 自动生成完整 Agent 配置
- 内建 validate → fix 循环保证正确性
- 资源感知：自动发现可用工具和 MCP 服务器
- 对话式迭代优化（"去掉 QA，加一个 DBA"）

</td>
</tr>
<tr>
<td width="50%">

### 🌐 DAG Swarm 编排

![DAG Orchestration](assets/card-dag-orchestration.png)

- 可视化拖拽工作流编辑器
- SINGLE / TEAM / DELIBERATION 三种任务类型
- 依赖自动解析，并行执行
- 通过 SSE 实时流式推送进度

</td>
<td width="50%">

### 📦 一次下载，零配置

![Desktop](assets/card-desktop.png)

- 内置 JRE + fat jar — 无需安装 JDK
- 嵌入式 H2 数据库 — 无需 PostgreSQL
- macOS (DMG) / Windows (NSIS) / Linux (AppImage)
- 自动更新 + 原生通知

</td>
</tr>
</table>

---

## 🚀 你能做什么

| 场景 | 方式 | 产出 |
|------|------|------|
| 运行 14 Agent 投资分析 | 粘贴一段 Prompt → AI 生成完整 DAG | 4D 报告 + 多空辩论 + 结构化交易方案 |
| 部署 6 人编程团队 | 粘贴一段 Prompt → AI 生成 TEAM Agent | 调研 → 实现 → 测试 → 审查，并行执行 |
| 30 秒创建自定义 Agent | 自然语言描述角色 | 完整 Agent（含工具 + 权限） |
| 100+ 轮深度研究会话 | 记忆系统 + 上下文压缩 | 上下文永不丢失，自动摘要 |
| 集成任意外部工具 | 在 UI 中连接 MCP 服务器 | Agent 即刻获得新能力 |
| 安全修改代码 | 每次变更前 Git 快照 | 一键回滚到任意检查点 |

---

## ⚡ 快速开始

### 方案 A：桌面应用（推荐 · 约 5 分钟）

```bash
# 1. 构建桌面应用（需要 JDK 21+、Node 20+）
cd easyai-desktop
scripts/build-backend.sh          # console dist → fat jar
scripts/fetch-jre.sh              # 打包 Temurin JRE 21
npm ci && npm run desktop:build   # → DMG / NSIS / AppImage

# 2. 安装并启动 — 内置 JRE + H2 数据库，零运行时配置
# 3. 打开设置 → 配置 LLM API Key（DashScope / OpenAI / Anthropic）
```

### 试试示例 ✨

每个示例都是一段**精心设计的 Prompt** — 复制、粘贴、生成、完成。详见 [easyai-examples/](easyai-examples/)。

**🧑‍💻 编程团队** — 6 位专家（调研员、工程师、QA、审查员、UI 操作员、调试员）协作完成你的代码：

> Agents → 创建 Agent → ✨ AI 面板 → 粘贴 [Prompt](easyai-examples/coding-team/) → 生成 → 应用 → 保存

**📈 投资分析** — 14 位 AI 分析师组成投资委员会，进行多空辩论：

> Workflow → 创建预设 → ✨ AI 面板 → 粘贴 [Prompt](easyai-examples/investment-analysis/) → 生成 → 应用 → 保存 → 运行

### 方案 B：从源码启动开发服务器

```bash
# 后端（JDK 21+）
cd easyai && ./mvnw clean install
cd ../easyai-apps && ./mvnw spring-boot:run -pl easyai-web-server   # → :8080

# 前端（Node 20+）
cd easyai-console && npm ci && npm run dev                          # → :5173
```

打开 `http://localhost:5173` → 设置 → 配置 LLM API Key → 开始对话。

---

## 🏗️ 架构

```
┌───────────────────────────────────────────────────────────┐
│                桌面外壳 (Electron)                          │
│  ┌─────────────────────────────────────────────────────┐  │
│  │          Web 控制台 (React + Vite + TailwindCSS)      │  │
│  │   对话 │ Agents │ 工作流 DAG │ MCP │ 记忆             │  │
│  └─────────────────────────────────────────────────────┘  │
├───────────────────────────────────────────────────────────┤
│              Spring Boot 后端 (Kotlin)                     │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────────┐  │
│  │  ReAct   │ │  Swarm   │ │  Skills  │ │  上下文      │  │
│  │  Agent   │ │  运行时   │ │  & Sub-  │ │  压缩        │  │
│  │  循环    │ │  (DAG)   │ │  Agents  │ │             │  │
│  └──────────┘ └──────────┘ └──────────┘ └─────────────┘  │
│  ┌─────────────────────────────────────────────────────┐  │
│  │  工具: file │ search │ shell │ web │ MCP │ memory    │  │
│  │        goal │ todo │ question │ calc                 │  │
│  └─────────────────────────────────────────────────────┘  │
│  SSE 流式推送 │ JWT 认证 │ 权限系统 │ Exposed R2DBC       │
└───────────────────────────────────────────────────────────┘
```

| 项目 | 路径 | 技术栈 | 用途 |
|------|------|--------|------|
| easyai | `easyai/` | Kotlin 2.3 / Spring Boot 4.1 | LLM 框架核心（Agent 循环、工具、Swarm、SSE、持久化） |
| easyai-console | `easyai-console/` | React 18 / TypeScript / Vite 6 | Web 控制台（对话、Agents、工作流、MCP、记忆） |
| easyai-apps | `easyai-apps/` | Kotlin / Spring Boot | 可运行服务（Web + 桌面后端） |
| easyai-desktop | `easyai-desktop/` | Electron 33 / esbuild | 桌面外壳：启动 fat jar + 内置 JRE |
| easyai-examples | `easyai-examples/` | Prompts | 实战学习：粘贴 Prompt，AI 完成剩余工作 |

---

## 🛠️ 技术栈

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

## 🔬 深入引擎 — 工程亮点

EasyAI 不是 LLM API 的薄封装。它是一个基于 Kotlin 协程**从零构建的生产级 Agent 运行时**。以下是内部实现：

### 🔄 完全可控的 ReAct 循环

- **手写 Agent 循环** — 不使用 Spring AI 内置 tool calling。每次迭代可检查、可中断、可扩展。
- **三阶段工具执行**：权限检查 → 并行批量执行 → 后置 Hook。被拦截的工具自动跳过剩余调用。
- **Completion Check 奖励迭代** — Goal 驱动的 Agent 可自主授予额外轮次（有预算上限）以完成工作。
- **上下文溢出自愈** — 检测 LLM token 限制错误，自动压缩上下文并透明重试。
- **运行时消息注入** — 可在循环中途注入引导/后续消息，附带迟到消息兜底逻辑。

### 📡 基于 Channel 的事件流

- `EventStream<TEvent, TResult>` 基于 Kotlin `Channel` + `CompletableDeferred` — 生产者/消费者完全解耦。
- 15+ 类型化 SSE 事件（thinking、工具生命周期、权限请求、压缩、子 Agent 转发）。
- 子 Agent 事件透明附加父级上下文（`withSubAgentContext`），支持嵌套渲染。
- `CustomEvent` 扩展点 — 无需修改 sealed 层级即可注入领域事件。

### 🐝 DAG Swarm 编排

- **Kahn 算法**实现分层拓扑调度 — 同层任务并行执行，层间顺序推进。
- **DFS 环检测**（白-灰-黑染色法）+ 人类可读的环路径报告。
- **失败级联** — BFS 传播标记所有传递依赖为 BLOCKED。
- **Semaphore 有界并发** + 运行时暂停/恢复/取消（原子信号）。

### ⚔️ AI 对抗辩论

- 裁判使用**中立编排 Prompt**（剥离角色人设），消除主持偏见。
- 每轮：裁判审阅完整历史 → 为每位参与者生成**个性化 Prompt**（JSON Schema 约束输出）。
- **自主收敛** — 裁判通过结构化 `converged` 输出判断共识是否达成。
- **断点恢复** — 重启时重建 token 计数器并定位最后完成的轮次。

### 👥 Leader-Member 响应式团队

- 事件驱动协调：成员结果通过 `Channel` 流入，去抖/排空后呈递给 Leader。
- Leader 输出**结构化决策**（新任务、重新分配、暂停协助、用户咨询）。
- 成员可被**阻塞、由同伴协助、或上报** — Leader 动态适应。
- 完整持久化：轮次记录 + 成员执行记录，支持崩溃恢复和断点续跑。

### 🗜️ 增量上下文压缩

- **每轮有界数据**：仅"上一轮摘要 + 新消息" — 永不重新加载完整历史。
- 三种触发方式：Auto（token 阈值）、Manual、Overflow（错误后紧急压缩）。
- `UsageAwareTokenEstimator` — 从实际 LLM 使用反馈中校准 chars/token 比率。
- 压缩过程中提取**会话变量**，供前端实时状态展示。

### 🔐 纵深防御权限系统

- **开闭原则设计** — 每个 ToolBuilder 声明自己的 `permissionEvaluator`；新工具零核心改动。
- Shell 命令三级安全分类（SAFE_READ / SAFE_WRITE / UNSAFE）+ 复合命令拆分。
- Git 子命令粒度：`git log` 自动通过，`git push` 需显式授权。
- 权限请求通过 SSE 暂停 Agent 循环 → 前端审批卡片 → 恢复执行。

### 📸 影子 Git 快照

- **独立 Git 仓库**（`--git-dir` 隔离）— 永不污染项目 `.git` 历史。
- 作者归因：区分 USER 提交与 LLM_AGENT 提交（Agent ID 编码在作者名中）。
- 每仓库 `Mutex` 防止并发快照写入；支持文件级回滚到任意检查点。

### ⚡ 韧性与错误分类

- 基于 Spring AI 异常层级的类型安全错误分类（`NonTransientAiException` / `TransientAiException`）。
- 4 级检测：结构化异常 → HTTP 状态码 → Provider 特定 → 关键词兜底。
- 跨 Provider 鲁棒性：OpenAI、Anthropic、DashScope 统一处理。
- 双流停滞检测：240s TTFT 超时 + 120s 块间空闲超时（独立于 HTTP 层）。

### 🔌 全异步 MCP 集成

- `McpAsyncClient` + Reactor Mono → 协程 `awaitSingle` 桥接 — 完全非阻塞。
- 按用户懒初始化 + 双重检查锁；系统服务器启动时连接。
- 支持 **Stdio** 和 **StreamableHTTP** 双传输协议，按用户-按服务器工具缓存。

---

## 🤝 贡献

欢迎贡献！请阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 了解开发环境搭建、代码规范和 PR 流程。

## 📄 许可证

[Apache License 2.0](LICENSE)
