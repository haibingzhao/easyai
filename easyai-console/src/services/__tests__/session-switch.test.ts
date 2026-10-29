import { beforeEach, describe, expect, it, vi } from 'vitest';
import { loadTranscriptSession, switchToSession } from '@/services/session-switch';
import { sessionService } from '@/services/session-service';
import type { SessionDetail } from '@/services/session-service';
import { getStreamingStatus } from '@/services/chat-service';
import { currentEpoch } from '@/services/session-epoch';
import { useChatStore } from '@/services/stores/chat-store';

vi.mock('@/services/session-service', () => ({
  sessionService: { getSessionDetail: vi.fn(), getGroupedTodos: vi.fn() },
}));
vi.mock('@/services/chat-service', () => ({ getStreamingStatus: vi.fn() }));
vi.mock('@/services/checkpoint-service', () => ({ getCheckpoints: vi.fn(), getFileReviewState: vi.fn() }));
vi.mock('@/services/stores/session-store', () => ({ useSessionStore: { getState: () => ({ setCurrentSessionId: vi.fn() }) } }));
vi.mock('@/services/stores/agent-store', () => ({ useAgentStore: { getState: () => ({ agents: [], selectAgent: vi.fn() }) } }));
vi.mock('@/services/stores/settings-store', () => ({ useSettingsStore: { getState: () => ({ setSelectedModelConfig: vi.fn() }) } }));
vi.mock('@/services/stores/project-store', () => ({ adoptSessionWorkspace: vi.fn() }));
vi.mock('@/services/stores/nav-store', () => ({ useNavStore: { getState: () => ({ setSelectedFile: vi.fn() }) } }));
vi.mock('@/constants/navigation', () => ({ APP_CONFIG: { showWorkspacePanels: false } }));

function detail(id: string): SessionDetail {
  return {
    id, title: id, createdAt: 1, updatedAt: 1, messageCount: 1,
    messages: [{ id: `${id}-message`, role: 'USER', content: [{ type: 'text', text: id }], timestamp: 1 }],
    newestMessageSeq: 1, contentRevision: 0,
  };
}

beforeEach(() => {
  vi.resetAllMocks();
  useChatStore.getState().clearChat();
  vi.mocked(sessionService.getSessionDetail).mockImplementation(async (id) => detail(id));
  vi.mocked(sessionService.getGroupedTodos).mockResolvedValue({ main: [], subAgents: [] });
  vi.mocked(getStreamingStatus).mockResolvedValue({ streaming: false, local: false });
});

describe('session switching', () => {
  it('keeps the current live session untouched instead of reloading it', async () => {
    const store = useChatStore.getState();
    store.setSessionId('A');
    store.setStreaming(true);
    store.appendToTextBlock('live content', 'live');
    const epoch = currentEpoch();
    await switchToSession('A');
    expect(sessionService.getSessionDetail).not.toHaveBeenCalled();
    expect(getStreamingStatus).not.toHaveBeenCalled();
    expect(currentEpoch()).toBeGreaterThan(epoch);
    expect(useChatStore.getState().streamingBlocks).toHaveLength(1);
    expect(useChatStore.getState().isStreaming).toBe(true);
  });

  it('cancels a pending switch when the user reselects the current live session', async () => {
    const store = useChatStore.getState();
    store.setSessionId('A');
    store.setStreaming(true);
    store.appendToTextBlock('live content', 'live');
    let resolve!: (value: SessionDetail) => void;
    vi.mocked(sessionService.getSessionDetail).mockReturnValueOnce(new Promise((done) => { resolve = done; }));
    const pendingSwitch = switchToSession('B');
    await switchToSession('A');
    resolve(detail('B'));
    await pendingSwitch;
    expect(useChatStore.getState().sessionId).toBe('A');
    expect(useChatStore.getState().streamingBlocks).toHaveLength(1);
    expect(useChatStore.getState().isStreaming).toBe(true);
  });

  it('exits transcript mode when opening a regular session', async () => {
    useChatStore.getState().setSessionId('transcript');
    useChatStore.getState().setTranscriptMode(true);
    await switchToSession('A');
    expect(useChatStore.getState().transcriptMode).toBe(false);
    expect(useChatStore.getState().sessionId).toBe('A');
    expect(useChatStore.getState()._snapshotSessionId).toBe('A');
  });

  it('exits transcript mode even when opening the same session id', async () => {
    useChatStore.getState().setSessionId('A');
    useChatStore.getState().setTranscriptMode(true);
    await switchToSession('A');
    expect(sessionService.getSessionDetail).toHaveBeenCalledWith('A');
    expect(useChatStore.getState().transcriptMode).toBe(false);
    expect(useChatStore.getState().messages[0]).toMatchObject({ role: 'user', content: 'A' });
  });

  it('can explicitly replace a live session with its read-only transcript', async () => {
    const store = useChatStore.getState();
    store.setSessionId('A');
    store.setStreaming(true);
    store.appendToTextBlock('live content', 'live');
    await loadTranscriptSession('A');
    expect(useChatStore.getState().transcriptMode).toBe(true);
    expect(useChatStore.getState().streamingBlocks).toEqual([]);
    expect(useChatStore.getState().isStreaming).toBe(false);
    expect(useChatStore.getState().messages[0]).toMatchObject({ role: 'user', content: 'A' });
  });

  it('rejects a delayed detail response after another session was selected', async () => {
    let resolve!: (value: SessionDetail) => void;
    vi.mocked(sessionService.getSessionDetail).mockReturnValueOnce(new Promise((done) => { resolve = done; }));
    const oldLoad = switchToSession('A');
    await switchToSession('B');
    resolve(detail('A'));
    await oldLoad;
    expect(useChatStore.getState().sessionId).toBe('B');
    expect(useChatStore.getState().messages[0]).toMatchObject({ role: 'user', content: 'B' });
    expect(useChatStore.getState()._snapshotSessionId).toBe('B');
  });
});
