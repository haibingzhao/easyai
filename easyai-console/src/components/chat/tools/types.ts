/**
 * Type definitions for tool rendering components.
 */

import type { ToolCall, ToolResult } from '@/types/message';
import type { ToolCallStatus } from '@/types/socket-event';
import type { ToolBlockData } from '@/services/stores/chat/types';

/** Parsed read tool result */
export interface ParsedReadResult {
  filePath: string;
  content: string;
  lines: string[];
  totalLines: number;
  offset?: number;
  limit?: number;
}

/** Parsed grep match entry */
export interface GrepMatch {
  filePath: string;
  lineNum: number;
  content: string;
}

/** Parsed grep result */
export interface ParsedGrepResult {
  pattern: string;
  matches: GrepMatch[];
  searchPath?: string;
}

/** Parsed find/ls result entry */
export interface FileEntry {
  name: string;
  isDirectory: boolean;
  path: string;
}

/** Parsed find/ls result */
export interface ParsedFileListResult {
  entries: FileEntry[];
  searchPath: string;
  toolType: 'glob' | 'ls';
}

/** Parsed file edit result */
export interface ParsedFileEditResult {
  filePath: string;
  operation: 'write' | 'edit';
  additions?: number;
  deletions?: number;
}

/** Parsed tool parameters */
export interface ParsedToolParams {
  read?: { path: string; offset?: number; limit?: number };
  write?: { path: string; content: string };
  edit?: { path: string; oldString: string; newString: string; replaceAll?: boolean };
  bash?: { command: string; timeout?: number; description?: string };
  grep?: { pattern: string; path?: string };
  glob?: { pattern: string; path?: string };
  ls?: { path?: string };
  calc?: { script: string };
}

/** Common props for tool message components */
export interface ToolMessageProps {
  toolCall: ToolCall;
  result?: ToolResult;
  status?: ToolCallStatus;
  streamingOutput?: string;
  workDir?: string;
  /** Compact row mode: borderless single-line header with inline summary, used inside process groups */
  compact?: boolean;
  /** Sub-agent streaming data (for SubAgentTool during streaming) */
  subAgent?: ToolBlockData['subAgent'];
}

/** Props for collapsible section component */
export interface CollapsibleSectionProps {
  title: React.ReactNode;
  children: React.ReactNode;
  defaultCollapsed?: boolean;
  className?: string;
}