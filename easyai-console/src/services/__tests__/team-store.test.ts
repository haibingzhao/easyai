import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionService } from '@/services/session-service';
import type { MessageSnapshot, SessionDetail, SessionMessagesAfterResponse } from '@/services/session-service';
import { useTeamStore } from '@/services/stores/team-store';
import type { TeamMemberExecution } from '@/types/team';

vi.mock('@/services/session-service', () => ({
  sessionService: {
    getSessionDetail: vi.fn(),
    getSessionMessagesAfterSeq: vi.fn(),
  },
}));

const getDetail = vi.mocked(sessionService.getSessionDetail);
const getDelta = vi.mocked(sessionService.getSessionMessagesAfterSeq);

function member(memberSessionId: string | null = 'member-a'): TeamMemberExecution {
  return {
    id: `execution-${memberSessionId}`,
    memberId: 'worker',
    memberName: 'Worker',
    memberSessionId,
    round: 1,
    assignment: 'Review changes',
    status: 'RUNNING',
    inputTokens: 0,
    outputTokens: 0,
  };
}

function snapshot(id = 'original', timestamp = 2_000): MessageSnapshot {
  return {
    id,
    role: 'USER',
    content: [{ type: 'text', text: id }],
    timestamp,
    metadata: { source: 'member' },
    fileUrls: { 'storage://image': 'https://objects.example/image' },
  };
}

function detail(overrides: Partial<SessionDetail> = {}): SessionDetail {
  return {
    id: 'member-a',
    title: 'Worker',
    createdAt: 1_000,
    updatedAt: 2_000,
    messageCount: 1,
    messages: [snapshot()],
    newestMessageSeq: 10,
    contentRevision: 0,
    ...overrides,
  };
}

