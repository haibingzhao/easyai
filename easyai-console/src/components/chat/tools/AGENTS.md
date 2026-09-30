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
├── FileEditToolMessage.tsx        # File edit tool display
├── FileSearchToolMessage.tsx      # Glob/find tool display
├── GrepToolMessage.tsx            # Grep search display
├── AskQuestionToolMessage.tsx     # User clarification prompts
├── TodoWriteToolMessage.tsx       # Todo list update display
├── GoalToolMessage.tsx            # Goal creation/update display
├── CalcToolMessage.tsx            # Calculator tool display
├── MemoryToolMessage.tsx          # Memory operations display
├── McpToolCard.tsx                # MCP tool execution card
├── SubAgentToolMessage.tsx        # Sub-agent spawn display (thin adapter over segments/SubAgentRow)
├── ToolRowHeader.tsx              # Shared compact single-line row header
├── useStreamingRowExpand.ts       # Shared auto-expand-while-running hook
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
- `compact?: boolean`: render as a borderless process-group row — header from `ToolRowHeader` with a one-line summary from `getToolRowSummary`, details body unchanged. Rows auto-expand only while `status` is RUNNING/PENDING via `useStreamingRowExpand`; a manual toggle wins (`userTouchedRef`).
- Use `CollapsibleSection.tsx` for expandable details
- Icons from `icons.tsx`, not inline Lucide imports
- `parsers.ts` handles arg parsing — keep UI components pure
- TypeScript strict: no `any` types

## ANTI-PATTERNS
- No `any` types — check node_modules for external types
- No inline imports — standard top-level imports only
- Don't add switch statements in router — use component map pattern
- Don't duplicate parsing logic — use `parsers.ts`
