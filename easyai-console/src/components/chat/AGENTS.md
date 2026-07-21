# easyai-console/src/components/chat/ AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Chat interface: message rendering, SSE streaming, tool call visualization, thinking blocks, permission prompts, goal cards, file changes.

## STRUCTURE

```
chat/
├── ChatPanel.tsx              # Main container: input, message list, sidebar panels
├── MessageList.tsx            # Scrollable message list with auto-scroll
├── UserMessage.tsx            # User message display (text/image/file attachments)
├── AssistantMessage.tsx       # Assistant response + streaming integration
├── StreamingMessage.tsx       # Active SSE streaming message rendering
├── ThinkingBlock.tsx          # Collapsible <thinking> visualization
├── ToolMessage.tsx            # Tool execution result (routes to tools/)
├── MessageEditor.tsx          # Edit/resend message support
├── InlineEditMessage.tsx      # Inline message editing
├── ModelSelector.tsx          # Model selection dropdown
├── AgentSelector.tsx          # Agent selection dropdown
├── PermissionBar.tsx          # Permission request prompt UI
├── AutoApprovePanel.tsx       # Auto-approve configuration panel
├── GoalCard.tsx               # Goal status display + controls
├── GoalEditDialog.tsx         # Goal creation/edit dialog
├── TodoPanel.tsx              # Todo list sidebar panel
├── FileChangesPanel.tsx       # File changes sidebar (snapshot diffs)
├── TimelineBar.tsx            # Session timeline navigation
├── TokenContextBar.tsx        # Token usage context indicator
├── QueuedMessagesPanel.tsx    # Queued pending messages
├── ReferencePanel.tsx         # Reference/context panel
├── SlashCommandPopover.tsx    # / command autocomplete
├── ResourceMentionPopover.tsx # @ resource mention autocomplete
├── FileBrowserDropdown.tsx    # File attachment browser
├── DiffViewer.tsx             # Code diff display
├── CodeBlock.tsx              # Syntax-highlighted code block
├── CompactionIndicator.tsx    # Context compaction marker
├── RevertBanner.tsx           # Revert action banner
└── tools/                     # Tool-specific renderers (see tools/AGENTS.md)
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Layout | `ChatPanel.tsx` | Main container with sidebar panels |
| Streaming | `StreamingMessage.tsx` | SSE delta handling |
| Tool calls | `tools/ToolMessageRouter.tsx` | Routes to specific tool renderers |
| Permission | `PermissionBar.tsx` | User approve/deny flow |
| Goal | `GoalCard.tsx` | Goal lifecycle display |
| File changes | `FileChangesPanel.tsx` | Snapshot diff viewing |

## CONVENTIONS
- Functional components with hooks
- TailwindCSS v4 utility classes, lucide-react icons
- Zustand selective subscriptions: `useChatStore((state) => state.field)`
- Props: messages array, SSE event handlers, model selection callback

## ANTI-PATTERNS
- No `any` types — check node_modules for external types
- No inline imports — standard top-level imports only
- Never remove/downgrade code to fix type errors from outdated deps
