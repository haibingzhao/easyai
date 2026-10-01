import type { CommandCategory, SlashCommand } from '@/types/command';
import { i18n } from '@/utils/i18n';

/** History has a name, not necessarily a known command category. Never guess BUILTIN. */
export interface CommandIdentity {
  name: string;
  category?: CommandCategory;
  /** Set for skill-derived commands: the installed skill this invocation loads. */
  skillName?: string;
  /** Menu rows only: the skill comes from the read-only shared layer. */
  shared?: boolean;
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
      // Exactly one decode: a literal "%20" in a name must remain "%20".
      const reference = decodeURIComponent(skill[2]);
      // Old links carried the absolute SKILL.md path. The bracket label was always the skill name,
      // so those render and re-serialize as the current name-only form.
      const legacyPath = reference.includes('/') || reference.includes('\\');
      if (!reference || legacyPath && !reference.endsWith('SKILL.md')) return null;
      return {
        command: { name: skill[1], category: 'SKILL', skillName: legacyPath ? skill[1] : reference },
        args: skill[3],
      };
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

export function serializeCommand(command: CommandIdentity | null | undefined, args: string): string {
  if (!command) return args.trim();
  const prefix = command.skillName
    ? `[/${command.name}](skill:${encodeURIComponent(command.skillName).replace(/[!'()*]/g, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`)})`
    : `/${command.name}`;
  return args.trim() ? `${prefix} ${args.trim()}` : prefix;
}

export function commandLabel(command: CommandIdentity): string {
  return command.skillName ? `/${command.name}·skill` : `/${command.name}`;
}

export function commandTooltip(command: CommandIdentity): string {
  if (!command.skillName) return `/${command.name}`;
  return `${command.shared ? i18n('Shared skill') : i18n('Skill')}: ${command.skillName}`;
}

/** Identity of a menu row, as the invocation token needs it. */
export function commandIdentity(command: SlashCommand): CommandIdentity {
  return {
    name: command.name,
    category: command.category,
    skillName: command.skillName,
    shared: command.shared,
  };
}

export function commandGroup(command: SlashCommand): string {
  if (command.category === 'SKILL') return 'Skills';
  return command.category === 'USER' ? 'Commands (User)' : 'Commands';
}

/** This is the only ordering used by both the popover and keyboard selection. */
export function flattenCommands(commands: SlashCommand[]): SlashCommand[] {
  return ['Commands (User)', 'Commands', 'Skills']
    .flatMap((group) => commands.filter((command) => commandGroup(command) === group));
}

/** /goal changes session state; editing a queued invocation cannot safely replay it. */
export function isSideEffectCommand(content: string, category?: CommandCategory): boolean {
  const parsed = parseCommand(content);
  if (!parsed || parsed.command.skillName) return false;
  if (category && category !== 'BUILTIN') return false;
  return parsed.command.name === 'goal';
}
