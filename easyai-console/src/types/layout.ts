export type NavPage = 'chat' | 'workflow' | 'agents' | 'commands' | 'mcp' | 'models' | 'config' | 'memories';

export interface NavItem {
  id: string;
  label: string;
  labelKey: string;
  icon: string;
  path: string;
}
