import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock sub-agent handler to avoid complex dependency chain
vi.mock('../chat/sub-agent-handler', () => ({
  dispatchSubAgentEvent: vi.fn(),
}));

import { handleChatEvent, type ChatStateShape } from '../chat/event-handler';
import type { SocketEvent } from '@/types/socket-event';
import type { StreamingBlock } from '../chat/types';

type SetFn = (partial: Partial<ChatStateShape> | ((state: ChatStateShape) => Partial<ChatStateShape>)) => void;

/**
 * Create a minimal mock ChatStateShape for testing event routing.
 */
function createMockState(overrides: Partial<ChatStateShape> = {}): ChatStateShape {
  return {
    sessionId: null,
    isStreaming: true,
    isFileWriting: false,
    cancelReason: null,
    streamingBlocks: [] as StreamingBlock[],
    streamingToolOutputs: {},
    messages: [],
    pendingPermission: null,
    pendingMessageData: {},
    cumulativeUsage: null,
    contextTokens: 0,
    contextWindow: 200_000,
    todos: [],
    subAgentTodos: {},
    sessionVariables: {},
    isCompacting: false,
    retryInfo: null,
    checkpointsByMessageId: {},
    revertState: null,
    fileReviewOverrides: {},
    currentGoal: null,
    queuedMessages: [],
    appendToTextBlock: vi.fn(),
    appendToThinkingBlock: vi.fn(),
    finishThinkingBlock: vi.fn(),
    startToolBlock: vi.fn(),
    appendToolArgs: vi.fn(),
    appendToolOutput: vi.fn(),
    commitToolResult: vi.fn(),
    addMessage: vi.fn(),
    setLastUserMessageId: vi.fn(),
    commitStreamingMessage: vi.fn(),
    removeQueuedMessage: vi.fn(),
    setIsFileWriting: vi.fn(),
    refreshGoal: vi.fn().mockResolvedValue(undefined),
    applyVariableUpdate: vi.fn(),
    ...overrides,
  };
}

