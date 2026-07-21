/**
 * Permission action types for tool execution authorization.
 */
export type PermissionAction = 'ALLOW' | 'ASK' | 'DENY';

/**
 * Permission rule DTO matching backend structure.
 */
export interface PermissionRuleDto {
  /** Permission type, e.g., "tool.execute.shell" */
  permission: string;
  /** Pattern to match, e.g., "*" or specific command */
  pattern: string;
  /** Action to take: ALLOW, ASK, or DENY */
  action: PermissionAction;
}

/**
 * Tool permission info aggregated from backend.
 */
export interface ToolPermissionInfo {
  name: string;
  description: string;
  category?: string;
  rules: PermissionRuleDto[];
}

/**
 * Category-level permission state for the AutoApprovePanel.
 * Maps each tool category to its permission action.
 */
export type CategoryPermissionMap = Record<string, PermissionAction>;

/**
 * Permission settings for the AutoApprovePanel.
 * Matches the backend PermissionSettingsDto structure.
 */
export interface PermissionSettingsDto {
  projectPath: string;
  readFileProject: boolean;
  readFileAll: boolean;
  writeFileProject: boolean;
  writeFileAll: boolean;
  executeSafeCommands: boolean;
  executeAllCommands: boolean;
  useBrowser: boolean;
  useMcp: boolean;
  readOtherPaths: string[];
  writeOtherPaths: string[];
  otherCommands: string[];
}

/**
 * File tree node for the project structure browser.
 */
export interface FileNodeDto {
  name: string;
  path: string;
  type: 'file' | 'directory';
  children?: FileNodeDto[];
}

/**
 * Request body for updating a single permission setting.
 */
export interface UpdateSettingRequest {
  key: string;
  value: boolean | string[];
}
