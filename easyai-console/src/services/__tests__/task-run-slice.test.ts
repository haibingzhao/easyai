import { describe, expect, it } from 'vitest';
import { sliceTaskTranscript } from '../stores/chat/task-run-slice';
import type { AssistantMessage, Message, UserMessage } from '@/types/message';

let clock = 1_000;
const nextTimestamp = () => clock++;

function user(content: string, metadata?: Record<string, string>): UserMessage {
  return { role: 'user', content, timestamp: nextTimestamp(), metadata };
}

function assistant(content: string): AssistantMessage {
  return { role: 'assistant', content, timestamp: nextTimestamp() };
}

describe('sliceTaskTranscript', () => {
  it('keeps only the firing a watch task opened', () => {
    const messages: Message[] = [
      user('run A', { watchRun: 'a' }),
      assistant('A answer'),
      user('run B', { watchRun: 'b' }),
      assistant('B answer'),
    ];

    expect(sliceTaskTranscript(messages, { id: 'a', type: 'watch' })).toEqual(messages.slice(0, 2));
    expect(sliceTaskTranscript(messages, { id: 'b', type: 'watch' })).toEqual(messages.slice(2));
  });

  it('keeps an empty transcript empty', () => {
    expect(sliceTaskTranscript([], { id: 'a', type: 'watch' })).toEqual([]);
  });

  it('treats a prompt with attachments as a run boundary', () => {
    const prompt = user('run A', { watchRun: 'a' });
    const messages: Message[] = [
      { ...prompt, role: 'user-with-attachments' },
      assistant('A answer'),
    ];

    expect(sliceTaskTranscript(messages, { id: 'a', type: 'watch' })).toEqual(messages);
  });

  it('renders nothing when a tagged session has no turn for this task', () => {
    const messages: Message[] = [user('run B', { watchRun: 'b' }), assistant('B answer')];

    expect(sliceTaskTranscript(messages, { id: 'missing', type: 'watch' })).toEqual([]);
  });

  it('keeps the whole transcript for task types that own their session', () => {
    const messages: Message[] = [
      user('first pass', { watchRun: 'a' }),
      assistant('first answer'),
      user('second pass', { watchRun: 'b' }),
      assistant('second answer'),
    ];

    expect(sliceTaskTranscript(messages, { id: 'a', type: 'memory_consolidation' })).toBe(messages);
  });

  it('keeps the whole transcript when a watch session predates the run tag', () => {
    const messages: Message[] = [user('legacy prompt'), assistant('legacy answer')];

    expect(sliceTaskTranscript(messages, { id: 'task-1', type: 'watch' })).toBe(messages);
  });

  it('uses the latest attempt when a retried task keeps its id', () => {
    const messages: Message[] = [
      user('attempt 1', { watchRun: 'a' }),
      assistant('attempt 1 answer'),
      user('other firing', { watchRun: 'b' }),
      assistant('other answer'),
      user('attempt 2', { watchRun: 'a' }),
      assistant('attempt 2 answer'),
    ];

    expect(sliceTaskTranscript(messages, { id: 'a', type: 'watch' })).toEqual(messages.slice(4));
  });

  it('shows nothing when a retry failed before writing a new prompt', () => {
    clock = 1_000;
    const messages: Message[] = [user('attempt 1', { watchRun: 'a' }), assistant('answer 1')];

    expect(sliceTaskTranscript(messages, { id: 'a', type: 'watch', retriedAt: 1_500 })).toEqual([]);
  });

  it('starts at the marker written after a retry', () => {
    clock = 1_000;
    const messages: Message[] = [user('attempt 1', { watchRun: 'a' }), assistant('answer 1')];
    clock = 1_500;
    messages.push(user('attempt 2', { watchRun: 'a' }), assistant('answer 2'));

    expect(sliceTaskTranscript(messages, { id: 'a', type: 'watch', retriedAt: 1_100 })).toEqual(
      messages.slice(2)
    );
  });

  it('ignores user turns that carry no run tag inside a firing', () => {
    const messages: Message[] = [
      user('run A', { watchRun: 'a' }),
      user('steering note', { source: 'steering' }),
      assistant('A answer'),
      user('run B', { watchRun: 'b' }),
      assistant('B answer'),
    ];

    expect(sliceTaskTranscript(messages, { id: 'a', type: 'watch' })).toEqual(messages.slice(0, 3));
  });
});
