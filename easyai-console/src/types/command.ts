// Slash command type definitions

export type CommandCategory = 'USER' | 'SKILL' | 'MCP' | 'BUILTIN';

export interface SlashCommand {
  name: string;
  description: string | null;
  aliases: string[];
  category: CommandCategory;
  hints: string[];
  /** Normalized absolute SKILL.md path for SKILL commands. */
  source?: string;
  scope?: 'GLOBAL' | 'PROJECT';
  projectPath?: string;
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
