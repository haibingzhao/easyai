import type React from 'react';
import type { NavItem } from '@/types/layout';

export const NAV_ITEMS: NavItem[] = [
  { id: 'chat', label: 'Chat', labelKey: 'Chat', icon: 'MessageSquare', path: '/' },
  { id: 'workflow', label: 'Workflow', labelKey: 'Workflow', icon: 'GitBranch', path: '/workflow' },
  { id: 'agents', label: 'Agents', labelKey: 'Agents', icon: 'Bot', path: '/agents' },
  { id: 'commands', label: 'Commands', labelKey: 'Commands', icon: 'Terminal', path: '/commands' },
  { id: 'memories', label: 'Memories', labelKey: 'Memories', icon: 'Brain', path: '/memories' },
  { id: 'knowledge', label: 'Knowledge', labelKey: 'Knowledge Base', icon: 'BookOpen', path: '/knowledge' },
  { id: 'mcp', label: 'MCP', labelKey: 'MCP', icon: 'Blocks', path: '/mcp' },
  { id: 'models', label: 'Models', labelKey: 'Models', icon: 'Cpu', path: '/models' },
  { id: 'config', label: 'Config', labelKey: 'Config', icon: 'Settings', path: '/settings' },
];

/**
 * Extra navigation items that external consumers (e.g. trading-console)
 * can push to extend the sidebar. Rendered before the default items.
 */
export const EXTRA_NAV_ITEMS: NavItem[] = [];

/**
 * Icon registry. External consumers can register additional icons
 * via `registerIcons()` so the Sidebar can render them by name.
 */
export const ICON_REGISTRY: Record<string, React.ComponentType<{ className?: string }>> = {};

/**
 * Register custom icons for use in NavItem.icon.
 * Call this before rendering the app (e.g. in main.tsx / App.tsx).
 *
 * @example
 * import { LayoutDashboard, TrendingUp } from 'lucide-react';
 * registerIcons({ LayoutDashboard, TrendingUp });
 */
export function registerIcons(icons: Record<string, React.ComponentType<{ className?: string }>>): void {
  Object.assign(ICON_REGISTRY, icons);
}

/**
 * App-level configuration that external consumers can override.
 */
export const APP_CONFIG = {
  appName: 'Easy AI',
};
