/**
 * Tool图标映射
 */

import type { LucideIcon } from 'lucide-react';
import { 
  Terminal, 
  FileText, 
  FileEdit, 
  FilePlus2, 
  Search, 
  FolderOpen, 
  FolderSearch, 
  File,
  Target,
  Brain,
  Calculator
} from 'lucide-react';

export const TOOL_ICONS: Record<string, LucideIcon> = {
  bash: Terminal,
  read: FileText,
  write: FilePlus2,
  edit: FileEdit,
  grep: Search,
  glob: FolderSearch,
  ls: FolderOpen,
  goal: Target,
  memory_search: Brain,
  memory_read: Brain,
  memory_write: Brain,
  memory_list: Brain,
  calc: Calculator,
};

/**
 * 获取工具对应的图标
 * @param toolName 工具名称
 * @returns Lucide图标组件
 */
export function getToolIcon(toolName: string): LucideIcon {
  return TOOL_ICONS[toolName] || File;
}

/**
 * 获取工具显示名称
 * @param toolName 工具名称
 * @returns 本地化的显示名称
 */
export function getToolDisplayName(toolName: string): string {
  const displayNames: Record<string, string> = {
    bash: 'Bash',
    read: 'Read',
    write: 'Write',
    edit: 'Edit',
    grep: 'Grep',
    glob: 'Glob',
    ls: 'Ls',
    goal: 'Goal',
    memory_search: 'Memory Search',
    memory_read: 'Memory Read',
    memory_write: 'Memory Write',
    memory_list: 'Memory List',
    calc: 'Calculator',
  };
  return displayNames[toolName] || toolName;
}