describe('handleChatEvent', () => {
  let mockState: ChatStateShape;
  let get: () => ChatStateShape;
  let set: SetFn & ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockState = createMockState();
    get = () => mockState;
    set = vi.fn((partial: Partial<ChatStateShape> | ((state: ChatStateShape) => Partial<ChatStateShape>)) => {
      if (typeof partial === 'function') {
        Object.assign(mockState, partial(mockState));
      } else {
        Object.assign(mockState, partial);
      }
    }) as SetFn & ReturnType<typeof vi.fn>;
  });

  describe('start event', () => {
    it('should set sessionId and clear cancelReason', () => {
      mockState.cancelReason = 'Previous Error';
      const event: SocketEvent = { type: 'start', sessionId: 'sess-1' };

      handleChatEvent(event, get, set);

      expect(set).toHaveBeenCalledWith(expect.objectContaining({ cancelReason: null }));
    });
  });

  describe('text_delta event', () => {
    it('should call appendToTextBlock with delta', () => {
      const event: SocketEvent = {
        type: 'text_delta',
        contentIndex: 0,
        delta: 'Hello world',
        messageId: 'msg-1',
      };

      handleChatEvent(event, get, set);

      expect(mockState.appendToTextBlock).toHaveBeenCalledWith('Hello world', 'msg-1');
    });
  });

  describe('thinking_delta event', () => {
    it('should call appendToThinkingBlock with delta', () => {
      const event: SocketEvent = {
        type: 'thinking_delta',
        contentIndex: 0,
        delta: 'Let me think...',
        messageId: 'msg-1',
      };

      handleChatEvent(event, get, set);

      expect(mockState.appendToThinkingBlock).toHaveBeenCalledWith('Let me think...', 'msg-1');
    });
  });

  describe('thinking_end event', () => {
    it('should call finishThinkingBlock with duration', () => {
      const event: SocketEvent = {
        type: 'thinking_end',
        contentIndex: 0,
        durationMs: 250,
      };

      handleChatEvent(event, get, set);

      expect(mockState.finishThinkingBlock).toHaveBeenCalledWith(250);
    });
  });

  describe('toolcall_start event', () => {
    it('should call startToolBlock', () => {
      const event: SocketEvent = {
        type: 'toolcall_start',
        contentIndex: 0,
        id: 'tc-1',
        toolName: 'read_file',
      };

      handleChatEvent(event, get, set);

      expect(mockState.startToolBlock).toHaveBeenCalledWith('tc-1', 'read_file', undefined);
    });
  });

  describe('toolcall_delta event', () => {
    it('should call appendToolArgs', () => {
      const event: SocketEvent = {
        type: 'toolcall_delta',
        contentIndex: 0,
        id: 'tc-1',
        delta: '{"path": "/src"}',
      };

      handleChatEvent(event, get, set);

      expect(mockState.appendToolArgs).toHaveBeenCalledWith('tc-1', '{"path": "/src"}');
    });
  });

  describe('tool_execution_start event (update_variable)', () => {
    it('should apply object-shaped variables', () => {
      const event: SocketEvent = {
        type: 'tool_execution_start',
        toolCallId: 'tc-1',
        toolName: 'update_variable',
        args: { variables: { revenue: '1.23B' }, deleteKeys: ['stale'] },
      };

      handleChatEvent(event, get, set);

      expect(mockState.applyVariableUpdate).toHaveBeenCalledWith({ revenue: '1.23B' }, ['stale']);
    });

    it('should coerce string-encoded variables instead of spreading per-character', () => {
      // Regression: some models send `variables` as a JSON string. Spreading the raw string
      // would decompose it into index keys ({0:'{', 1:'"', ...}).
      const event: SocketEvent = {
        type: 'tool_execution_start',
        toolCallId: 'tc-1',
        toolName: 'update_variable',
        args: { variables: '{"revenue":"1.23B","margin":"23.4%"}' },
      };

      handleChatEvent(event, get, set);

      expect(mockState.applyVariableUpdate).toHaveBeenCalledWith(
        { revenue: '1.23B', margin: '23.4%' },
        []
      );
    });

    it('should coerce string-encoded deleteKeys into an array', () => {
      const event: SocketEvent = {
        type: 'tool_execution_start',
        toolCallId: 'tc-1',
        toolName: 'update_variable',
        args: { deleteKeys: '["a","b"]' },
      };

      handleChatEvent(event, get, set);

      expect(mockState.applyVariableUpdate).toHaveBeenCalledWith({}, ['a', 'b']);
    });

    it('should not apply update when args are unparseable', () => {
      const event: SocketEvent = {
        type: 'tool_execution_start',
        toolCallId: 'tc-1',
        toolName: 'update_variable',
        args: { variables: 'not-json' },
      };

      handleChatEvent(event, get, set);

      expect(mockState.applyVariableUpdate).not.toHaveBeenCalled();
    });
  });

  describe('tool_execution_update event', () => {
    it('should call appendToolOutput', () => {
      const event: SocketEvent = {
        type: 'tool_execution_update',
        toolCallId: 'tc-1',
        output: 'file content here',
      };

      handleChatEvent(event, get, set);

      expect(mockState.appendToolOutput).toHaveBeenCalledWith('tc-1', 'file content here');
    });
  });

  describe('done event', () => {
    it('should commit streaming message and set isStreaming to false', () => {
      const event: SocketEvent = { type: 'done', reason: 'stop' };

      handleChatEvent(event, get, set);

      expect(mockState.commitStreamingMessage).toHaveBeenCalled();
      expect(set).toHaveBeenCalledWith(
        expect.objectContaining({ isStreaming: false, isFileWriting: false })
      );
    });

    it('should set cancelReason on max_iterations', () => {
      const event: SocketEvent = { type: 'done', reason: 'stop', endReason: 'max_iterations' };

      handleChatEvent(event, get, set);

      expect(set).toHaveBeenCalledWith(
        expect.objectContaining({ cancelReason: 'Max Iterations Reached' })
      );
    });
  });

  describe('error event', () => {
    it('should commit streaming, add error message, and stop streaming', () => {
      const event: SocketEvent = {
        type: 'error',
        reason: 'send_failed',
        errorMessage: 'Something went wrong',
      };

      handleChatEvent(event, get, set);

      expect(mockState.commitStreamingMessage).toHaveBeenCalled();
      expect(mockState.addMessage).toHaveBeenCalledWith(
        expect.objectContaining({
          role: 'error',
          content: 'Something went wrong',
        })
      );
      expect(set).toHaveBeenCalledWith(
        expect.objectContaining({ isStreaming: false })
      );
    });

    it('should preserve pendingPermission on connection_lost', () => {
      mockState.pendingPermission = {
        type: 'permission_request',
        toolCallId: 'tc-1',
        toolName: 'shell',
        permission: 'execute',
        pattern: '*',
      };
      const event: SocketEvent = {
        type: 'error',
        reason: 'connection_lost',
        errorMessage: 'Connection lost',
      };

      handleChatEvent(event, get, set);

      expect(set).toHaveBeenCalledWith(
        expect.objectContaining({
          cancelReason: 'Connection Lost',
          pendingPermission: mockState.pendingPermission,
        })
      );
    });
  });

  describe('cancelled event', () => {
    it('should commit streaming, stop, and set cancel reason', () => {
      const event: SocketEvent = { type: 'cancelled', reason: 'user_cancelled' };

      handleChatEvent(event, get, set);

      expect(mockState.commitStreamingMessage).toHaveBeenCalled();
      expect(set).toHaveBeenCalledWith(
        expect.objectContaining({
          isStreaming: false,
          cancelReason: 'Manually Cancelled',
          queuedMessages: [],
        })
      );
    });
  });

  describe('permission_request event', () => {
    it('should set pendingPermission and pause streaming', () => {
      const event: SocketEvent = {
        type: 'permission_request',
        toolCallId: 'tc-1',
        toolName: 'shell',
        permission: 'execute_command',
        pattern: 'rm *',
        arguments: { command: 'rm -rf /tmp/test' },
      };

      handleChatEvent(event, get, set);

      expect(set).toHaveBeenCalledWith(
        expect.objectContaining({
          isStreaming: false,
          pendingPermission: expect.objectContaining({
            toolCallId: 'tc-1',
            toolName: 'shell',
            permission: 'execute_command',
          }),
        })
      );
    });
  });

  describe('compaction events', () => {
    it('should append an in-progress compaction block and set isCompacting on compaction_start while streaming', () => {
      const event: SocketEvent = {
        type: 'compaction_start',
        turnId: 1,
        reason: 'context_overflow',
        messageCount: 50,
      };

      handleChatEvent(event, get, set);

      expect(mockState.isCompacting).toBe(true);
      expect(mockState.streamingBlocks).toHaveLength(1);
      expect(mockState.streamingBlocks[0]).toMatchObject({
        type: 'compaction',
        isFinished: false,
      });
    });

    it('should only set isCompacting on compaction_start when not streaming', () => {
      mockState = createMockState({ isStreaming: false });
      const event: SocketEvent = {
        type: 'compaction_start',
        turnId: 1,
        reason: 'context_overflow',
        messageCount: 50,
      };

      handleChatEvent(event, get, set);

      expect(mockState.isCompacting).toBe(true);
      expect(mockState.streamingBlocks).toHaveLength(0);
    });

    it('should finalize the in-progress compaction block in place on compaction_end while streaming', () => {
      // Simulate compaction_start having appended an in-progress block.
      mockState.streamingBlocks = [
        { type: 'compaction', isFinished: false, compactedCount: 0, tokensSaved: 0, timestamp: 1, id: 'compaction-1' },
      ];
      const event: SocketEvent = {
        type: 'compaction_end',
        turnId: 1,
        summary: 'Compacted 30 messages',
        compactedCount: 30,
        tokensSaved: 5000,
        currentTokens: 10000,
      };

      handleChatEvent(event, get, set);

      // isCompacting is cleared and the in-progress block is finalized in place
      // (preserving its position), so the indicator switches from the live timer to
      // the final stats during streaming.
      expect(mockState.isCompacting).toBe(false);
      expect(mockState.streamingBlocks).toHaveLength(1);
      expect(mockState.streamingBlocks[0]).toMatchObject({
        type: 'compaction',
        isFinished: true,
        compactedCount: 30,
        tokensSaved: 5000,
        currentTokens: 10000,
      });
      // Must NOT be added to committed messages while streaming
      expect(mockState.addMessage).not.toHaveBeenCalled();
    });

    it('should add compaction indicator message on compaction_end when not streaming', () => {
      mockState = createMockState({ isStreaming: false });
      const event: SocketEvent = {
        type: 'compaction_end',
        turnId: 1,
        summary: 'Compacted 30 messages',
        compactedCount: 30,
        tokensSaved: 5000,
        currentTokens: 10000,
      };

      handleChatEvent(event, get, set);

      expect(mockState.isCompacting).toBe(false);
      expect(mockState.addMessage).toHaveBeenCalledWith(
        expect.objectContaining({
          role: 'custom',
          customType: 'compaction',
        })
      );
    });
  });

  describe('retry event', () => {
    it('should set retryInfo on retry event', () => {
      const event: SocketEvent = {
        type: 'retry',
        attempt: 1,
        maxRetries: 3,
        backoffMs: 1000,
      };

      handleChatEvent(event, get, set);

      expect(set).toHaveBeenCalledWith(
        expect.objectContaining({ retryInfo: { attempt: 1, maxRetries: 3 } })
      );
    });

    it('should strip trailing text/thinking blocks but keep tool blocks', () => {
      const toolBlock: StreamingBlock = {
        type: 'tool',
        toolCall: { id: 'tc-1', toolName: 'bash', args: '', status: 'COMPLETED' },
      };
      const thinkingBlock: StreamingBlock = {
        type: 'thinking',
        content: 'partial thinking',
        id: 'thinking-1',
      };
      const textBlock: StreamingBlock = {
        type: 'text',
        content: 'partial text',
        id: 'text-1',
      };
      mockState.streamingBlocks = [toolBlock, thinkingBlock, textBlock];

      const event: SocketEvent = {
        type: 'retry',
        attempt: 2,
        maxRetries: 3,
        backoffMs: 2000,
      };

      handleChatEvent(event, get, set);

      // Tool block preserved; trailing thinking/text stripped
      expect(mockState.streamingBlocks).toEqual([toolBlock]);
      expect(set).toHaveBeenCalledWith(
        expect.objectContaining({ retryInfo: { attempt: 2, maxRetries: 3 } })
      );
    });

    it('should clear retryInfo when content delta arrives', () => {
      mockState.retryInfo = { attempt: 1, maxRetries: 3 };

      const event: SocketEvent = {
        type: 'text_delta',
        contentIndex: 0,
        delta: 'hello',
        messageId: 'msg-1',
      };

      handleChatEvent(event, get, set);

      expect(set).toHaveBeenCalledWith({ retryInfo: null });
    });

    it('should clear retryInfo on done event', () => {
      mockState.retryInfo = { attempt: 1, maxRetries: 3 };

      const event: SocketEvent = { type: 'done', reason: 'completed' };

      handleChatEvent(event, get, set);

      expect(set).toHaveBeenCalledWith(expect.objectContaining({ retryInfo: null }));
    });
  });

  describe('goal_status event', () => {
    it('should set currentGoal', () => {
      const event: SocketEvent = {
        type: 'goal_status',
        sessionId: 'sess-1',
        objective: 'Fix the bug',
        status: 'active',
        turnCount: 3,
        maxTurns: 20,
        elapsedSeconds: 45,
      };

      handleChatEvent(event, get, set);

      expect(set).toHaveBeenCalledWith(
        expect.objectContaining({
          currentGoal: expect.objectContaining({ objective: 'Fix the bug', status: 'active' }),
        })
      );
    });
  });

  describe('user_message_ack event', () => {
    it('should call setLastUserMessageId', () => {
      const event: SocketEvent = {
        type: 'user_message_ack',
        messageId: 'msg-abc',
      };

      handleChatEvent(event, get, set);

      expect(mockState.setLastUserMessageId).toHaveBeenCalledWith('msg-abc');
    });
  });

  describe('checkpoint event', () => {
    it('should store checkpoint data', () => {
      const event: SocketEvent = {
        type: 'checkpoint',
        messageId: 'msg-1',
        assistantMessageId: 'msg-2',
        snapshotHash: 'hash123',
        filesChanged: [{ path: '/src/a.ts', additions: 5, deletions: 2, status: 'modified' }],
        additions: 5,
        deletions: 2,
      };

      handleChatEvent(event, get, set);

      expect(set).toHaveBeenCalled();
      // Verify checkpoint was stored via set callback
      const setCall = set.mock.calls.find(
        (call) => typeof call[0] === 'function'
      );
      expect(setCall).toBeDefined();
    });
  });

  describe('revert event', () => {
    it('should set revertState', () => {
      const event: SocketEvent = {
        type: 'revert',
        messageId: 'msg-1',
        additions: 10,
        deletions: 3,
        filesCount: 2,
      };

      handleChatEvent(event, get, set);

      expect(set).toHaveBeenCalledWith(
        expect.objectContaining({
          revertState: expect.objectContaining({
            messageId: 'msg-1',
            additions: 10,
            deletions: 3,
            filesCount: 2,
          }),
        })
      );
    });
  });
});
