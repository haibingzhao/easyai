export type NavPage = 'chat' | 'workflow' | 'agents' | 'commands' | 'mcp' | 'models' | 'config' | 'memories';

export interface NavItem {
  id: string;
  label: string;
  labelKey: string;
  icon: string;
  path: string;
  /**
   * Optional visibility predicate evaluated on every sidebar render.
   *
   * Lets a consumer hide entries dynamically (e.g. by the current user's
   * effective permissions). Omit it for a permanently visible entry.
   */
  visible?: () => boolean;
}
