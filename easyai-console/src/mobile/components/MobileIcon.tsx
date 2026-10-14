import React from 'react';
import {
  Blocks,
  BookOpen,
  Bot,
  Brain,
  Clock,
  Cpu,
  GitBranch,
  LayoutGrid,
  ListTodo,
  MessageSquare,
  MoreHorizontal,
  Settings,
  Sparkles,
  Terminal,
  User,
  Wrench,
} from 'lucide-react';
import { ICON_REGISTRY } from '@/constants/navigation';

const MOBILE_ICON_MAP: Record<string, React.ComponentType<{ className?: string }>> = {
  Blocks,
  BookOpen,
  Bot,
  Brain,
  Clock,
  Cpu,
  GitBranch,
  LayoutGrid,
  ListTodo,
  MessageSquare,
  MoreHorizontal,
  Settings,
  Sparkles,
  Terminal,
  User,
  Wrench,
};

/** Built-in map first, then the host registry — same lookup order as the desktop Sidebar. */
export function resolveMobileIcon(name: string): React.ComponentType<{ className?: string }> | undefined {
  return MOBILE_ICON_MAP[name] ?? ICON_REGISTRY[name];
}

export const MobileIcon: React.FC<{ name: string; className?: string }> = ({ name, className }) => {
  const Icon = resolveMobileIcon(name);
  return Icon ? <Icon className={className} /> : null;
};
