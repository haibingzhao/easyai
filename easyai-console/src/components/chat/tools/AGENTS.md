# easyai-console/src/components/chat/tools/ AGENTS.md

This file provides guidance to Qoder (qoder.com) when working with code in this repository.

## OVERVIEW
Tool execution message renderers — each tool type gets a dedicated component with custom display, icons, and collapsible details.

## STRUCTURE

```
tools/
├── ToolMessageRouter.tsx          # Routes tool execution events to correct renderer
├── GenericToolMessage.tsx         # Fallback for unknown tool types (JSON auto-detect)
├── BashToolMessage.tsx            # Bash command execution display
├── ReadToolMessage.tsx            # File read tool display
├── ReadLsGroupedMessage.tsx       # Grouped read/ls operations
├── FileEditToolMessage.tsx        # File edit tool display
├── EditedGroupedMessage.tsx       # Grouped edit operations
├── FileSearchToolMessage.tsx      # Glob/find tool display
├── GrepToolMessage.tsx            # Grep search display
├── AskQuestionToolMessage.tsx     # User clarification prompts
├── TodoWriteToolMessage.tsx       # Todo list update display
├── GoalToolMessage.tsx            # Goal creation/update display
├── CalcToolMessage.tsx            # Calculator tool display
├── MemoryToolMessage.tsx          # Memory operations display
├── McpToolCard.tsx                # MCP tool execution card
├── SubAgentToolMessage.tsx        # Sub-agent spawn display
├── SubAgentPanel.tsx              # Sub-agent execution panel
├── CollapsibleSection.tsx         # Shared collapsible UI primitive
├── CopyableText.tsx               # Copy-to-clipboard text
├── icons.tsx                      # Tool-specific Lucide icon mappings
├── types.ts                       # Tool message type definitions
├── parsers.ts                     # Parse tool args/results into display data
└── index.tsx                      # Barrel export
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| Add new tool renderer | Create `*ToolMessage.tsx`, register in `ToolMessageRouter` | Follow `ReadToolMessage.tsx` pattern |
| Route registration | `ToolMessageRouter.tsx` | Maps tool names to components |
| Icon mapping | `icons.tsx` | Tool name → Lucide icon |
| Parsing logic | `parsers.ts` | Raw args → typed display data |

## CONVENTIONS
- Each tool: separate `*ToolMessage.tsx` component
- Props: tool call data (id, name, args, result, status)
- Use `CollapsibleSection.tsx` for expandable details
- Icons from `icons.tsx`, not inline Lucide imports
- `parsers.ts` handles arg parsing — keep UI components pure
- TypeScript strict: no `any` types

## ANTI-PATTERNS
- No `any` types — check node_modules for external types
- No inline imports — standard top-level imports only
- Don't add switch statements in router — use component map pattern
- Don't duplicate parsing logic — use `parsers.ts`
