import { describe, expect, it } from 'vitest';
import { parseBashArgs } from './parsers';

describe('parseBashArgs', () => {
  it('reads the same command and description from card JSON and permission arguments', () => {
    const args = {
      command: 'git diff --stat &&\ngit status --short',
      description: '检查本地变更，不修改文件。',
      timeout: 60,
    };
    expect(parseBashArgs(args)).toEqual(args);
    expect(parseBashArgs(JSON.stringify(args))).toEqual(args);
  });

  it.each([undefined, null, '', '  \n  ', 42, { text: 'not a string' }])(
    'omits an empty or non-string description: %j',
    (description) => {
      expect(parseBashArgs({ command: 'pwd', description }).description).toBeUndefined();
    },
  );

  it('keeps the complete command and trims only the description', () => {
    const command = `printf '%s\\n' '${'long-value-'.repeat(200)}'\n`;
    const args = parseBashArgs({ command, description: '  展示文本。\n第二行说明。  ' });
    expect(args.command).toBe(command);
    expect(args.description).toBe('展示文本。\n第二行说明。');
  });

  it.each(['git status', '{"command":', 'null', '[]', '42', ''])('tolerates raw or incomplete arguments: %s', (args) => {
    expect(parseBashArgs(args)).toEqual({ command: args });
  });

  it('tolerates missing permission arguments', () => {
    expect(parseBashArgs(undefined)).toEqual({ command: '' });
  });

  it.each([undefined, null, 0, -1, '60'])('uses the caller default for invalid timeout: %j', (timeout) => {
    expect(parseBashArgs({ command: 'pwd', timeout }).timeout).toBeUndefined();
  });
});
