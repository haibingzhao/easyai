// Slash command type definitions

export type CommandCategory = 'USER' | 'SKILL' | 'MCP' | 'BUILTIN';

export interface SlashCommand {
  name: string;
  description: string | null;
  aliases: string[];
  category: CommandCategory;
  hints: string[];
  /** SKILL commands only: the installed skill this command loads, addressed by name. */
  skillName?: string;
  /** SKILL commands only: the skill comes from the read-only shared layer. */
  shared?: boolean;
}

// User command CRUD types (DB-persisted)

export interface UserCommand {
  id: string;
  name: string;
  description: string | null;
  aliases: string[];
  template: string;
  hints: string[];
}

export interface UserCommandCreateRequest {
  name: string;
  description?: string;
  aliases?: string[];
  template?: string;
}
