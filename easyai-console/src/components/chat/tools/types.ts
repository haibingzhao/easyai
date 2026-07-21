/**
 * Tool渲染组件的类型定义
 */

import type { ToolCall, ToolResult } from '@/types/message';
import type { ToolCallStatus } from '@/types/socket-event';
import type { TodoInfo } from '@/types/todo';
import type { SubAgentInnerBlock } from './SubAgentPanel';

/** 解析后的read工具结果 */
export interface ParsedReadResult {
  filePath: string;
  content: string;
  lines: string[];
  totalLines: number;
  offset?: number;
  limit?: number;
}

/** 解析后的grep匹配结果 */
export interface GrepMatch {
  filePath: string;
  lineNum: number;
  content: string;
}

/** 解析后的grep结果 */
export interface ParsedGrepResult {
  pattern: string;
  matches: GrepMatch[];
  searchPath?: string;
}

/** 解析后的find/ls结果项 */
export interface FileEntry {
  name: string;
  isDirectory: boolean;
  path: string;
}

/** 解析后的find/ls结果 */
export interface ParsedFileListResult {
  entries: FileEntry[];
  searchPath: string;
  toolType: 'glob' | 'ls';
}

/** 解析后的文件编辑结果 */
export interface ParsedFileEditResult {
  filePath: string;
  operation: 'write' | 'edit';
  additions?: number;
  deletions?: number;
}

/** 解析后的工具参数 */
export interface ParsedToolParams {
  read?: { path: string; offset?: number; limit?: number };
  write?: { path: string; content: string };
  edit?: { path: string; oldString: string; newString: string; replaceAll?: boolean };
  bash?: { command: string; timeout?: number };
  grep?: { pattern: string; path?: string };
  glob?: { pattern: string; path?: string };
  ls?: { path?: string };
  calc?: { script: string };
}

/** Tool消息组件的通用props */
export interface ToolMessageProps {
  toolCall: ToolCall;
  result?: ToolResult;
  status?: ToolCallStatus;
  streamingOutput?: string;
  workDir?: string;
  /** Sub-agent streaming data (for SubAgentTool during streaming) */
  subAgent?: {
    agentName: string;
    toolCallId: string;
    prompt?: string;
    blocks: SubAgentInnerBlock[];
    isFinished: boolean;
    streamingToolOutputs?: Record<string, string>;
    errorMessage?: string;
    /** Accumulated token usage from sub-agent's LLM calls */
    accumulatedUsage?: { inputTokens: number; outputTokens: number; cacheReadTokens: number };
    /** Latest todo snapshot from the sub-agent's todo_write calls */
    todos?: TodoInfo[];
  };
}

/** 折叠组件的props */
export interface CollapsibleSectionProps {
  title: React.ReactNode;
  children: React.ReactNode;
  defaultCollapsed?: boolean;
  className?: string;
}