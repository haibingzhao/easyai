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
| Host-specific renderer | `registerToolRenderers` (exported from `@easyai/console`) | Hosts register by tool name at startup; entries override built-ins and are matched before MCP routing |
| Icon mapping | `icons.tsx` | Tool name → Lucide icon |
| Parsing logic | `parsers.ts` | Raw args → typed display data |

## CONVENTIONS
- Each tool: separate `*ToolMessage.tsx` component
- Props: tool call data (id, name, args, result, status)
- `compact?: boolean`: render as a borderless process-group row — header from `ToolRowHeader` with a one-line summary from `getToolRowSummary`, details body unchanged. Rows auto-expand only while `status` is RUNNING/PENDING via `useStreamingRowExpand` (third arg marks a tool already settled when a committed `result` outruns the status); a manual toggle wins via the returned marker. Don't reimplement that logic per renderer.
- Use `CollapsibleSection.tsx` for expandable details
- Icons from `icons.tsx`, not inline Lucide imports
- `parsers.ts` handles arg parsing — keep UI components pure
- Structured tool output comes from `result.details`: set by the tool as `ToolResult.details`, carried on the `tool_execution_end` SSE event and on the persisted `toolResult` content block. Text output is prose addressed to the model, so render it only as a fallback (no `details` → show the text).
- TypeScript strict: no `any` types

## ANTI-PATTERNS
- No `any` types — check node_modules for external types
- No inline imports — standard top-level imports only
- Don't add switch statements in router — use component map pattern
- Don't duplicate parsing logic — use `parsers.ts`
- Never regex a tool's text output back into data. Wording changes silently and punctuation inside a description breaks the split; ask the tool to send `details` instead.
