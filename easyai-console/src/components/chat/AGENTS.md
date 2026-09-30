# easyai-console/src/components/chat/ AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Chat interface: message rendering, SSE streaming, tool call visualization, thinking blocks, permission prompts, goal cards, file changes.

Messages show results first: consecutive thinking + tool calls collapse into a "process group" row (`执行工具 N 次`), text blocks split groups and render normally. Live streaming keeps the trailing group and the currently-running row expanded; commit and history load render fully collapsed. One backend turn spans an assistant message per LLM round, so `MessageList` folds consecutive assistant messages into a single block (`AssistantMessageCluster`) and their groups merge across round boundaries.

## STRUCTURE

```
chat/
├── ChatPanel.tsx              # Main container: input, message list, sidebar panels
├── MessageList.tsx            # Scrollable message list; folds consecutive assistant messages into clusters
├── UserMessage.tsx            # User message display (text/image/file attachments)
├── AssistantMessage.tsx       # Single committed assistant message — segments + hover meta bar
├── AssistantMessageCluster.tsx # Consecutive assistant messages of one turn, merged segments + aggregated meta bar
├── MessageMetaBar.tsx         # Hover meta bar: aggregated token usage + fork action
├── StreamingMessage.tsx       # Active SSE streaming message rendering (segments from streaming blocks)
├── segments/                  # Ordered segment rendering (shared by live / commit / history)
│   ├── MessageSegments.tsx    #   The single segment renderer
│   ├── groupSegments.ts       #   segments → process-group / standalone render nodes
│   ├── ProcessGroup.tsx       #   collapsible group row + auto-expand trailing group
│   ├── SegmentRow.tsx         #   flat thinking / text / tool / compaction row
│   ├── SubAgentRow.tsx        #   standalone `task` row + flat inner transcript
│   ├── TextMarkdown.tsx       #   markdown with streaming-safe code fences
│   ├── messagesToBlocks.ts    #   history Message[] → StreamingBlock[]
│   └── useAutoExpand.ts       #   auto-expand until first manual toggle
├── ThinkingBlock.tsx          # Collapsible <thinking> row (preview + duration)
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
| Process folding | `segments/groupSegments.ts` + `segments/ProcessGroup.tsx` | Which segments collapse into a group row |
| Cross-round merge | `MessageList.tsx` `buildRenderItems` + `AssistantMessageCluster.tsx` | One turn renders as a single block |
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
