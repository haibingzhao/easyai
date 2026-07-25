/**
 * Tool消息路由组件
 * 基于注册式映射表根据 toolName 选择渲染组件。
 * 新增 tool 时只需在 TOOL_RENDERERS 中添加一行。
 * MCP 工具名遵循 "serverName__toolName" 格式，自动路由到 McpToolCard。
 */

import type { ToolMessageProps } from './types';
import { BashToolMessage } from './BashToolMessage';
import { ReadToolMessage } from './ReadToolMessage';
import { FileEditToolMessage } from './FileEditToolMessage';
import { GrepToolMessage } from './GrepToolMessage';
import { FileSearchToolMessage } from './FileSearchToolMessage';
import { TodoWriteToolMessage } from './TodoWriteToolMessage';
import { AskQuestionToolMessage } from './AskQuestionToolMessage';
import { SubAgentToolMessage } from './SubAgentToolMessage';
import { GoalToolMessage } from './GoalToolMessage';
import { MemoryToolMessage } from './MemoryToolMessage';
import { CalcToolMessage } from './CalcToolMessage';
import { WebFetchToolMessage } from './WebFetchToolMessage';
import { LoadSkillToolMessage } from './LoadSkillToolMessage';
import { TeamToolMessage } from './TeamToolMessage';
import { GenericToolMessage } from './GenericToolMessage';
import { McpToolCard } from './McpToolCard';

/** Renderer registry — add new tool renderers here */
const TOOL_RENDERERS: Record<string, React.ComponentType<ToolMessageProps>> = {
  bash: BashToolMessage,
  read: ReadToolMessage,
  write: FileEditToolMessage,
  edit: FileEditToolMessage,
  grep: GrepToolMessage,
  glob: FileSearchToolMessage,
  ls: FileSearchToolMessage,
  todo_write: TodoWriteToolMessage,
  ask_question: AskQuestionToolMessage,
  task: SubAgentToolMessage,
  goal: GoalToolMessage,
  memory_search: MemoryToolMessage,
  memory_read: MemoryToolMessage,
  memory_write: MemoryToolMessage,
  memory_list: MemoryToolMessage,
  calc: CalcToolMessage,
  webfetch: WebFetchToolMessage,
  load_skill: LoadSkillToolMessage,
  delegate_to_member: TeamToolMessage,
  wait_for_member_events: TeamToolMessage,
  resume_member: TeamToolMessage,
};

export function ToolMessageRouter(props: ToolMessageProps) {
  // MCP tool names contain "__" (serverName__toolName)
  if (props.toolCall.toolName.includes('__')) {
    return <McpToolCard {...props} />;
  }
  const Renderer = TOOL_RENDERERS[props.toolCall.toolName] ?? GenericToolMessage;
  return <Renderer {...props} />;
}