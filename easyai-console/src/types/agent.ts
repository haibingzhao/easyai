/**
 * Agent type: controls visibility and invocation rules.
 * - PRIMARY: Only usable as a main agent selected by the user.
 * - SUBAGENT: Only invocable via the subagent tool, not selectable by the user.
 * - TEAM: Team leader that coordinates member agents via delegate/wait/resume tools.
 *   Selectable by the user in Chat (like PRIMARY), requires memberIds.
 * - ALL: Can be used as both primary agent and sub-agent.
 */
export type AgentType = 'PRIMARY' | 'SUBAGENT' | 'TEAM' | 'ALL';

/**
 * Agent execution environment compatibility.
 * - CHAT: Prompt uses only PromptContext variables. Usable in Chat sessions.
 * - SWARM: Prompt may use Swarm-specific variables. Designed for Swarm workflows.
 * - BOTH: Compatible with both Chat and Swarm environments.
 */
export type AgentEnv = 'CHAT' | 'SWARM' | 'BOTH';

/**
 * Target type for agent tool configuration.
 */
export type TargetType = 'TOOL' | 'SUBAGENT' | 'SKILL' | 'MCP' | 'COMMAND' | 'MEMBER';

/**
 * Tool information from the API.
 */
export interface ToolInfo {
  name: string;
  description: string;
  permissionCategory?: string;
  uiRenderer?: string;
  isDefaultTool?: boolean;
}

/**
 * Inline custom agent specification.
 * Used for defining sub-agents or team members directly within a parent agent.
 */
export interface InlineAgentSpec {
  name: string;
  description: string;
  systemPrompt: string;
  toolNames: string[];
  skillNames: string[];
  mcpConfigs: McpBindingDto[];
}

/**
 * Agent DTO from the API.
 */
export interface AgentDto {
  id: string;
  name: string;
  agentType: AgentType;
  agentContext: AgentEnv;
  description: string | null;
  customInstructions: string | null;
  promptTemplate: string | null;
  toolNames: string[];
  subAgentIds: string[];
  skillNames: string[];
  mcpConfigs: McpBindingDto[];
  commandNames: string[];
  memberIds: string[];
  customSubAgents: InlineAgentSpec[];
  customMembers: InlineAgentSpec[];
  maxIterations: number;
  maxSubAgentDepth: number;
  color: string | null;
  enabled: boolean;
  instructionsEnabled: boolean;
  inputSchema: string | null;
  outputSchema: string | null;
  builtin: boolean;
  createdAt: number | null;
  updatedAt: number | null;
}

/**
 * Request body for creating/updating an agent.
 */
export interface AgentCreateRequest {
  id: string;
  name: string;
  agentType: AgentType;
  agentContext?: AgentEnv;
  description?: string;
  customInstructions?: string;
  promptTemplate?: string;
  toolNames: string[];
  subAgentIds: string[];
  skillNames: string[];
  mcpConfigs: McpBindingDto[];
  commandNames: string[];
  /** Team member agent IDs. Required when agentType = TEAM; members must be existing non-TEAM agents. */
  memberIds?: string[];
  /** Inline custom sub-agents defined directly within this agent. */
  customSubAgents?: InlineAgentSpec[];
  /** Inline custom team members defined directly within this agent. */
  customMembers?: InlineAgentSpec[];
  maxIterations: number;
  maxSubAgentDepth: number;
  color?: string;
  enabled: boolean;
  instructionsEnabled?: boolean;
  inputSchema?: string;
  outputSchema?: string;
}

/**
 * Agent tool/subagent configuration item.
 */
export interface AgentToolConfig {
  agentId: string;
  targetType: TargetType;
  targetName: string;
  metadata?: string;
}

/**
 * MCP binding DTO: server name + optional tool whitelist.
 * Empty toolNames = all tools from this server are allowed.
 * Empty promptNames = all prompts from this server are allowed.
 */
export interface McpBindingDto {
  serverName: string;
  toolNames: string[];
  promptNames: string[];
}

/**
 * Request body for saving agent configs.
 */
export interface AgentConfigsRequest {
  targetType: TargetType;
  targetNames: string[];
}

/**
 * Skill information from the API.
 */
export interface SkillInfo {
  name: string;
  description: string | null;
  tags: string[];
}

/**
 * Response from Jinja2 template syntax validation.
 */
export interface ValidateTemplateResponse {
  valid: boolean;
  errors?: TemplateValidationError[];
}

export interface TemplateValidationError {
  message: string;
  lineNumber?: number;
  startPosition?: number;
  fieldName?: string;
  severity?: string;
}

/** A resource item (tool or MCP server) shown in import dialogs. */
export interface ResourceItem {
  name: string;
  available: boolean;
  checked: boolean;
}