function delta(overrides: Partial<SessionMessagesAfterResponse> = {}): SessionMessagesAfterResponse {
  return {
    sessionId: 'member-a',
    messages: [],
    compactionOccurredAfter: false,
    contentUpdatedAt: 0,
    streaming: true,
    newestSeq: 10,
    revision: 0,
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

beforeEach(() => {
  vi.resetAllMocks();
  useTeamStore.getState().resetTeam();
  getDetail.mockResolvedValue(detail());
});

afterEach(() => {
  useTeamStore.getState().resetTeam();
});

describe('member transcript revisions', () => {
  it.each([0, 7, null, undefined])('stores full-load revision %s with the transcript and cursor', async (contentRevision) => {
    const full = detail({ contentRevision });
    getDetail.mockResolvedValue(full);

    await useTeamStore.getState().selectMember(member());

    expect(useTeamStore.getState()).toMatchObject({
      memberSnapshots: full.messages,
      memberMessages: [{ messageId: 'original', content: 'original' }],
      memberCursor: 10,
      memberRevision: contentRevision ?? null,
      memberMessagesLoading: false,
    });
  });

  it.each([10, 7])('reloads changed revision before accepting an empty delta with seq %i', async (newestSeq) => {
    await useTeamStore.getState().selectMember(member());
    const replacement = snapshot('replacement');
    getDelta.mockResolvedValue(delta({ revision: 1, newestSeq }));
    getDetail.mockResolvedValue(detail({
      messages: [replacement], newestMessageSeq: newestSeq, contentRevision: 1,
    }));

    await useTeamStore.getState().refreshMemberMessages();

    expect(getDelta).toHaveBeenCalledWith('member-a', 10);
    expect(getDetail).toHaveBeenCalledTimes(2);
    expect(useTeamStore.getState()).toMatchObject({
      memberSnapshots: [replacement],
      memberMessages: [{ messageId: 'replacement', content: 'replacement' }],
      memberCursor: newestSeq,
      memberRevision: 1,
    });
  });

  it('reloads a backdated compaction summary and rewritten historical rows', async () => {
    const original = snapshot();
    const retained = snapshot('retained', 3_000);
    getDetail.mockResolvedValue(detail({ messages: [original, retained] }));
    await useTeamStore.getState().selectMember(member());
    const summary = {
      ...snapshot('summary', 1_500),
      metadata: { isCompactionSummary: 'true' },
    };
    const compacted = { ...original, compactedAt: 4_000 };
    const fullMessages = [compacted, summary, retained];
    getDelta.mockResolvedValue(delta({ messages: [summary], revision: 1, newestSeq: 11 }));
    getDetail.mockResolvedValue(detail({
      messages: fullMessages, contentRevision: 1, newestMessageSeq: 11,
    }));

    await useTeamStore.getState().refreshMemberMessages();

    expect(getDetail).toHaveBeenCalledTimes(2);
    expect(useTeamStore.getState()).toMatchObject({
      memberSnapshots: fullMessages,
      memberMessages: [
        { messageId: 'original', compactedAt: 4_000 },
        { messageId: 'summary', timestamp: 1_500, metadata: { isCompactionSummary: 'true' } },
        { messageId: 'retained' },
      ],
      memberCursor: 11,
      memberRevision: 1,
    });
  });

  it('appends unchanged-revision messages, deduplicates the round overlap and preserves existing fields', async () => {
    await useTeamStore.getState().selectMember(member());
    useTeamStore.getState().setTeamFilter('RUNNING');
    const original = useTeamStore.getState().memberSnapshots[0];
    const next = snapshot('next', 2_000);
    getDelta.mockResolvedValue(delta({ messages: [snapshot(), next], newestSeq: 11 }));

    await useTeamStore.getState().refreshMemberMessages();

    expect(getDetail).toHaveBeenCalledTimes(1);
    expect(useTeamStore.getState()).toMatchObject({
      selectedExecutionId: member().id,
      selectedMemberSessionId: 'member-a',
      memberSnapshots: [original, next],
      memberMessages: [{ messageId: 'original' }, { messageId: 'next' }],
      memberCursor: 11,
      memberRevision: 0,
      teamFilter: 'RUNNING',
    });
    expect(useTeamStore.getState().memberSnapshots[0]).toBe(original);
  });

  it.each([
    { name: 'empty delta', messages: [] },
    { name: 'duplicate rows', messages: [snapshot()] },
  ])('advances only the cursor for an unchanged revision: $name', async ({ messages }) => {
    await useTeamStore.getState().selectMember(member());
    const before = useTeamStore.getState();
    getDelta.mockResolvedValue(delta({ messages, newestSeq: 15 }));

    await useTeamStore.getState().refreshMemberMessages();

    const after = useTeamStore.getState();
    expect(getDetail).toHaveBeenCalledTimes(1);
    expect(after.memberSnapshots).toBe(before.memberSnapshots);
    expect(after.memberMessages).toBe(before.memberMessages);
    expect(after.memberCursor).toBe(15);
    expect(after.memberRevision).toBe(0);
  });

  it.each([
    { name: 'empty delta', messages: [] },
    { name: 'duplicate rows', messages: [snapshot()] },
    { name: 'new rows', messages: [snapshot('next')] },
  ])('preserves known cursor and revision when delta metadata is absent: $name', async ({ messages }) => {
    getDetail.mockResolvedValue(detail({ contentRevision: 5 }));
    await useTeamStore.getState().selectMember(member());
    getDelta.mockResolvedValue(delta({ messages, newestSeq: undefined, revision: undefined }));

    await useTeamStore.getState().refreshMemberMessages();

    expect(getDetail).toHaveBeenCalledTimes(1);
    expect(useTeamStore.getState()).toMatchObject({ memberCursor: 10, memberRevision: 5 });
  });

  it('reloads when the delta supplies a revision but the cached baseline has none', async () => {
    getDetail.mockResolvedValue(detail({ contentRevision: null }));
    await useTeamStore.getState().selectMember(member());
    getDelta.mockResolvedValue(delta({ revision: 3 }));
    getDetail.mockResolvedValue(detail({ contentRevision: 3 }));

    await useTeamStore.getState().refreshMemberMessages();

    expect(getDetail).toHaveBeenCalledTimes(2);
    expect(useTeamStore.getState().memberRevision).toBe(3);
  });

  it('keeps the previous revision if a rewrite reload fails so the next poll retries', async () => {
    await useTeamStore.getState().selectMember(member());
    getDelta.mockResolvedValue(delta({ revision: 1 }));
    getDetail.mockRejectedValueOnce(new Error('temporary failure'));

    await useTeamStore.getState().refreshMemberMessages();

    expect(useTeamStore.getState()).toMatchObject({ memberRevision: 0, memberCursor: 10 });
    getDetail.mockResolvedValue(detail({ contentRevision: 1 }));
    await useTeamStore.getState().refreshMemberMessages();
    expect(getDetail).toHaveBeenCalledTimes(3);
    expect(useTeamStore.getState().memberRevision).toBe(1);
  });
});

describe('member selection and stale responses', () => {
  it('clears the revision immediately on selection and ignores the previous full response', async () => {
    await useTeamStore.getState().selectMember(member());
    const oldRequest = deferred<SessionDetail>();
    const newRequest = deferred<SessionDetail>();
    getDetail.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise);
    const oldSelection = useTeamStore.getState().selectMember(member('member-b'));
    const newSelection = useTeamStore.getState().selectMember(member('member-c'));

    expect(useTeamStore.getState()).toMatchObject({
      selectedMemberSessionId: 'member-c', memberRevision: null, memberCursor: null,
      memberSnapshots: [], memberMessages: [], memberMessagesLoading: true,
    });
    oldRequest.resolve(detail({ id: 'member-b', contentRevision: 8 }));
    await oldSelection;
    expect(useTeamStore.getState()).toMatchObject({ memberRevision: null, memberMessagesLoading: true });
    newRequest.resolve(detail({ id: 'member-c', contentRevision: 2 }));
    await newSelection;
    expect(useTeamStore.getState()).toMatchObject({ memberRevision: 2, memberMessagesLoading: false });
  });

  it('clears the revision when selecting a member without a session', async () => {
    await useTeamStore.getState().selectMember(member());

    await useTeamStore.getState().selectMember(member(null));

    expect(useTeamStore.getState()).toMatchObject({
      selectedMemberSessionId: null, memberRevision: null, memberCursor: null,
      memberSnapshots: [], memberMessages: [], memberMessagesLoading: false,
    });
    expect(getDetail).toHaveBeenCalledTimes(1);
  });

  it.each(['clearSelectedMember', 'resetTeam'] as const)('%s clears revision and ignores a pending rewrite reload', async (action) => {
    await useTeamStore.getState().selectMember(member());
    const pending = deferred<SessionDetail>();
    const reloadStarted = deferred<void>();
    getDelta.mockResolvedValue(delta({ revision: 1 }));
    getDetail.mockImplementationOnce(() => {
      reloadStarted.resolve();
      return pending.promise;
    });
    const refreshing = useTeamStore.getState().refreshMemberMessages();
    await reloadStarted.promise;

    useTeamStore.getState()[action]();
    const cleared = useTeamStore.getState();
    expect(cleared).toMatchObject({
      selectedExecutionId: null, selectedMemberSessionId: null,
      memberRevision: null, memberCursor: null, memberSnapshots: [], memberMessages: [],
      memberMessagesLoading: false,
    });
    pending.resolve(detail({ contentRevision: 1 }));
    await refreshing;
    expect(useTeamStore.getState()).toBe(cleared);
  });

  it('ignores an old delta before revision comparison after switching members', async () => {
    await useTeamStore.getState().selectMember(member());
    const pending = deferred<SessionMessagesAfterResponse>();
    getDelta.mockReturnValueOnce(pending.promise);
    const refreshing = useTeamStore.getState().refreshMemberMessages();
    getDetail.mockResolvedValue(detail({ id: 'member-b', contentRevision: 5 }));
    await useTeamStore.getState().selectMember(member('member-b'));
    const selected = useTeamStore.getState();

    pending.resolve(delta({ revision: 9, newestSeq: 50 }));
    await refreshing;

    expect(getDetail).toHaveBeenCalledTimes(2);
    expect(useTeamStore.getState()).toBe(selected);
  });
});
