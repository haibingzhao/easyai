# easyai-console AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
React 18 + TypeScript 5.9 + Vite 6 + TailwindCSS 4 frontend console. Zero test coverage. SSE chat streaming via Zustand. Pages: Chat, Agents, Swarm Workflow, MCP, Knowledge, Commands, Settings.

## STRUCTURE

```
easyai-console/src/
├── pages/
│   ├── AgentsPage.tsx           # Agent list + create/edit
│   ├── AgentCreatePage.tsx      # Agent config form (prompt, tools, schema)
│   ├── WorkflowPage.tsx         # Swarm workflow list
│   ├── WorkflowRunPage.tsx      # Swarm run execution view
│   ├── SwarmPresetEditorPage.tsx # DAG workflow editor
│   ├── McpPage.tsx              # MCP server management
│   ├── KnowledgePage.tsx        # Documents / Articles / Memory tabs
│   ├── SkillsPage.tsx           # Skill management (my skills + shared read-only layer) + AddSkillDialog
│   ├── SkillAgentPage.tsx       # "Install with Agent" chat panel: scan report, high-risk confirm, install via upload
│   ├── CommandsPage.tsx         # User command management
│   ├── ModelsPage.tsx           # Model provider config
│   ├── SettingsPage.tsx         # App settings (project, permissions, auth)
│   ├── DatabaseSetupPage.tsx    # First-run DB setup
│   └── LoginPage.tsx            # Authentication
├── components/
│   ├── chat/          # ChatPanel, MessageList, StreamingMessage, ThinkingBlock, ToolMessage, etc.
│   │   └── tools/     # Tool-specific renderers (Bash, FileEdit, Grep, MCP, SubAgent, etc.)
│   ├── swarm/         # WorkflowEditorCanvas, SwarmAgentEditor, TaskDetailPanel, progress views
│   ├── agent/         # JinjaTemplateEditor, SchemaEditor, VariableDropdown, selectors
│   ├── artifacts/     # ArtifactPanel (MIME router) + renderers (Text/HTML/MD/PDF/Excel/Image)
│   └── ui/            # Base UI primitives
├── services/
│   ├── stores/        # Zustand: chat-store (SSE), session-store, swarm-store, agent-store, etc.
│   │   └── chat/      # event-handler, message-converter, session-loader, sub-agent-handler
│   └── *-service.ts   # Backend API services (chat, session, agent, swarm, mcp, permission, etc.)
├── types/             # TypeScript type definitions (message, agent, socket-event, checkpoint, etc.)
├── hooks/             # useMention, useSlashCommand, useAttachmentManager, useResizable, etc.
├── constants/         # Navigation, tools, swarm variables
└── utils/             # i18n (en/zh), dag-layout, dag-validator, shiki, format
```

## CONVENTIONS
- `/skills/agent` is entered from a button inside the Skills page, not the sidebar; hosts pass `showAgentInstaller` to `<SkillsPage />` only after mounting the route (hosts without the route must not show a dead entry)
- The Agent panel pins the write bucket onto each assistant turn (`installScope`) and passes it to `installFromUpload(request, scope?)`, so a host write-default change between preview and install cannot land the package in a bucket other than the card shows
- TypeScript strict: noUnusedLocals, noUnusedParameters; no `any` types
- Path alias: `@/*` → `src/*` (tsconfig + vite)
- No inline/dynamic imports for types — standard top-level imports only
- Zustand selective subscriptions: `useChatStore((state) => state.field)`
- TailwindCSS v4 utility classes, lucide-react icons
- i18n: manual en/zh selection via `useTranslation()` hook
- Functional components with hooks
- ESLint: `typescript-eslint.configs.recommended`

## SSE EVENT FLOW
```
SSE Event → ChatService → useChatStore.handleEvent() → event-handler.ts → UI Update
```
Event types: `start`, `text_delta`, `thinking_delta`, `toolcall_start/delta/end`, `tool_execution_start/update/end`, `permission_request`, `checkpoint`, `goal_status`, `done`, `error`.

## ANTI-PATTERNS
- No `any` types (check node_modules for external types instead of guessing)
- No inline imports — no `await import()`, no `import("pkg").Type`. The one exception is route-level lazy loading: `lazy(() => import('./Page'))` at a route boundary (see `src/pages/ArticleEditorPageLazy.tsx`).
- Never remove/downgrade code to fix type errors from outdated deps — upgrade the dep
- `npm run dev/build/preview` forbidden in agent workflows
- Zero frontend tests — don't assume test patterns exist

## COMMANDS
```bash
npm run lint    # ESLint check
tsc -b          # Type check
# NEVER run: npm run dev, npm run build, npm run preview
```

## BACKEND ENDPOINTS

| Service | Endpoint | Method |
|---------|----------|--------|
| ChatService.sendMessage() | `/api/socket/chat` | POST (SSE) |
| SessionService | `/api/socket/session` | GET/POST/DELETE |
| AgentService | `/api/agent` | GET/POST/PUT/DELETE |
| SwarmService | `/api/swarm` | GET/POST/PUT/DELETE |
| McpService | `/api/mcp` | GET/POST/PUT/DELETE |
| PermissionService | `/api/permission` | GET/POST/PUT/DELETE |
| MemoryService | `/api/memory` | GET/POST/PUT/DELETE |
| SkillService | `/api/skills` | GET/POST/PATCH/DELETE (`POST /api/skills/upload` multipart) |
| skill-agent-service | `/api/skills/agent/stream` | POST (SSE, no session; preview + scan) |
| AuthService | `/api/auth` | POST (login/register/refresh) |
