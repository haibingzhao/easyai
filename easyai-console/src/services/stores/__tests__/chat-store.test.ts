import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock external dependencies before importing the store
vi.mock('@/services/goal-service', () => ({
  getGoal: vi.fn().mockResolvedValue(null),
}));

vi.mock('../chat/event-handler', () => ({
  handleChatEvent: vi.fn(),
  setLastRespondedPermissionToolCallId: vi.fn(),
}));

vi.mock('../chat/session-loader', () => ({
  commitStreamingMessageImpl: vi.fn((state) => ({
    messages: state.messages,
    streamingBlocks: [],
  })),
  loadSessionMessagesImpl: vi.fn(),
}));

vi.mock('../chat/message-converter', () => ({
  convertSnapshot: vi.fn(),
}));

import { useChatStore } from '../chat-store';
import type { Message } from '@/types/message';

describe('useChatStore', () => {
  beforeEach(() => {
    // Reset store to initial state before each test
    useChatStore.getState().clearChat();
  });

  describe('initial state', () => {
    it('should have correct default values', () => {
      const state = useChatStore.getState();
      expect(state.sessionId).toBeNull();
      expect(state.messages).toEqual([]);
      expect(state.isStreaming).toBe(false);
      expect(state.streamingBlocks).toEqual([]);
      expect(state.agentId).toBe('default');
      expect(state.todos).toEqual([]);
      expect(state.pendingPermission).toBeNull();
      expect(state.contextWindow).toBe(200_000);
    });
  });

  describe('message management', () => {
    it('should add a message', () => {
      const msg: Message = {
        role: 'user',
        content: 'Hello',
        timestamp: Date.now(),
      };
      useChatStore.getState().addMessage(msg);
      expect(useChatStore.getState().messages).toHaveLength(1);
      expect((useChatStore.getState().messages[0] as { content: string }).content).toBe('Hello');
    });

    it('should set messages replacing existing ones', () => {
      const msg1: Message = { role: 'user', content: 'A', timestamp: 1 };
      const msg2: Message = { role: 'assistant', content: 'B', timestamp: 2 };
      useChatStore.getState().addMessage(msg1);
      useChatStore.getState().setMessages([msg2]);
      expect(useChatStore.getState().messages).toHaveLength(1);
      expect((useChatStore.getState().messages[0] as { content: string }).content).toBe('B');
    });

    it('should set messageId on last user message via setLastUserMessageId', () => {
      const msg: Message = { role: 'user', content: 'Hi', timestamp: 1 };
      useChatStore.getState().addMessage(msg);
      useChatStore.getState().setLastUserMessageId('msg-123');
      const updated = useChatStore.getState().messages[0] as { messageId?: string };
      expect(updated.messageId).toBe('msg-123');
    });

    it('should truncate messages from a given index and clean checkpoints', () => {
      const msgs: Message[] = [
        { role: 'user', content: 'A', timestamp: 1, messageId: 'm1' },
        { role: 'assistant', content: 'B', timestamp: 2, messageId: 'm2' },
        { role: 'user', content: 'C', timestamp: 3, messageId: 'm3' },
      ];
      useChatStore.getState().setMessages(msgs);
      useChatStore.getState().addCheckpoint('m2', {
        messageId: 'm1',
        assistantMessageId: 'm2',
        snapshotHash: 'abc',
        filesChanged: [],
        additions: 1,
        deletions: 0,
        createdAt: Date.now(),
      });

      useChatStore.getState().truncateMessagesFrom(1);

      expect(useChatStore.getState().messages).toHaveLength(1);
      expect((useChatStore.getState().messages[0] as { content: string }).content).toBe('A');
      // Checkpoint for removed message should be cleaned
      expect(useChatStore.getState().checkpointsByMessageId['m2']).toBeUndefined();
    });
  });

  describe('streaming blocks', () => {
    it('should create a new text block on first appendToTextBlock', () => {
      useChatStore.getState().appendToTextBlock('Hello');
      const blocks = useChatStore.getState().streamingBlocks;
      expect(blocks).toHaveLength(1);
      expect(blocks[0].type).toBe('text');
      expect((blocks[0] as { content: string }).content).toBe('Hello');
    });

    it('should append to existing text block', () => {
      useChatStore.getState().appendToTextBlock('Hello');
      useChatStore.getState().appendToTextBlock(' World');
      const blocks = useChatStore.getState().streamingBlocks;
      expect(blocks).toHaveLength(1);
      expect((blocks[0] as { content: string }).content).toBe('Hello World');
    });

    it('should create separate text blocks for different messageIds', () => {
      useChatStore.getState().appendToTextBlock('A', 'msg1');
      useChatStore.getState().appendToTextBlock('B', 'msg2');
      const blocks = useChatStore.getState().streamingBlocks;
      expect(blocks).toHaveLength(2);
    });

    it('should create and append to thinking blocks', () => {
      useChatStore.getState().appendToThinkingBlock('thinking...');
      useChatStore.getState().appendToThinkingBlock(' more');
      const blocks = useChatStore.getState().streamingBlocks;
      expect(blocks).toHaveLength(1);
      expect(blocks[0].type).toBe('thinking');
      expect((blocks[0] as { content: string }).content).toBe('thinking... more');
    });

    it('should finish thinking block', () => {
      useChatStore.getState().appendToThinkingBlock('thought');
      useChatStore.getState().finishThinkingBlock(150);
      const block = useChatStore.getState().streamingBlocks[0];
      expect(block.type).toBe('thinking');
      expect((block as { isFinished?: boolean }).isFinished).toBe(true);
      expect((block as { durationMs?: number }).durationMs).toBe(150);
    });

    it('should not append to a finished thinking block', () => {
      useChatStore.getState().appendToThinkingBlock('done');
      useChatStore.getState().finishThinkingBlock();
      useChatStore.getState().appendToThinkingBlock('new thought');
      const blocks = useChatStore.getState().streamingBlocks;
      expect(blocks).toHaveLength(2);
    });
  });

  describe('tool blocks', () => {
    it('should start a tool block', () => {
      useChatStore.getState().startToolBlock('tc1', 'read_file', { path: '/a.ts' });
      const blocks = useChatStore.getState().streamingBlocks;
      expect(blocks).toHaveLength(1);
      expect(blocks[0].type).toBe('tool');
      const toolBlock = blocks[0] as { toolCall: { id: string; toolName: string; args: string; status: string } };
      expect(toolBlock.toolCall.id).toBe('tc1');
      expect(toolBlock.toolCall.toolName).toBe('read_file');
      expect(toolBlock.toolCall.status).toBe('PENDING');
    });

    it('should deduplicate tool blocks with same id', () => {
      useChatStore.getState().startToolBlock('tc1', 'read_file');
      useChatStore.getState().startToolBlock('tc1', 'write_file');
      const blocks = useChatStore.getState().streamingBlocks;
      expect(blocks).toHaveLength(1);
      const toolBlock = blocks[0] as { toolCall: { toolName: string } };
      expect(toolBlock.toolCall.toolName).toBe('write_file');
    });

    it('should append tool args', () => {
      useChatStore.getState().startToolBlock('tc1', 'shell');
      useChatStore.getState().appendToolArgs('tc1', '{"cmd":');
      useChatStore.getState().appendToolArgs('tc1', '"ls"}');
      const block = useChatStore.getState().streamingBlocks[0] as { toolCall: { args: string } };
      expect(block.toolCall.args).toBe('{"cmd":"ls"}');
    });

    it('should append and retrieve streaming tool output', () => {
      useChatStore.getState().appendToolOutput('tc1', 'line1\n');
      useChatStore.getState().appendToolOutput('tc1', 'line2\n');
      expect(useChatStore.getState().getStreamingToolOutput('tc1')).toBe('line1\nline2\n');
    });

    it('should commit tool result and clean up streaming output', () => {
      useChatStore.getState().startToolBlock('tc1', 'shell');
      useChatStore.getState().appendToolOutput('tc1', 'output text');
      useChatStore.getState().commitToolResult('tc1', undefined, false, 0);

      // Streaming output should be cleaned
      expect(useChatStore.getState().getStreamingToolOutput('tc1')).toBeUndefined();

      // Tool block should have result
      const block = useChatStore.getState().streamingBlocks[0] as {
        toolResult?: { result: string; isError: boolean; exitCode?: number };
      };
      expect(block.toolResult).toBeDefined();
      expect(block.toolResult!.result).toBe('output text');
      expect(block.toolResult!.isError).toBe(false);
      expect(block.toolResult!.exitCode).toBe(0);
    });
  });

  describe('queued messages', () => {
    it('should add, update, reorder, and remove queued messages', () => {
      const { addQueuedMessage, updateQueuedMessage, reorderQueuedMessages, removeQueuedMessage } = useChatStore.getState();

      addQueuedMessage({ id: 'q1', content: 'First', type: 'steer', status: 'pending' });
      addQueuedMessage({ id: 'q2', content: 'Second', type: 'followUp', status: 'pending' });
      expect(useChatStore.getState().queuedMessages).toHaveLength(2);

      updateQueuedMessage('q1', 'Updated First');
      expect(useChatStore.getState().queuedMessages[0].content).toBe('Updated First');

      reorderQueuedMessages(['q2', 'q1']);
      expect(useChatStore.getState().queuedMessages[0].id).toBe('q2');

      removeQueuedMessage('q2');
      expect(useChatStore.getState().queuedMessages).toHaveLength(1);
      expect(useChatStore.getState().queuedMessages[0].id).toBe('q1');
    });
  });

  describe('clearChat', () => {
    it('should reset all state to defaults', () => {
      // Set up some state
      useChatStore.getState().setSessionId('session-1');
      useChatStore.getState().addMessage({ role: 'user', content: 'Hi', timestamp: 1 });
      useChatStore.getState().setStreaming(true);
      useChatStore.getState().appendToTextBlock('text');

      useChatStore.getState().clearChat();

      const state = useChatStore.getState();
      expect(state.sessionId).toBeNull();
      expect(state.messages).toEqual([]);
      expect(state.isStreaming).toBe(false);
      expect(state.streamingBlocks).toEqual([]);
    });
  });
});
