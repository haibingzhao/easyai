import type { CommandCategory, SlashCommand } from '@/types/command';

/** History has a name, not necessarily a known command category. Never guess BUILTIN. */
export interface CommandIdentity {
  name: string;
  category?: CommandCategory;
  source?: string;
  scope?: 'GLOBAL' | 'PROJECT';
  projectPath?: string;
}

export interface ParsedCommand {
  command: CommandIdentity;
  args: string;
}

const COMMAND_NAME = '[a-zA-Z_][a-zA-Z0-9_:-]*';
const SKILL_PREFIX = new RegExp(`^\\[/(${COMMAND_NAME})\\]\\(skill:([^\\s()]+)\\)(?:\\s+|$)([\\s\\S]*)$`);
const PLAIN_PREFIX = new RegExp(`^/(${COMMAND_NAME})(?:\\s+|$)([\\s\\S]*)$`);

/** Skill links are an inert text protocol, not URLs or attachment references. */
export function parseCommand(text: string): ParsedCommand | null {
  const skill = text.match(SKILL_PREFIX);
  if (skill) {
    try {
      // Exactly one decode: a literal "%20" in a filename must remain "%20".
      const source = decodeURIComponent(skill[2]);
      if (!isAbsoluteSkillPath(source)) return null;
      return { command: { name: skill[1], category: 'SKILL', source }, args: skill[3] };
    } catch {
      return null;
    }
  }
  const plain = text.match(PLAIN_PREFIX);
  if (plain) return { command: { name: plain[1] }, args: plain[2] };
  // Legacy /goal中文 syntax only; do not truncate other command tokens.
  const goal = text.match(/^\/goal(?=[\u3400-\u9fff])([\s\S]*)$/);
  return goal ? { command: { name: 'goal' }, args: goal[1] } : null;
}

function isAbsoluteSkillPath(source: string): boolean {
  return /^(?:\/|[A-Za-z]:[\\/])/.test(source)
    && /[\\/]SKILL\.md$/.test(source)
    && !/[\u0000-\u001f\u007f]/.test(source);
}

export function serializeCommand(command: CommandIdentity | null | undefined, args: string): string {
  if (!command) return args.trim();
  const prefix = command.source
    ? `[/${command.name}](skill:${encodeURIComponent(command.source).replace(/[!'()*]/g, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`)})`
    : `/${command.name}`;
  return args.trim() ? `${prefix} ${args.trim()}` : prefix;
}

/** Derive a display-only location for historical links even when no menu is loaded. */
export function commandLocation(command: CommandIdentity): string {
  if (command.scope === 'GLOBAL') return 'GLOBAL';
  const projectPath = command.projectPath ?? command.source?.match(/^(.*)[\\/]\.(?:easyai|agents|claude)[\\/]skills[\\/]/)?.[1];
  if (projectPath) {
    // Known home skill roots are GLOBAL; the source remains authoritative either way.
    if (!command.scope && /^(?:\/Users\/[^/]+|\/home\/[^/]+|[A-Za-z]:[\\/]Users[\\/][^\\/]+)$/.test(projectPath)) return 'GLOBAL';
    return projectPath.split(/[\\/]/).filter(Boolean).pop() ?? projectPath;
  }
  return command.source?.split(/[\\/]/).filter(Boolean).slice(-3, -2)[0] ?? 'SKILL';
}

export function commandLabel(command: CommandIdentity): string {
  return `/${command.name}${command.source ? `·${commandLocation(command)}` : ''}`;
}

export function commandGroup(command: SlashCommand): string {
  if (command.category === 'SKILL') return command.scope === 'GLOBAL' ? 'Skills (Global)' : 'Skills (Project)';
  return command.category === 'USER' ? 'Commands (User)' : 'Commands';
}

/** This is the only ordering used by both the popover and keyboard selection. */
export function flattenCommands(commands: SlashCommand[]): SlashCommand[] {
  return ['Commands (User)', 'Commands', 'Skills (Global)', 'Skills (Project)']
    .flatMap((group) => commands.filter((command) => commandGroup(command) === group));
}

/** /goal changes session state; editing a queued invocation cannot safely replay it. */
export function isSideEffectCommand(content: string, category?: CommandCategory): boolean {
  const parsed = parseCommand(content);
  if (!parsed || parsed.command.source) return false;
  if (category && category !== 'BUILTIN') return false;
  return parsed.command.name === 'goal';
}
