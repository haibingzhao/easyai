import { describe, expect, it } from 'vitest';
import { parseBashArgs, parseMemoryDocument } from './parsers';

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

describe('parseMemoryDocument', () => {
  const doc = (frontmatter: string, body = '## Body\n\ntext') => `---\n${frontmatter}\n---\n\n${body}`;

  it('splits frontmatter fields from the markdown body', () => {
    const parsed = parseMemoryDocument(doc('name: frp-setup\ndescription: frp relay for Mac\ncreated: 2026-01-15'));
    expect(parsed.fields.name).toBe('frp-setup');
    expect(parsed.fields.description).toBe('frp relay for Mac');
    expect(parsed.body).toBe('\n## Body\n\ntext');
    expect(parsed.tags).toEqual([]);
  });

  it('decodes every list item without leaving stray quotes on the inner ones', () => {
    // The store quotes each item, so the whole line starts and ends with a quote that is not one pair:
    // unquoting the line first and then splitting on commas left `"` around every middle item.
    const parsed = parseMemoryDocument(doc('keywords: "frp", "tunnel", "port:8000"'));
    expect(parsed.tags).toEqual(['frp', 'tunnel', 'port:8000']);
  });

  it('keeps a comma inside a quoted item in one piece', () => {
    const parsed = parseMemoryDocument(doc('scenarios: "deploy, then verify"\nkeywords: "a, b", c'));
    expect(parsed.tags).toEqual(['a, b', 'c', 'deploy, then verify']);
  });

  it('reads items written before every element was quoted', () => {
    const parsed = parseMemoryDocument(doc('keywords: frp, tunnel'));
    expect(parsed.tags).toEqual(['frp', 'tunnel']);
  });

  it('unescapes a quote inside an item', () => {
    const parsed = parseMemoryDocument(doc('keywords: "he said \\"hi\\"", plain'));
    expect(parsed.tags).toEqual(['he said "hi"', 'plain']);
  });

  it('returns the whole output as body when there is no frontmatter', () => {
    const parsed = parseMemoryDocument('just prose');
    expect(parsed.fields).toEqual({});
    expect(parsed.body).toBe('just prose');
  });
});
