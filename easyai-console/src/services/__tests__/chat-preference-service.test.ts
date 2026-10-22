import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setDefaultAssetScope } from '../api-client';
import { chatPreferenceService } from '../chat-preference-service';
import type { ChatPreference } from '@/types/agent';

/**
 * The host's `setDefaultAssetScope('group')` is a *write* default. Reads must keep naming nothing
 * when they want the effective layer, otherwise a member's personal Chat defaults get ignored.
 */
const row = (overrides: Partial<ChatPreference> = {}): ChatPreference => ({
  defaultAgentId: 'ask-agent',
  hiddenAgentIds: [],
  effectiveSource: 'user',
  source: 'user',
  ...overrides,
});

function requestedUrl(index = 0): string {
  const [url] = vi.mocked(fetch).mock.calls[index];
  return String(url);
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify(row())))));
});

afterEach(() => {
  vi.unstubAllGlobals();
  setDefaultAssetScope(undefined);
});

describe('chatPreferenceService scope', () => {
  it('reads the effective preference without a scope even when the host defaults writes to group', async () => {
    setDefaultAssetScope('group');

    await chatPreferenceService.get();

    expect(requestedUrl()).toBe('/api/chat-preferences');
  });

  it('names the bucket a scoped read edits', async () => {
    setDefaultAssetScope('group');

    await chatPreferenceService.get('personal');
    await chatPreferenceService.get('group');

    expect(requestedUrl(0)).toBe('/api/chat-preferences?scope=personal');
    expect(requestedUrl(1)).toBe('/api/chat-preferences?scope=group');
  });

  it('keeps the host write default for saves', async () => {
    setDefaultAssetScope('group');

    await chatPreferenceService.save({ defaultAgentId: null, hiddenAgentIds: ['ask-agent'] });

    expect(requestedUrl()).toBe('/api/chat-preferences?scope=group');
  });

  it('lets an explicit save scope override the host default', async () => {
    setDefaultAssetScope('group');

    await chatPreferenceService.save({ defaultAgentId: 'ask-agent', hiddenAgentIds: [] }, 'personal');

    expect(requestedUrl()).toBe('/api/chat-preferences?scope=personal');
  });
});
