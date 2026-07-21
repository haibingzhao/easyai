import type { NavItem } from '@/types/layout';

export const NAV_ITEMS: NavItem[] = [
  { id: 'chat', label: 'Chat', labelKey: 'Chat', icon: 'MessageSquare', path: '/' },
  { id: 'workflow', label: 'Workflow', labelKey: 'Workflow', icon: 'GitBranch', path: '/workflow' },
  { id: 'agents', label: 'Agents', labelKey: 'Agents', icon: 'Bot', path: '/agents' },
  { id: 'commands', label: 'Commands', labelKey: 'Commands', icon: 'Terminal', path: '/commands' },
  { id: 'memories', label: 'Memories', labelKey: 'Memories', icon: 'Brain', path: '/memories' },
  { id: 'mcp', label: 'MCP', labelKey: 'MCP', icon: 'Blocks', path: '/mcp' },
  { id: 'models', label: 'Models', labelKey: 'Models', icon: 'Cpu', path: '/models' },
  { id: 'config', label: 'Config', labelKey: 'Config', icon: 'Settings', path: '/settings' },
];
