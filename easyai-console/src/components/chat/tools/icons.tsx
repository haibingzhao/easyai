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
  BookOpen,
  Calculator,
  Globe,
  Sparkles,
  Users,
  Clock,
  RefreshCw,
  Network,
  ListTodo,
  Bot,
  CircleHelp,
  Image as ImageIcon,
  AudioLines,
  Video,
  Download,
  Plug,
  Newspaper,
  TrendingUp,
  Database,
  Mail,
  CalendarDays,
  CodeXml,
  MessageSquare
} from 'lucide-react';

export const TOOL_ICONS: Record<string, LucideIcon> = {
  bash: Terminal,
  read: FileText,
  write: FilePlus2,
  edit: FileEdit,
  grep: Search,
  glob: FolderSearch,
  ls: FolderOpen,
  todo_write: ListTodo,
  ask_question: CircleHelp,
  task: Bot,
  goal: Target,
  memory_search: Brain,
  memory_read: Brain,
  memory_write: Brain,
  memory_list: Brain,
  knowledge_search: BookOpen,
  knowledge_read: BookOpen,
  calc: Calculator,
  webfetch: Globe,
  load_skill: Sparkles,
  delegate_to_member: Users,
  wait_for_member_events: Clock,
  resume_member: RefreshCw,
  run_swarm: Network,
  generate_image: ImageIcon,
  generate_speech: AudioLines,
  generate_video: Video,
  fetch_media: Download,
};

/** MCP 工具（serverName__toolName）按工具名片段匹配图标，未命中回退 Plug */
const MCP_ICON_RULES: ReadonlyArray<readonly [RegExp, LucideIcon]> = [
  [/news|article/, Newspaper],
  [/search|find|lookup|query/, Search],
  [/price|quote|stock|market|chart|indicator|trend|financial|earning/, TrendingUp],
  [/data|database|sql|table|stat/, Database],
  [/image|photo|picture|screenshot|render/, ImageIcon],
  [/video|movie/, Video],
  [/audio|speech|voice|listen|transcri|synthe/, AudioLines],
  [/mail|email/, Mail],
  [/file|pdf|doc|folder|director/, FileText],
  [/send|post|publish|notify|message/, MessageSquare],
  [/calendar|schedule|date|event/, CalendarDays],
  [/code|repo|commit|branch|deploy|build/, CodeXml],
  [/web|url|http|fetch|browse|scrape|crawl/, Globe],
  [/download|upload|export|import/, Download],
];

/**
 * 获取工具对应的图标
 * @param toolName 工具名称（MCP 工具为 "serverName__toolName"）
 * @returns Lucide图标组件
 */
export function getToolIcon(toolName: string): LucideIcon {
  const exact = TOOL_ICONS[toolName];
  if (exact) return exact;
  if (toolName.includes('__')) {
    const mcpTool = (toolName.split('__').pop() ?? '').replace(/_/g, '-').toLowerCase();
    for (const [pattern, icon] of MCP_ICON_RULES) {
      if (pattern.test(mcpTool)) return icon;
    }
    return Plug;
  }
  return File;
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
    todo_write: 'Todo Write',
    ask_question: 'Ask Question',
    task: 'Sub Agent',
    goal: 'Goal',
    memory_search: 'Memory Search',
    memory_read: 'Memory Read',
    memory_write: 'Memory Write',
    memory_list: 'Memory List',
    knowledge_search: 'Knowledge Search',
    knowledge_read: 'Knowledge Read',
    calc: 'Calculator',
    webfetch: 'Web Fetch',
    load_skill: 'Load Skill',
    delegate_to_member: 'Delegate to Member',
    wait_for_member_events: 'Wait for Member Events',
    resume_member: 'Resume Member',
    run_swarm: 'Run Swarm',
  };
  return displayNames[toolName] || toolName;
}