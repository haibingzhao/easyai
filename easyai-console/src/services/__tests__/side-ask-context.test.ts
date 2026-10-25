import { describe, expect, it } from 'vitest';
import {
  buildSideAskContext,
  CONTEXT_ENTRY_MAX_CHARS,
  CONTEXT_MAX_CHARS,
} from '../stores/chat/side-ask-context';
import type { AssistantMessage, Message, UserMessage } from '@/types/message';

let clock = 1_000;
const nextTimestamp = () => clock++;

function user(content: string, extra?: Partial<UserMessage>): UserMessage {
  return { role: 'user', content, timestamp: nextTimestamp(), ...extra };
}

function assistant(content: string, extra?: Partial<AssistantMessage>): AssistantMessage {
  return { role: 'assistant', content, timestamp: nextTimestamp(), ...extra };
}

function flattened(context: { content: string }[]): string {
  return context.map((entry) => entry.content).join('\n');
}

describe('buildSideAskContext', () => {
  it('takes the anchor turn plus two previous turns, oldest first', () => {
    const messages: Message[] = [
      user('u1', {
        role: 'user-with-attachments',
        attachments: [{ id: 'a1', name: 'spec.pdf', mimeType: 'application/pdf', data: '', size: 0 }],
      }),
      assistant('a1'),
      user('u2'),
      assistant('a2'),
      user('u3'),
      assistant('a3', {
        thinking: 'secret thinking',
        toolCalls: [
          { id: 't1', toolName: 'read_file', args: '{"path":"/etc/secret"}' },
          { id: 't2', toolName: 'read_file', args: '{"path":"/etc/secret"}' },
        ],
        toolResults: [
          { id: 't1', toolName: 'read_file', result: 'tool output body', isError: false },
          { id: 't2', toolName: 'read_file', result: 'tool output body', isError: true },
        ],
        subAgentMessages: [{ toolCallId: 't3', agentName: 'worker', messages: [user('sub transcript')] }],
      }),
      user('u4'),
      assistant('a4'),
    ];

    const { context, truncated } = buildSideAskContext(messages, 5, 'a3');

    expect(context.map((entry) => entry.role)).toEqual([
      'user', 'assistant', 'user', 'assistant', 'user', 'assistant',
    ]);
    expect(context[0].content).toContain('u1');
    expect(context[0].content).toContain('[attachment: spec.pdf]');
    expect(context[5].content).toContain('a3');
    expect(context[5].content).toContain('[tool activity: read_file (2 calls, 1 error)]');
    expect(flattened(context)).not.toContain('secret thinking');
    expect(flattened(context)).not.toContain('/etc/secret');
    expect(flattened(context)).not.toContain('tool output body');
    expect(flattened(context)).not.toContain('sub transcript');
    expect(flattened(context)).not.toContain('u4');
    expect(truncated).toBe(false);
  });

  it('skips system-injected user messages without breaking the turn', () => {
    const messages: Message[] = [
      user('u1'),
      assistant('a1'),
      user('internal one', { metadata: { source: 'completion_check' } }),
      assistant('a2'),
      user('internal two', { metadata: { systemOrigin: 'background_task' } }),
      assistant('a3'),
      user('u2'),
      assistant('a4'),
    ];

    const { context } = buildSideAskContext(messages, 5, 'a3');

    expect(context).toHaveLength(2);
    expect(context[0].content).toContain('u1');
    expect(context[1].content).toContain('a1');
    expect(context[1].content).toContain('a2');
    expect(context[1].content).toContain('a3');
    expect(flattened(context)).not.toContain('internal');
  });

  it('falls back to the newest two turns without an anchor and returns nothing for an empty chat', () => {
    const messages: Message[] = [
      user('u1'), assistant('a1'),
      user('u2'), assistant('a2'),
      user('u3'), assistant('a3'),
    ];

    const { context } = buildSideAskContext(messages, null, 'a3');
    expect(context.map((entry) => entry.content)).toEqual(['u2', 'a2', 'u3', 'a3']);

    const empty = buildSideAskContext([], null, 'a3');
    expect(empty.context).toEqual([]);
    expect(empty.truncated).toBe(false);
  });

  it('caps the listed tool names in the activity line', () => {
    const toolCalls = ['t1', 't2', 't3', 't4', 't5', 't6', 't7'].map((id, index) => ({
      id,
      toolName: `tool_${index}`,
      args: '{}',
    }));
    const messages: Message[] = [user('u1'), assistant('a1', { toolCalls })];

    const { context } = buildSideAskContext(messages, 1, 'a1');

    expect(context[1].content).toContain('tool_0, tool_1, tool_2, tool_3, tool_4, tool_5, +1 more');
    expect(context[1].content).toContain('(7 calls)');
  });

  it('clips the anchor entry around the selection', () => {
    const long = `${'h'.repeat(4_000)}NEEDLE${'t'.repeat(4_000)}`;
    const messages: Message[] = [user('u1'), assistant(long)];

    const { context, truncated, clipped } = buildSideAskContext(messages, 1, 'NEEDLE');

    expect(clipped).toBe(true);
    expect(truncated).toBe(false);
    expect(context[1].content).toContain('NEEDLE');
    expect(context[1].content.length).toBeLessThanOrEqual(CONTEXT_ENTRY_MAX_CHARS);
    expect(context[1].content.startsWith('…')).toBe(true);
    expect(context[1].content.endsWith('…')).toBe(true);
  });

  it('clips around a selection inside a long user message', () => {
    const long = `${'h'.repeat(4_000)}NEEDLE${'t'.repeat(4_000)}`;
    const messages: Message[] = [user(long)];

    const { context, truncated, clipped } = buildSideAskContext(messages, 0, 'NEEDLE');

    expect(clipped).toBe(true);
    expect(truncated).toBe(false);
    expect(context[0].content).toContain('NEEDLE');
    expect(context[0].content.length).toBeLessThanOrEqual(CONTEXT_ENTRY_MAX_CHARS);
  });

  it('drops the oldest entries over the budget and keeps the anchor turn', () => {
    const block = (label: string) => label + 'x'.repeat(2_500);
    const messages: Message[] = [
      user(block('u-prev2')), assistant(block('a-prev2')),
      user(block('u-prev1')), assistant(block('a-prev1')),
      user(block('u-anchor')), assistant(block('a-anchor')),
    ];

    const { context, truncated, clipped } = buildSideAskContext(messages, 5, 'a-anchor');
    const total = context.reduce((sum, entry) => sum + entry.content.length, 0);

    expect(truncated).toBe(true);
    expect(clipped).toBe(false);
    expect(total).toBeLessThanOrEqual(CONTEXT_MAX_CHARS);
    expect(flattened(context)).toContain('u-anchor');
    expect(flattened(context)).toContain('a-anchor');
    expect(flattened(context)).not.toContain('u-prev2');
    expect(flattened(context)).not.toContain('a-prev2');
  });
});