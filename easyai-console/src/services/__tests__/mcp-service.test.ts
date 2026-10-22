import { describe, it, expect } from 'vitest';
import { changedMcpServerFields, dedupeMcpServers, mcpServerKey, mcpServerScope } from '../mcp-service';
import type { McpServerEditDraft } from '../mcp-service';
import type { McpServerDto } from '@/types/mcp';

/** A response from a backend older than the `ownerId` field; the DTO cannot express the missing value. */
const legacy = { name: 'shared' } as unknown as { ownerId: string; name: string };

function server(
  ownerId: string,
  status: McpServerDto['status'],
  name = 'shared',
  overrides: Partial<McpServerDto> = {},
): McpServerDto {
  return { name, ownerId, type: 'remote', enabled: true, status, tools: [], prompts: [], ...overrides };
}

function draft(overrides: Partial<McpServerEditDraft> = {}): McpServerEditDraft {
  return { url: 'https://example.com', headers: {}, command: [], env: {}, timeoutSeconds: 120, ...overrides };
}

describe('mcpServerScope', () => {
  it("targets the caller's own row in the personal bucket", () => {
    expect(mcpServerScope({ ownerId: 'alice' }, 'alice')).toBe('personal');
  });

  it('targets a same-named row from another visible bucket as group', () => {
    expect(mcpServerScope({ ownerId: 'grp-1' }, 'alice')).toBe('group');
  });

  it('treats a legacy row without ownerId as personal', () => {
    expect(mcpServerScope(legacy, 'alice')).toBe('personal');
  });

  it('defaults to personal before the caller is known', () => {
    expect(mcpServerScope({ ownerId: 'grp-1' }, undefined)).toBe('personal');
  });
});

describe('mcpServerKey', () => {
  it('keeps same-named rows from different buckets distinct', () => {
    expect(mcpServerKey({ ownerId: 'alice', name: 'shared' }))
      .not.toBe(mcpServerKey({ ownerId: 'grp-1', name: 'shared' }));
  });

  it('uses a legacy prefix when ownerId is missing', () => {
    expect(mcpServerKey(legacy)).toBe('legacy:shared');
  });
});

describe('changedMcpServerFields', () => {
  it('sends nothing when the draft matches the opening row', () => {
    const row = server('alice', 'connected', 's', {
      url: 'https://example.com',
      headers: { Authorization: 'Bearer a' },
    });

    expect(changedMcpServerFields(row, draft({
      url: ' https://example.com ',
      headers: { Authorization: 'Bearer a' },
    }))).toEqual({});
  });

  it('sends only the edited URL of a remote server', () => {
    const row = server('alice', 'connected', 's', {
      url: 'https://old.example.com',
      headers: { Authorization: 'Bearer a' },
      command: ['stale-command'],
      env: { STALE: '1' },
    });

    expect(changedMcpServerFields(row, draft({
      url: 'https://new.example.com',
      headers: { Authorization: 'Bearer a' },
      command: ['ignored-for-remote'],
      env: { IGNORED: '1' },
    }))).toEqual({ url: 'https://new.example.com' });
  });

  it('ignores header order', () => {
    const row = server('alice', 'connected', 's', {
      url: 'https://example.com',
      headers: { A: '1', B: '2' },
    });

    expect(changedMcpServerFields(row, draft({
      url: 'https://example.com',
      headers: { B: '2', A: '1' },
    }))).toEqual({});
  });

  it('keeps a refreshed field when only the timeout was edited', () => {
    const row = server('alice', 'connected', 's', {
      url: 'https://old.example.com',
      headers: { Authorization: 'Bearer a' },
      timeoutSeconds: 120,
    });
    const latest = { ...row, url: 'https://refreshed.example.com' };
    const changes = changedMcpServerFields(row, draft({
      url: 'https://old.example.com',
      headers: { Authorization: 'Bearer a' },
      timeoutSeconds: 300,
    }));

    expect({ ...latest, ...changes }).toMatchObject({
      url: 'https://refreshed.example.com',
      timeoutSeconds: 300,
    });
  });

  it('normalizes whitespace before deciding a local command changed', () => {
    const row = server('alice', 'connected', 's', {
      type: 'local',
      command: ['npx', 'server'],
      url: undefined,
      headers: {},
    });

    expect(changedMcpServerFields(row, draft({
      url: '',
      command: [' npx ', 'server', ' ', ''],
    }))).toEqual({});
  });

  it('sends only the edited local env map', () => {
    const row = server('alice', 'connected', 's', {
      type: 'local',
      command: ['npx'],
      env: { A: '1' },
      url: undefined,
      headers: {},
    });

    expect(changedMcpServerFields(row, draft({
      url: '',
      command: ['npx'],
      env: { A: '1', B: '2' },
    }))).toEqual({ env: { A: '1', B: '2' } });
  });
});

describe('dedupeMcpServers', () => {
  it("shows the connected row when the caller's same-named row failed", () => {
    const rows = dedupeMcpServers([server('alice', 'failed'), server('grp-1', 'connected')], 'alice');

    expect(rows.map(row => row.ownerId)).toEqual(['grp-1']);
  });

  it("prefers the caller's row when both copies are connected", () => {
    const rows = dedupeMcpServers([server('grp-1', 'connected'), server('alice', 'connected')], 'alice');

    expect(rows.map(row => row.ownerId)).toEqual(['alice']);
  });

  it("falls back to the caller's row when neither copy is connected", () => {
    const rows = dedupeMcpServers([server('grp-1', 'failed'), server('alice', 'disabled')], 'alice');

    expect(rows.map(row => row.ownerId)).toEqual(['alice']);
  });

  it('keeps servers with distinct names', () => {
    const rows = dedupeMcpServers(
      [server('alice', 'connected', 'a'), server('grp-1', 'connected', 'b')],
      'alice',
    );

    expect(rows.map(row => row.name).sort()).toEqual(['a', 'b']);
  });
});
